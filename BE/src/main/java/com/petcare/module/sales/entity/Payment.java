package com.petcare.module.sales.entity;

import java.time.Instant;

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

/** Bảng {@code payments} (erd §8, ROOT): một phiếu thu có thể gộp nhiều Order cùng khách, cùng chi nhánh (BR-TG-03). */
@Getter
@Entity
@Table(name = "payments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "cashier_shift_id", nullable = false, updatable = false)
    private Long cashierShiftId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, updatable = false)
    private PaymentMethod method;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "received_by", nullable = false, updatable = false)
    private Long receivedBy;

    @Column(name = "paid_at", nullable = false, updatable = false)
    private Instant paidAt;

    public static Payment record(String code, Long branchId, Long customerId, Long cashierShiftId,
            PaymentMethod method, long amount, Long receivedBy, Instant paidAt) {
        Payment payment = new Payment();
        payment.code = code;
        payment.branchId = branchId;
        payment.customerId = customerId;
        payment.cashierShiftId = cashierShiftId;
        payment.method = method;
        payment.amount = amount;
        payment.receivedBy = receivedBy;
        payment.paidAt = paidAt;
        return payment;
    }
}
