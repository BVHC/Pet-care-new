package com.petcare.module.ai.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.ai.entity.AiDocument;
import com.petcare.module.ai.entity.DocumentStatus;
import com.petcare.module.ai.repository.AiChunkRepository;
import com.petcare.module.ai.repository.AiDocumentRepository;
import com.petcare.module.ai.service.DocumentChunker.Chunk;

/**
 * Hai transaction ngắn của ST22, tách khỏi {@link KnowledgeIndexer} để việc gọi nhà cung cấp (chậm, ngoài transaction)
 * không bao giờ nằm trong khóa dòng (docs/adr/0130). Mỗi method là một transaction riêng; không gọi nhau.
 */
@Service
public class KnowledgeIndexWriter {

    /** {@code ai_documents.index_error VARCHAR(300)}. */
    static final int ERROR_MAX = 300;

    private final AiDocumentRepository docs;
    private final AiChunkRepository chunks;
    private final Clock clock;

    public KnowledgeIndexWriter(AiDocumentRepository docs, AiChunkRepository chunks, Clock clock) {
        this.docs = docs;
        this.chunks = chunks;
        this.clock = clock;
    }

    /**
     * Hoán đổi đoạn (BR-AI-16, 07 §5.3 bước 4) trong <b>một</b> transaction: khóa dòng tài liệu, kiểm lại dưới khóa rằng
     * nó vẫn {@code PUBLISHED} và vẫn đúng phiên bản vừa nhúng (nếu không, bỏ lượt này và trả {@code false}, dòng giữ
     * nguyên để lượt sau nạp phiên bản mới), xóa <b>mọi</b> đoạn cũ, ghi đoạn mới, rồi đặt {@code indexed_version} bằng
     * một câu UPDATE có điều kiện. Bất kỳ lỗi nào ở giữa làm rollback cả: đoạn cũ vẫn phục vụ.
     */
    @Transactional
    public boolean swap(Long documentId, int version, List<Chunk> pieces, List<float[]> vectors, String model) {
        AiDocument doc = docs.findByIdForUpdate(documentId).orElse(null);
        if (doc == null || doc.getStatus() != DocumentStatus.PUBLISHED || doc.getVersion() != version) {
            return false;
        }
        chunks.deleteAllOf(documentId);
        Instant now = Instant.now(clock);
        for (int i = 0; i < pieces.size(); i++) {
            Chunk piece = pieces.get(i);
            chunks.insert(documentId, version, piece.ordinal(), piece.heading(), piece.text(),
                    VectorLiteral.of(vectors.get(i)), model, now);
        }
        if (docs.markIndexed(documentId, version) != 1) {
            throw new IllegalStateException("Không đặt được chỉ mục của tài liệu " + documentId + " dưới khóa");
        }
        return true;
    }

    /**
     * Nạp lỗi (BR-AI-16): chỉ đặt {@code FAILED} và lý do (cắt còn 300 ký tự), không đụng {@code ai_chunks} hay
     * {@code indexed_version}, nên đoạn cũ vẫn phục vụ. Bỏ qua nếu dòng đã sang phiên bản khác hoặc không còn
     * {@code PUBLISHED}.
     */
    @Transactional
    public void fail(Long documentId, int version, String message) {
        String error = message == null ? "Lỗi không rõ" : message;
        docs.markFailed(documentId, version, error.length() <= ERROR_MAX ? error : error.substring(0, ERROR_MAX));
    }
}
