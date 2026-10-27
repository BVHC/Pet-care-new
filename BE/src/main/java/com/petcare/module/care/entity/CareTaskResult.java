package com.petcare.module.care.entity;

/**
 * {@code care_tasks.result} (erd §0): {@code LIÊN_HỆ_ĐƯỢC} → {@code REACHED}, {@code KHÔNG_LIÊN_LẠC_ĐƯỢC} →
 * {@code UNREACHABLE}. Có giá trị khi và chỉ khi task {@code DONE} (CHECK {@code ck_care_tasks_done_result}).
 */
public enum CareTaskResult {
    REACHED, UNREACHABLE
}
