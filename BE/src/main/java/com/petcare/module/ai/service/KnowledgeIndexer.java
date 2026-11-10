package com.petcare.module.ai.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import com.petcare.module.ai.entity.AiDocument;
import com.petcare.module.ai.entity.ChatErrorKind;
import com.petcare.module.ai.entity.DocumentStatus;
import com.petcare.module.ai.llm.AiProviderException;
import com.petcare.module.ai.llm.EmbeddingClient;
import com.petcare.module.ai.repository.AiDocumentRepository;
import com.petcare.module.ai.service.DocumentChunker.Chunk;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * ST22 — nạp chỉ mục tri thức (07 §5.3, BR-AI-15, BR-AI-16, docs/adr/0130). Không {@code @Transactional}: đọc tài liệu,
 * chia đoạn và nhúng đều chạy <b>không có transaction và không giữ kết nối</b>; chỉ bước hoán đổi mới mở transaction và
 * khóa dòng ({@link KnowledgeIndexWriter#swap}). Nhúng theo lô nhỏ, giãn nhịp, và thử lại khi nhà cung cấp trả 429 (chat
 * không được thử lại, BR-AI-10; ST22 được, 07 §5.3 bước 3).
 *
 * <p>{@link #requestIndex} xếp hàng cho <b>một luồng nền riêng</b>, nên cuộc gọi mạng không chiếm thread của scheduler
 * (4 luồng, ST20 cần luồng trống) và hai lượt nạp không chạy song song trên cùng tài liệu. Độ bền không nằm ở hàng đợi
 * trong bộ nhớ mà ở dòng {@code PENDING} / {@code FAILED} trong CSDL: lượt quét khi khởi động và định kỳ nhặt lại.
 */
@Slf4j
@Service
@EnableConfigurationProperties(AiIndexingProperties.class)
public class KnowledgeIndexer {

    public enum Result {
        /** Đã hoán đổi đoạn, {@code index_status = INDEXED}. */
        INDEXED,
        /** Không còn việc: không tồn tại hoặc không {@code PUBLISHED}. */
        SKIPPED,
        /** Phiên bản hoặc trạng thái đổi giữa lúc nhúng: bỏ lượt, dòng giữ nguyên cho lượt sau. */
        STALE,
        /** Nhúng hoặc ghi lỗi: {@code index_status = FAILED}, đoạn cũ vẫn phục vụ. */
        FAILED
    }

    private final AiDocumentRepository docs;
    private final EmbeddingClient embeddings;
    private final KnowledgeIndexWriter writer;
    private final AiIndexingProperties props;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-index");
        thread.setDaemon(true);
        return thread;
    });
    /** Tài liệu đang chờ trong hàng đợi, để một tài liệu không xếp hàng hai lần. */
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();

    public KnowledgeIndexer(AiDocumentRepository docs, EmbeddingClient embeddings, KnowledgeIndexWriter writer,
            AiIndexingProperties props) {
        this.docs = docs;
        this.embeddings = embeddings;
        this.writer = writer;
        this.props = props;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Xếp hàng nạp một tài liệu cho luồng nền; không làm gì khi {@code app.ai.indexing.enabled = false}. */
    public void requestIndex(Long documentId) {
        if (!props.enabled() || !queued.add(documentId)) {
            return;
        }
        executor.execute(() -> {
            queued.remove(documentId);
            try {
                indexDocument(documentId);
            } catch (RuntimeException e) {
                log.error("KNOWLEDGE_INDEX_FAILED documentId={}", documentId, e);
            }
        });
    }

    /** Xếp hàng mọi tài liệu {@code PUBLISHED} chưa có chỉ mục khớp phiên bản (khởi động, định kỳ). */
    public void requestPending() {
        if (props.enabled()) {
            docs.findIdsNeedingIndex().forEach(this::requestIndex);
        }
    }

    /** Nạp đồng bộ một tài liệu; mọi lỗi nhúng và ghi đều thành {@link Result#FAILED} kèm lý do trong CSDL. */
    public Result indexDocument(Long documentId) {
        AiDocument doc = docs.findById(documentId).orElse(null);
        if (doc == null || doc.getStatus() != DocumentStatus.PUBLISHED) {
            return Result.SKIPPED;
        }
        int version = doc.getVersion();
        try {
            List<Chunk> pieces = DocumentChunker.chunk(doc.getTitle(), doc.getContent());
            if (pieces.isEmpty()) {
                throw new IllegalStateException("Nội dung không có đoạn nào để nạp");
            }
            List<float[]> vectors = embedAll(pieces);
            return writer.swap(documentId, version, pieces, vectors, embeddings.embeddingModel()) ? Result.INDEXED
                    : Result.STALE;
        } catch (RuntimeException e) {
            log.warn("KNOWLEDGE_INDEX_FAILED documentId={} version={} cause={}", documentId, version, e.toString());
            writer.fail(documentId, version, describe(e));
            return Result.FAILED;
        }
    }

    private List<float[]> embedAll(List<Chunk> pieces) {
        List<float[]> vectors = new ArrayList<>(pieces.size());
        for (int from = 0; from < pieces.size(); from += props.batchSize()) {
            if (from > 0) {
                pause(props.batchGap());
            }
            List<String> texts = pieces.subList(from, Math.min(from + props.batchSize(), pieces.size())).stream()
                    .map(Chunk::embedText).toList();
            vectors.addAll(embedWithRetry(texts));
        }
        return vectors;
    }

    private List<float[]> embedWithRetry(List<String> texts) {
        for (int attempt = 1;; attempt++) {
            try {
                return embeddings.embedDocuments(texts);
            } catch (AiProviderException e) {
                if (e.kind() != ChatErrorKind.PROVIDER_RATE_LIMIT || attempt >= props.maxAttempts()) {
                    throw e;
                }
                pause(props.retryBackoff());
            }
        }
    }

    private static String describe(RuntimeException e) {
        if (e instanceof AiProviderException provider) {
            return "[" + provider.kind() + "] " + provider.getMessage();
        }
        if (e instanceof IllegalStateException) {
            return e.getMessage();
        }
        return "Lỗi nội bộ khi nạp chỉ mục (" + e.getClass().getSimpleName() + ")";
    }

    private static void pause(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Bị ngắt khi chờ giữa hai lượt nhúng");
        }
    }
}
