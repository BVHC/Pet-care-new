package com.petcare.module.notification.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Gateway dự phòng cho máy dev: không gửi SMTP mà in thẳng nội dung email
 * (gồm mã OTP) ra log, nên chạy end-to-end được mà không cần App Password Gmail.
 *
 * <p>Chỉ bật khi spring.mail.username để trống — có cấu hình SMTP thì
 * {@link GmailSmtpEmailGateway} giành quyền. TUYỆT ĐỐI không dùng ở production.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${spring.mail.username:}'.isBlank()")
public class LogEmailGateway implements EmailGateway {

    public static final String PROVIDER_NAME = "LOG_ONLY";

    @Override
    public String send(String toEmail, String subject, String body) {
        log.warn("DEV EMAIL (khong gui that) -> {} | {} | {}", toEmail, subject, body);
        return null;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }
}
