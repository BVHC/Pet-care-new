package com.petcare.module.care.repository;

import java.time.Instant;

/** Số dòng email đến hạn chưa gửi của một luồng và {@code next_attempt_at} sớm nhất ({@code null} khi rỗng). */
public record LaneBacklog(long pending, Instant oldestDueAt) {
}
