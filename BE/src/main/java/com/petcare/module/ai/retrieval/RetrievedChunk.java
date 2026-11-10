package com.petcare.module.ai.retrieval;

/**
 * Một đoạn tri thức tìm được (07 §5.1): siêu dữ liệu để dựng khối tài liệu cho LLM và danh sách nguồn trả khách.
 * {@code similarity} là độ tương đồng cosine ({@code 1 - (embedding <=> query)}); {@code heading} có thể {@code null}.
 */
public record RetrievedChunk(long documentId, String title, String species, String heading, String text,
        double similarity) {
}
