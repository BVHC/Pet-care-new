package com.petcare.module.visit.dto;

import java.time.LocalDate;

import com.petcare.module.visit.entity.VisitStatus;

/**
 * Bộ lọc của hàng đợi chi nhánh (visit-v1 #2, A1; docs/adr/0060). Mọi trường có thể null = không gửi. Không có
 * {@code branchId}: chi nhánh luôn là của người gọi (00-method §3.3).
 * <ul>
 * <li>Không gửi {@code date}: lượt {@code WAITING} / {@code IN_PROGRESS} của mọi ngày; {@code status} là
 * {@code COMPLETED} / {@code CANCELLED} thì chỉ lượt check-in hôm nay.</li>
 * <li>Không gửi {@code status}: chỉ {@code WAITING} + {@code IN_PROGRESS}, trừ khi gửi {@code date} (khi đó mọi trạng
 * thái của ngày đó).</li>
 * <li>{@code serviceGroup}, {@code assigneeId}, {@code needsReassign}: AND khi được gửi.</li>
 * </ul>
 */
public record VisitQueueFilter(LocalDate date, VisitStatus status, QueueServiceGroup serviceGroup, Long assigneeId,
        Boolean needsReassign) {
}
