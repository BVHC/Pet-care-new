package com.petcare.module.ai.entity;

import java.math.BigDecimal;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.petcare.platform.model.CreatedAtEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng {@code ai_chat_logs} (07 §8, LOG): một dòng cho mỗi lượt hỏi đáp UC90 (BR-AI-24). Không có cột tài khoản, IP hay
 * kênh. Chỉ thêm, nên entity bất biến; ST23 xóa quá hạn bằng câu native ở {@code AiChatLogRepository}. Chưa có nơi ghi
 * (chat là việc của T77b); entity có ở đây để schema được kiểm khi khởi động.
 */
@Getter
@Immutable
@Entity
@Table(name = "ai_chat_logs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiChatLog extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 30)
    private ChatResult result;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_kind", length = 30)
    private ChatErrorKind errorKind;

    @Column(name = "question", length = 500)
    private String question;

    @Column(name = "answer")
    private String answer;

    @Column(name = "top_similarity", precision = 4, scale = 3)
    private BigDecimal topSimilarity;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "source_document_ids", columnDefinition = "bigint[]")
    private Long[] sourceDocumentIds;

    @Column(name = "llm_model", length = 60)
    private String llmModel;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "thought_tokens")
    private Integer thoughtTokens;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;
}
