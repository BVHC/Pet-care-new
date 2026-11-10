package com.petcare.module.ai.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.ai} (docs/adr/0109 D4, 0130): cấu hình nhà cung cấp AI qua biến môi trường. Không phải [CFG]: khóa API không
 * nằm trong CSDL (BR-AI-25) và tên mô hình đổi theo vòng đời mô hình (docs/adr/0122).
 *
 * @param provider       {@code gemini}; {@code off} tắt mọi lệnh gọi (UC90 trả {@code UNAVAILABLE}, 07 §5.5)
 * @param apiKey         khóa từ {@code GEMINI_API_KEY}; trống thì mọi lệnh gọi lỗi {@code NO_KEY}, BE vẫn khởi động
 * @param baseUrl        gốc URL kèm {@code /models/}; chỉ đổi trong test
 * @param llmModel       mặc định {@code gemini-3.1-flash-lite} (ADR-0122)
 * @param embeddingModel mặc định {@code gemini-embedding-001}
 * @param embeddingDim   cố định 768 theo cột {@code vector(768)}; đổi số chiều cần migration (BR-AI-15)
 */
@ConfigurationProperties("app.ai")
public record AiProperties(String provider, String apiKey, String baseUrl, String llmModel, String embeddingModel,
        int embeddingDim) {

    /** Không bao giờ in khóa: record tự sinh {@code toString} sẽ in mọi trường. */
    @Override
    public String toString() {
        return "AiProperties[provider=" + provider + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<unset>" : "<masked>")
                + ", baseUrl=" + baseUrl + ", llmModel=" + llmModel + ", embeddingModel=" + embeddingModel
                + ", embeddingDim=" + embeddingDim + "]";
    }
}
