package com.petcare.module.ai.retrieval;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.petcare.module.ai.service.VectorLiteral;

/**
 * Truy hồi pgvector (07 §5.1, BR-AI-04, BR-AI-16): K đoạn gần nhất của tài liệu {@code PUBLISHED} ở đúng phiên bản đã
 * nạp. Lọc trạng thái và phiên bản <b>ngay trong SQL</b>, nên lưu trữ hoặc hoán đổi phiên bản có hiệu lực ngay khi commit
 * mà không phải nạp lại bộ nhớ, và đúng khi chạy nhiều bản BE. Ngưỡng τ giữ ở tầng ứng dụng ({@code ChatService}).
 * <p>
 * Dùng {@link JdbcTemplate}: câu truy hồi chỉ đọc, không cần entity (cột {@code embedding} không ánh xạ vào JPA,
 * docs/adr/0130 D1), và không giữ kết nối ngoài lúc chạy câu lệnh (BR-AI-10: kết nối không được treo qua lệnh gọi LLM).
 */
@Component
public class ChunkRetriever {

    private static final String NEAREST = """
            SELECT c.document_id, d.title, d.species, c.heading, c.text,
                   1 - (c.embedding <=> CAST(? AS vector)) AS similarity
            FROM ai_chunks c JOIN ai_documents d ON d.id = c.document_id
            WHERE d.status = 'PUBLISHED' AND c.doc_version = d.indexed_version
            ORDER BY c.embedding <=> CAST(? AS vector), c.id
            LIMIT ?""";

    private final JdbcTemplate jdbc;

    public ChunkRetriever(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tối đa {@code k} đoạn, độ tương đồng giảm dần. */
    public List<RetrievedChunk> nearest(float[] query, int k) {
        String vector = VectorLiteral.of(query);
        return jdbc.query(NEAREST, (rs, row) -> new RetrievedChunk(rs.getLong("document_id"), rs.getString("title"),
                rs.getString("species"), rs.getString("heading"), rs.getString("text"), rs.getDouble("similarity")),
                vector, vector, k);
    }
}
