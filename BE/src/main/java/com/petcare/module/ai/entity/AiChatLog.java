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
 * kênh. Chỉ thêm, nên entity bất biến; ST23 xóa quá hạn bằng câu native ở {@code AiChatLogRepository}. Ghi bởi {@code ChatService}
 * qua {@link #of} (docs/adr/0131); schema cũng được kiểm khi khởi động.
 */
@Getter
@Immutable
@Entity
@Table(name = "ai_chat_logs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiChatLog extends CreatedAtEntity {

    /** Độ rộng cột {@code question}; [CFG] {@code ai.chat.max_question_chars} cho tới 2000 nên nhật ký cắt bớt. */
    public static final int QUESTION_MAX_LENGTH = 500;

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

    /**
     * Một dòng nhật ký (BR-AI-24). {@code question} và {@code answer} phải là bản <b>đã che</b> (BR-AI-12); các kết quả
     * cá nhân và {@code NEED_LOGIN} truyền {@code null} cho mọi trường nội dung. Câu hỏi quá {@value #QUESTION_MAX_LENGTH}
     * ký tự bị cắt cho vừa cột.
     */
    public static AiChatLog of(ChatResult result, ChatErrorKind errorKind, String question, String answer,
            BigDecimal topSimilarity, Long[] sourceDocumentIds, String llmModel, Integer inputTokens,
            Integer outputTokens, Integer thoughtTokens, int latencyMs) {
        AiChatLog log = new AiChatLog();
        log.result = result;
        log.errorKind = errorKind;
        log.question = question == null || question.length() <= QUESTION_MAX_LENGTH ? question
                : question.substring(0, QUESTION_MAX_LENGTH);
        log.answer = answer;
        log.topSimilarity = topSimilarity;
        log.sourceDocumentIds = sourceDocumentIds;
        log.llmModel = llmModel;
        log.inputTokens = inputTokens;
        log.outputTokens = outputTokens;
        log.thoughtTokens = thoughtTokens;
        log.latencyMs = latencyMs;
        return log;
    }
}
