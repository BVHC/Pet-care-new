package com.petcare.module.notification.gateway;

/**
 * Cổng gửi email thật cho kênh NotificationChannel.EMAIL. Interface tách khỏi
 * NotificationServiceImpl để implementation gateway (Gmail SMTP hiện tại) có
 * thể thay bằng provider khác (SendGrid...) mà không sửa NotificationService.
 */
public interface EmailGateway {

    /** @return message id do provider trả về (có thể null nếu provider không cấp) */
    String send(String toEmail, String subject, String body);

    String providerName();
}
