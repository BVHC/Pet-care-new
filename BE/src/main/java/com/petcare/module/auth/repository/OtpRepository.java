package com.petcare.module.auth.repository;

import com.petcare.module.auth.entity.Otp;
import com.petcare.platform.enums.OtpPurpose;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface OtpRepository extends JpaRepository<Otp, UUID> {

    /**
     * PESSIMISTIC_WRITE — bắt buộc dùng trong verifyOtp()/resendOtp() để
     * serialize các request đồng thời cùng (email, purpose), tránh lost-update
     * trên attempt_count/is_used/locked_until khi người dùng bấm nhiều lần
     * (xem plan §5.2 Concurrency).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Otp> findTopByEmailAndPurposeOrderByCreatedAtDesc(String email, OtpPurpose purpose);

    /** RULE-01-05 — đếm trực tiếp số OTP do ResendOTP tạo (is_resend=true) trong cửa sổ, không tính OTP gốc từ RegisterAccount. */
    long countByEmailAndPurposeAndResendTrueAndCreatedAtAfter(String email, OtpPurpose purpose, LocalDateTime after);

    /** RULE-01-08 (ExpireOTP job) — tái dùng is_used làm "đã hết hiệu lực" (bảng otps không có cột status riêng). */
    @Modifying
    @Query("UPDATE Otp o SET o.used = true WHERE o.used = false AND o.expiresAt < :now")
    int markExpiredOtpsAsUsed(LocalDateTime now);
}
