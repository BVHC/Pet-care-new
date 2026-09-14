package com.petcare.module.auth.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RULE-01-08 (ExpireOTP, System) — mirror RefreshTokenCleanupJob (ADR-0003):
 * @Scheduled đơn giản, không distributed lock (an toàn với đúng 1 backend
 * instance hiện tại — docker-compose.yml). Không cần batch/bucket phức tạp
 * như refresh_tokens vì volume otps nhỏ hơn nhiều (mỗi email chỉ vài row).
 */
@Component
@ConditionalOnProperty(prefix = "app.otp-expiry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OtpExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(OtpExpiryJob.class);

    private final OtpExpiryService otpExpiryService;

    public OtpExpiryJob(OtpExpiryService otpExpiryService) {
        this.otpExpiryService = otpExpiryService;
    }

    @Scheduled(cron = "${app.otp-expiry.cron:0 */5 * * * *}", zone = "Asia/Ho_Chi_Minh")
    public void expireOutstandingOtps() {
        int updated = otpExpiryService.expireOutstandingOtps();
        if (updated > 0) {
            log.info("OtpExpiryJob: marked {} expired OTP(s) as used", updated);
        }
    }
}
