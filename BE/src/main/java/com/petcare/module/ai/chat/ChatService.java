package com.petcare.module.ai.chat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.petcare.module.ai.dto.ChatRequest;
import com.petcare.module.ai.dto.ChatResponse;
import com.petcare.module.ai.dto.ChatSource;
import com.petcare.module.ai.dto.EmergencyBranch;
import com.petcare.module.ai.entity.AiChatLog;
import com.petcare.module.ai.entity.ChatErrorKind;
import com.petcare.module.ai.entity.ChatResult;
import com.petcare.module.ai.exception.ChatValidationException;
import com.petcare.module.ai.llm.AiProviderException;
import com.petcare.module.ai.llm.EmbeddingClient;
import com.petcare.module.ai.llm.LlmAnswer;
import com.petcare.module.ai.llm.LlmClient;
import com.petcare.module.ai.repository.AiChatLogRepository;
import com.petcare.module.ai.retrieval.ChunkRetriever;
import com.petcare.module.ai.retrieval.RetrievedChunk;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Đường ống hỏi đáp UC90 (07 §5.2, docs/adr/0131). Thứ tự cố định: kiểm đầu vào → che email / SĐT / số giấy tờ →
 * cấp cứu (không nhúng, không LLM) → bệnh án → ý định cá nhân → nhúng → truy hồi → ngưỡng τ → LLM → hậu kiểm → nhật ký
 * và bộ đếm. Bước 1 còn có giới hạn tần suất theo IP ở {@code RateLimitFilter} (BR-AI-09), chạy trước cả controller.
 * <p>
 * Không có {@code @Transactional}: lệnh nhúng và lệnh LLM có thể chậm tới {@code ai.llm.timeout_seconds} nên không được
 * giữ kết nối CSDL; truy hồi và ghi nhật ký mỗi lần một câu lệnh ngắn. Không thử lại nhà cung cấp trong cùng request
 * (BR-AI-10). Mọi kết quả nghiệp vụ là một {@link ChatResponse}; chỉ đầu vào sai là {@link ChatValidationException}.
 * <p>
 * Ngữ cảnh khách (tên các thú còn sống) đi vào {@link PersonalIntentRouter} qua tham số {@code petNames}; endpoint công
 * khai không biết khách là ai nên truyền rỗng. Nhánh {@code /me} (so khớp tên thú, {@code PersonalService}, che tên thú)
 * là việc của AI-3, chưa có ở đây.
 */
@Service
public class ChatService {

    private static final int MAX_SOURCES = 3;
    private static final String COUNTER = "ai.chat.result";
    private static final String NO_ERROR = "NONE";

    private final SystemConfigApi config;
    private final EmergencyDetector emergency;
    private final PersonalIntentRouter router;
    private final DoseGuard doseGuard;
    private final BranchQueryApi branches;
    private final EmbeddingClient embeddings;
    private final LlmClient llm;
    private final ChunkRetriever retriever;
    private final AiChatLogRepository logs;
    private final MeterRegistry meters;

    public ChatService(SystemConfigApi config, EmergencyDetector emergency, PersonalIntentRouter router,
            DoseGuard doseGuard, BranchQueryApi branches, EmbeddingClient embeddings, LlmClient llm,
            ChunkRetriever retriever, AiChatLogRepository logs, MeterRegistry meters) {
        this.config = config;
        this.emergency = emergency;
        this.router = router;
        this.doseGuard = doseGuard;
        this.branches = branches;
        this.embeddings = embeddings;
        this.llm = llm;
        this.retriever = retriever;
        this.logs = logs;
        this.meters = meters;
    }

    /** {@code POST /public/ai/chat} (ai-v1 #1): khách vãng lai, không có tên thú. */
    public ChatResponse ask(ChatRequest request) {
        return run(request, List.of());
    }

    private ChatResponse run(ChatRequest request, List<String> petNames) {
        long started = System.nanoTime();
        String question = request.question() == null ? "" : request.question().strip();
        List<String> previous = request.previousQuestions() == null ? List.of() : request.previousQuestions();
        validate(question, previous);

        String masked = PiiMasker.mask(question);
        List<String> maskedPrevious = previous.stream().map(text -> PiiMasker.mask(text.strip())).toList();
        Trace trace = new Trace(masked, started);

        if (emergency.isEmergency(masked)) {
            return finish(emergencyResponse(), trace, true);
        }
        if (router.isMedicalRecordQuestion(masked, petNames)) {
            return finish(ChatResponse.canned(ChatResult.MEDICAL_REFUSAL, ChatTexts.MEDICAL_RECORD, false), trace, true);
        }
        if (router.detect(masked, petNames).isPresent()) {
            // BR-AI-28a: không biết khách là ai. Câu cá nhân không ghi nội dung vào nhật ký (BR-AI-24).
            return finish(ChatResponse.canned(ChatResult.NEED_LOGIN, ChatTexts.NEED_LOGIN, false), trace, false);
        }
        return retrieveAndAnswer(masked, maskedPrevious, trace);
    }

    private ChatResponse retrieveAndAnswer(String question, List<String> previous, Trace trace) {
        List<RetrievedChunk> nearest;
        try {
            float[] vector = embeddings.embedQuery(question);
            nearest = retriever.nearest(vector, config.getInt(ConfigKey.AI_RETRIEVAL_TOP_K));
        } catch (AiProviderException e) {
            return unavailable(e, trace);
        }
        trace.topSimilarity = nearest.stream().mapToDouble(RetrievedChunk::similarity).boxed().max(Double::compare)
                .orElse(null);
        double threshold = config.getDecimal(ConfigKey.AI_RETRIEVAL_MIN_SIMILARITY).doubleValue();
        List<RetrievedChunk> hits = nearest.stream().filter(chunk -> chunk.similarity() >= threshold).toList();
        if (hits.isEmpty()) {
            return finish(outOfScope(), trace, true);
        }

        trace.llmModel = llm.llmModel();
        LlmAnswer reply;
        try {
            reply = llm.generate(ChatPrompt.SYSTEM, ChatPrompt.userBlock(hits, previous, question));
        } catch (AiProviderException e) {
            return unavailable(e, trace);
        }
        trace.llm = reply;
        if (!reply.inScope() || ChatPrompt.leaksSystemPrompt(reply.answer())) {
            return finish(outOfScope(), trace, true);
        }
        if (doseGuard.violates(reply.answer())) {
            return finish(ChatResponse.canned(ChatResult.MEDICAL_REFUSAL, ChatTexts.MEDICAL_REFUSAL, true), trace, true);
        }
        return finish(ChatResponse.answered(reply.answer(), sourcesOf(hits), ChatTexts.DISCLAIMER), trace, true);
    }

    /** Tối đa 3 tài liệu có đoạn tương đồng cao nhất, không trùng (BR-AI-05); {@code hits} đã sắp giảm dần. */
    private static List<ChatSource> sourcesOf(List<RetrievedChunk> hits) {
        Map<Long, ChatSource> documents = new LinkedHashMap<>();
        for (RetrievedChunk hit : hits) {
            documents.putIfAbsent(hit.documentId(), new ChatSource(hit.documentId(), hit.title()));
            if (documents.size() == MAX_SOURCES) {
                break;
            }
        }
        return List.copyOf(documents.values());
    }

    private ChatResponse emergencyResponse() {
        List<EmergencyBranch> list = branches.findEmergencyBranches().stream()
                .map(b -> new EmergencyBranch(b.branchId(), b.name(), b.address(), b.phone())).toList();
        return ChatResponse.emergency(list.isEmpty() ? ChatTexts.EMERGENCY_NO_BRANCH : ChatTexts.EMERGENCY, list);
    }

    private static ChatResponse outOfScope() {
        return ChatResponse.canned(ChatResult.OUT_OF_SCOPE, ChatTexts.OUT_OF_SCOPE, true);
    }

    /** BR-AI-10: AI hỏng không chặn trang: HTTP 200, câu mẫu, không thử lại; ghi {@code error_kind} và đếm riêng. */
    private ChatResponse unavailable(AiProviderException error, Trace trace) {
        trace.errorKind = error.kind();
        return finish(ChatResponse.canned(ChatResult.UNAVAILABLE, ChatTexts.UNAVAILABLE, true), trace, true);
    }

    private ChatResponse finish(ChatResponse response, Trace trace, boolean logContent) {
        ChatErrorKind errorKind = trace.errorKind;
        meters.counter(COUNTER, "result", response.result().name(), "error_kind",
                errorKind == null ? NO_ERROR : errorKind.name()).increment();
        LlmAnswer usage = trace.llm;
        Long[] sourceIds = response.sources().isEmpty() ? null
                : response.sources().stream().map(ChatSource::documentId).toArray(Long[]::new);
        logs.save(AiChatLog.of(response.result(), errorKind, logContent ? trace.question : null,
                logContent ? PiiMasker.mask(response.answer()) : null, similarityOf(trace), sourceIds, trace.llmModel,
                usage == null ? null : usage.inputTokens(), usage == null ? null : usage.outputTokens(),
                usage == null ? null : usage.thoughtTokens(), trace.latencyMs()));
        return response;
    }

    /** {@code top_similarity} là {@code NUMERIC(4,3)}; vector không chuẩn (NaN) thì để trống thay vì làm hỏng lượt hỏi. */
    private static BigDecimal similarityOf(Trace trace) {
        if (trace.topSimilarity == null || !Double.isFinite(trace.topSimilarity)) {
            return null;
        }
        return BigDecimal.valueOf(trace.topSimilarity).setScale(3, RoundingMode.HALF_UP);
    }

    /** BR-AI-08: giới hạn đọc từ [CFG] ở mỗi request, nên Bean Validation (hằng biên dịch) không đủ. */
    private void validate(String question, List<String> previous) {
        int maxChars = config.getInt(ConfigKey.AI_CHAT_MAX_QUESTION_CHARS);
        int maxPrevious = config.getInt(ConfigKey.AI_CHAT_MAX_PREVIOUS_QUESTIONS);
        if (question.isEmpty()) {
            throw new ChatValidationException("Câu hỏi không được để trống");
        }
        if (length(question) > maxChars) {
            throw new ChatValidationException("Câu hỏi tối đa " + maxChars + " ký tự");
        }
        if (previous.size() > maxPrevious) {
            throw new ChatValidationException("Tối đa " + maxPrevious + " câu hỏi trước");
        }
        for (String text : previous) {
            if (text == null || text.isBlank()) {
                throw new ChatValidationException("Câu hỏi trước không được để trống");
            }
            if (length(text.strip()) > maxChars) {
                throw new ChatValidationException("Mỗi câu hỏi trước tối đa " + maxChars + " ký tự");
            }
        }
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    /** Dữ liệu một lượt để ghi nhật ký (BR-AI-24); {@code question} đã che. */
    private static final class Trace {
        final String question;
        final long startedNanos;
        Double topSimilarity;
        String llmModel;
        LlmAnswer llm;
        ChatErrorKind errorKind;

        Trace(String question, long startedNanos) {
            this.question = question;
            this.startedNanos = startedNanos;
        }

        int latencyMs() {
            return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedNanos) / 1_000_000);
        }
    }
}
