package com.petcare.module.auth.repository;

import com.petcare.module.auth.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    boolean existsByEmail(String email);

    /** RULE-01-10: phone là UNIQUE ở DB (uq_accounts_phone) nên phải pre-check như email. */
    boolean existsByPhone(String phone);

    Optional<Account> findByEmail(String email);

    /**
     * PESSIMISTIC_WRITE trên Account — dùng trong verifyOtp()/resendOtp() thay cho
     * findByEmail() thường. Account là 1 row ổn định (không bị insert/thay thế), nên khoá
     * vào nó serialize được trọn vẹn đoạn "đọc Otp hiện tại -> tạo Otp mới" của resendOtp(),
     * tránh race 2 request resend đồng thời cùng tạo ra 2 OTP active (RULE-01-04) — khoá
     * trên OtpRepository#findTopByEmailAndPurposeOrderByCreatedAtDesc chỉ khoá đúng row đang
     * tồn tại tại thời điểm query, không "theo kịp" row Otp mới mà request thắng cuộc vừa
     * tạo ra. Bắt buộc gọi Account-lock TRƯỚC Otp-lock ở CẢ verifyOtp() lẫn resendOtp() —
     * đảo ngược thứ tự này ở 1 trong 2 method sẽ gây deadlock khi chúng chạy đồng thời trên
     * cùng email (A giữ khoá Account chờ khoá Otp trong khi B giữ khoá Otp chờ khoá Account).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.email = :email")
    Optional<Account> findByEmailForUpdate(@Param("email") String email);
}
