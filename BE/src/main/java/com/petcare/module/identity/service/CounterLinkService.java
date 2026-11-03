package com.petcare.module.identity.service;

import java.util.Locale;

import org.springframework.stereotype.Service;

import com.petcare.module.identity.dto.CounterLinkRequest;
import com.petcare.module.identity.dto.LinkResult;

/**
 * UC22 — lễ tân liên kết HỘ tài khoản online với hồ sơ tại quầy sau khi đối chiếu CCCD (identity-v1 #27; BR-TK-16, 19).
 * Cố ý <b>không</b> {@code @Transactional} (khuôn {@link LinkProfileService}, {@link CustomerAccountService}): không có
 * BCrypt ở đây, nhưng bước đọc hồ sơ phải kết thúc transaction của nó trước khi bước ghi bắt đầu — chi tiết ở
 * {@link CounterLinkStepService}. Đặt {@code @Transactional} lên method này sẽ gộp hai bước vào một transaction và làm
 * hỏng lần "kiểm lại dưới khóa" (ca {@code counterProfileLinkedByAnotherAccountBetweenReadAndLockIsRejectedUnderLock}
 * của {@code CustomerAccountLinkIT}). Không được gọi từ trong một transaction khác.
 */
@Service
public class CounterLinkService {

    private final CounterLinkStepService steps;

    public CounterLinkService(CounterLinkStepService steps) {
        this.steps = steps;
    }

    /** identity-v1 #27: đọc hồ sơ (404 / BR-TK-19) → chuẩn hóa email tài khoản → khóa, kiểm lại, gắn, audit. */
    public LinkResult linkAccount(Long customerId, CounterLinkRequest request) {
        String counterEmail = steps.findCounterEmail(customerId);
        String accountEmail = request.accountEmail().strip().toLowerCase(Locale.ROOT);
        return steps.applyLink(customerId, accountEmail, counterEmail);
    }
}
