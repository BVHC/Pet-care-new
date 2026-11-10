package com.petcare.module.ai;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

import com.petcare.module.ai.service.VectorLiteral;

/**
 * Dữ liệu và truy vấn SQL dùng chung cho IT của module ai. Tài liệu của mỗi IT mang tiền tố tiêu đề riêng ({@code tag})
 * để {@link #cleanUp()} chỉ xóa dữ liệu của chính nó; tài liệu {@code CATALOG_PROTOCOL} không có tiền tố nên IT chạy ST22
 * phải gọi {@link #deleteCatalogDocuments()} (khóa ngoại tới {@code vaccine_types} chặn IT khác xóa loại vaccine).
 */
public final class AiItSupport {

    /** Câu truy hồi của 07 §5.1, thêm lọc tiêu đề để tách khỏi dữ liệu của IT khác. */
    private static final String RETRIEVAL = """
            SELECT c.document_id, c.doc_version, c.ordinal, 1 - (c.embedding <=> CAST(? AS vector)) AS similarity
            FROM ai_chunks c JOIN ai_documents d ON d.id = c.document_id
            WHERE d.status = 'PUBLISHED' AND c.doc_version = d.indexed_version AND d.title LIKE ?
            ORDER BY c.embedding <=> CAST(? AS vector)
            LIMIT 50""";

    private final JdbcTemplate jdbc;
    private final String tag;

    public AiItSupport(JdbcTemplate jdbc, String tag) {
        this.jdbc = jdbc;
        this.tag = tag;
    }

    public String title(String suffix) {
        return tag + " " + suffix;
    }

    /** Nội dung gồm {@code sections} mục, mỗi mục khoảng 350 ký tự nên mỗi mục thành đúng một đoạn. */
    public static String content(int sections) {
        StringBuilder text = new StringBuilder();
        for (int s = 1; s <= sections; s++) {
            text.append("## Mục ").append(s).append('\n');
            StringBuilder body = new StringBuilder();
            for (int i = 0; body.length() < 350; i++) {
                body.append("câu").append(s).append('_').append(i).append(' ');
            }
            text.append(body.toString().trim()).append("\n\n");
        }
        return text.toString().trim();
    }

    /** Tài liệu {@code MANUAL} đã đủ hai lớp kiểm soát (BR-AI-14), trạng thái và chỉ mục tùy ý. */
    public long document(String suffix, String content, String status, int version, int indexedVersion,
            String indexStatus) {
        return jdbc.queryForObject("""
                INSERT INTO ai_documents (source_type, title, topic, species, content, source_note, reviewer_name,
                        reviewed_on, status, version, indexed_version, index_status)
                VALUES ('MANUAL', ?, 'CARE', 'ALL', ?, 'Nguồn thử (IT)', 'BS. Thử nghiệm (IT)', DATE '2026-11-05', ?, ?,
                        ?, ?) RETURNING id""", Long.class, title(suffix), content, status, version, indexedVersion,
                indexStatus);
    }

    /** Một đoạn có vector tất định của {@code text}, để dựng tình huống "đã nạp phiên bản N". */
    public void chunk(long documentId, int docVersion, int ordinal, String text) {
        jdbc.update("""
                INSERT INTO ai_chunks (document_id, doc_version, ordinal, heading, text, embedding, embedding_model)
                VALUES (?, ?, ?, NULL, ?, CAST(? AS vector), 'cũ')""", documentId, docVersion, ordinal, text,
                VectorLiteral.of(FakeEmbeddingClient.vectorOf(text)));
    }

    public Map<String, Object> row(long documentId) {
        return jdbc.queryForMap("SELECT * FROM ai_documents WHERE id = ?", documentId);
    }

    public List<Map<String, Object>> chunks(long documentId) {
        return jdbc.queryForList("""
                SELECT doc_version, ordinal, heading, text, embedding_model FROM ai_chunks
                WHERE document_id = ? ORDER BY doc_version, ordinal""", documentId);
    }

    public int chunkCount(long documentId) {
        return jdbc.queryForObject("SELECT count(*) FROM ai_chunks WHERE document_id = ?", Integer.class, documentId);
    }

    /** Truy hồi như BR-AI-04 (không lọc ngưỡng) trong các tài liệu của IT này. */
    public List<Map<String, Object>> retrieve(float[] query) {
        String literal = VectorLiteral.of(query);
        return jdbc.queryForList(RETRIEVAL, literal, tag + "%", literal);
    }

    public void cleanUp() {
        jdbc.update("DELETE FROM ai_chunks WHERE document_id IN (SELECT id FROM ai_documents WHERE title LIKE ?)",
                tag + "%");
        jdbc.update("DELETE FROM ai_documents WHERE title LIKE ?", tag + "%");
    }

    public void deleteCatalogDocuments() {
        jdbc.update("DELETE FROM ai_chunks WHERE document_id IN (SELECT id FROM ai_documents WHERE vaccine_type_id IS NOT NULL)");
        jdbc.update("DELETE FROM ai_documents WHERE vaccine_type_id IS NOT NULL");
    }
}
