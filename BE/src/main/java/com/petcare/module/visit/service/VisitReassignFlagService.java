package com.petcare.module.visit.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.visit.repository.VisitRepository;

/**
 * Hệ quả trên visit của khóa tài khoản (03 Tài khoản#7 "Visit {@code IN_PROGRESS} đánh dấu cần gán lại", BR-QT-11,
 * BR-TN-08, 06 §3; trả nợ D017 — docs/adr/0062). Chỉ gọi từ {@code visit/listener/AccountLockedListener} trong
 * transaction khóa của identity: {@code MANDATORY}, không bắt lỗi — lỗi ở đây rollback cả lần khóa (convention 07
 * §7.2).
 * <p>
 * Không kiểm lại guard của khóa (identity đã kiểm dưới khóa {@code accounts}) và không lọc theo role: đổi chức vụ được
 * phép khi còn lượt được gán (docs/adr/0031 Q4), nên người bị khóa có thể đang giữ lượt dù không còn là VET /
 * CARETAKER. Lượt {@code WAITING} không đánh dấu (lễ tân gán lại tự do, Visit#2). Mở khóa không gỡ cờ (docs/adr/0062
 * Q5, nợ D042).
 */
@Service
public class VisitReassignFlagService {

    private final VisitRepository visits;
    private final Clock clock;

    public VisitReassignFlagService(VisitRepository visits, Clock clock) {
        this.visits = visits;
        this.clock = clock;
    }

    /** Số lượt vừa được đánh dấu; lượt đã đánh dấu từ lần khóa trước không tính lại. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int flagCalledVisitsOf(Long accountId) {
        Objects.requireNonNull(accountId, "accountId");
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return visits.flagNeedsReassign(accountId, now);
    }
}
