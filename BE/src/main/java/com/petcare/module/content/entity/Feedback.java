package com.petcare.module.content.entity;

import com.petcare.platform.model.TimestampedEntity;

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
 * Bảng {@code feedbacks} (erd §10, ROOT): ý kiến không công khai của khách, không sửa, không xóa, không có trạng thái
 * (BR-DG-02; docs/adr/0122: bỏ BR-DG-04). Giữ 30 ngày rồi ST24 xóa cứng (BR-DG-05).
 */
@Getter
@Entity
@Table(name = "feedbacks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Feedback extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "topic", nullable = false, updatable = false)
    private FeedbackTopic topic;

    @Column(name = "branch_id", updatable = false)
    private Long branchId;

    @Column(name = "rating", updatable = false)
    private Short rating;

    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    public Feedback(Long customerId, FeedbackTopic topic, Long branchId, Short rating, String content) {
        this.customerId = customerId;
        this.topic = topic;
        this.branchId = branchId;
        this.rating = rating;
        this.content = content;
    }
}
