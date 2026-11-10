package com.petcare.module.ai.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.ai.indexing} (docs/adr/0130): tham số vận hành của ST22, không phải [CFG] (07 §4.6 chỉ liệt kê tham số
 * nghiệp vụ). Sai giá trị thì app dừng lúc khởi động.
 *
 * @param enabled      {@code false} tắt lượt nạp sau commit và lượt quét khi khởi động (profile test)
 * @param batchSize    số đoạn nhúng trong một lệnh gọi nhà cung cấp (07 §5.3 bước 3: lô nhỏ)
 * @param batchGap     nghỉ giữa hai lô để khỏi vượt hạn mức gói free
 * @param maxAttempts  số lần thử một lô khi nhà cung cấp trả 429 (BR-AI-10 cấm thử lại ở chat, không ở ST22)
 * @param retryBackoff chờ giữa hai lần thử
 */
@Validated
@ConfigurationProperties("app.ai.indexing")
public record AiIndexingProperties(
        boolean enabled,
        @Positive int batchSize,
        @NotNull Duration batchGap,
        @Positive int maxAttempts,
        @NotNull Duration retryBackoff) {
}
