package com.petcare.module.auth.entity;

import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.LockReason;
import com.petcare.platform.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Aggregate Root Module 01 (Auth & OTP) — docs/05-domain-model.md §4.1,
 * docs/03-state-machines.md FSM 1, docs/06-erd.md bảng accounts.
 * `email` là danh tính đăng nhập chính (RULE-01-10, sửa 2026-09-13); `phone`
 * là liên hệ tuỳ chọn.
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
public class Account extends BaseEntity {

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "email", nullable = false, length = 100)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private AccountStatus status = AccountStatus.PENDING_VERIFICATION;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "lock_reason", length = 30)
    private LockReason lockReason;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    public Account(String email, String phone, String passwordHash) {
        this.email = email;
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.status = AccountStatus.PENDING_VERIFICATION;
    }
}
