package com.petcare.module.ai.llm;

import java.util.List;

/** Nhúng văn bản thành vector 768 chiều (docs/adr/0109 D2). Lỗi ném {@link AiProviderException}. */
public interface EmbeddingClient {

    /** Tên mô hình, ghi vào {@code ai_chunks.embedding_model} để phát hiện đoạn nhúng bằng mô hình cũ (07 §8). */
    String embeddingModel();

    /** Một câu hỏi, loại truy vấn ({@code RETRIEVAL_QUERY}). */
    float[] embedQuery(String text);

    /** Nhiều đoạn tài liệu trong một lệnh, loại tài liệu ({@code RETRIEVAL_DOCUMENT}); kết quả cùng thứ tự đầu vào. */
    List<float[]> embedDocuments(List<String> texts);
}
