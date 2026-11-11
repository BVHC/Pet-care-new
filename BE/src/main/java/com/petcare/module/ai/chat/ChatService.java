package com.petcare.module.ai.chat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

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
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
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
 * khai không biết khách là ai nên truyền rỗng. {@code /me} (docs/adr/0132) thêm hai việc: ý định cá nhân do
 * {@link PersonalService} trả bằng câu mẫu (không nhúng, không LLM, không ghi nội dung vào nhật ký), và tên thú được che
 * thành {@code [thú]} <b>sau</b> định tuyến, <b>trước</b> nhúng và LLM (BR-AI-12b). Nhật ký ghi bản đã che tên thú ở mọi
 * nhánh có ghi nội dung, nên "Mochi bị co giật" cũng chỉ vào nhật ký là "[thú] bị co giật".
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
    private final CustomerQueryApi customers;
    private final PetQueryApi pets;
    private final PersonalService personal;

    public ChatService(SystemConfigApi config, EmergencyDetector emergency, PersonalIntentRouter router,
            DoseGuard doseGuard, BranchQueryApi branches, EmbeddingClient embeddings, LlmClient llm,
            ChunkRetriever retriever, AiChatLogRepository logs, MeterRegistry meters, CustomerQueryApi customers,
            PetQueryApi pets, PersonalService personal) {
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
        this.customers = customers;
        this.pets = pets;
        this.personal = personal;
    }

    /** {@code POST /public/ai/chat} (ai-v1 #1): khách vãng lai, không có tên thú. */
    public ChatResponse ask(ChatRequest request) {
        return run(request, () -> Viewer.GUEST);
    }

    /** {@code POST /me/ai/chat} (ai-v1 #2): khách lấy từ tài khoản của token, không bao giờ từ câu hỏi (BR-AI-27). */
    public ChatResponse askAsCustomer(Long accountId, ChatRequest request) {
        return run(request, () -> viewerOf(accountId));
    }

    /**
     * Khách và các thú còn sống được tra sau khi kiểm đầu vào (request sai là 400, không tra gì) và trước cấp cứu: câu
     * bệnh án cần tên thú, và nhật ký cần tên thú để che. Tài khoản chưa có hồ sơ khách (BR-TK-19) coi như không có thú
     * và không có lịch (ADR-0132).
     */
    private Viewer viewerOf(Long accountId) {
        Long customerId = customers.findCustomerIdByAccountId(accountId).orElse(null);
        return new Viewer(true, customerId, customerId == null ? List.of() : pets.findLivingPetsOfCustomer(customerId));
    }

    private ChatResponse run(ChatRequest request, Supplier<Viewer> viewerSource) {
        long started = System.nanoTime();
        String question = request.question() == null ? "" : request.question().strip();
        List<String> previous = request.previousQuestions() == null ? List.of() : request.previousQuestions();
        validate(question, previous);
        Viewer viewer = viewerSource.get();
        List<String> petNames = viewer.pets().stream().map(PetQueryApi.LivingPet::name).toList();

        String masked = PiiMasker.mask(question);
        List<String> maskedPrevious = previous.stream().map(text -> PiiMasker.mask(text.strip())).toList();
        // Bản gửi nhà cung cấp và bản ghi nhật ký đã che cả tên thú; định tuyến dưới đây vẫn dùng bản chưa che tên thú.
        String withoutPetNames = maskPetNames(masked, petNames);
        Trace trace = new Trace(withoutPetNames, started);

        if (emergency.isEmergency(masked)) {
            return finish(emergencyResponse(), trace, true);
        }
        if (router.isMedicalRecordQuestion(masked, petNames)) {
            return finish(ChatResponse.canned(ChatResult.MEDICAL_REFUSAL, ChatTexts.MEDICAL_RECORD, false), trace, true);
        }
        Optional<PersonalIntent> intent = router.detect(masked, petNames);
        if (intent.isPresent()) {
            // Câu cá nhân không ghi nội dung vào nhật ký (BR-AI-24).
            return finish(viewer.signedIn() ? personalResponse(intent.get(), viewer, masked)
                    : ChatResponse.canned(ChatResult.NEED_LOGIN, ChatTexts.NEED_LOGIN, false), trace, false);
        }
        return retrieveAndAnswer(withoutPetNames, maskedPrevious.stream().map(text -> maskPetNames(text, petNames)).toList(),
                trace);
    }

    /** Chỉ đổi dạng chữ khi khách có thú: khách vãng lai giữ nguyên câu hỏi. */
    private String maskPetNames(String text, List<String> petNames) {
        return petNames.isEmpty() ? text : router.maskPetNames(text, petNames);
    }

    private ChatResponse personalResponse(PersonalIntent intent, Viewer viewer, String question) {
        List<String> petNames = viewer.pets().stream().map(PetQueryApi.LivingPet::name).toList();
        PersonalService.Reply reply = personal.answer(intent, viewer.customerId(), viewer.pets(),
                router.mentionedPetNames(question, petNames));
        return reply.result() == ChatResult.NEED_PET_CHOICE ? ChatResponse.needPetChoice(reply.answer(), reply.pets())
                : ChatResponse.personal(reply.answer(), reply.bookingHint());
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

    /** Người hỏi: khách vãng lai, hoặc khách đã đăng nhập (có thể chưa có hồ sơ khách: {@code customerId} null). */
    private record Viewer(boolean signedIn, Long customerId, List<PetQueryApi.LivingPet> pets) {
        static final Viewer GUEST = new Viewer(false, null, List.of());
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
