package com.petcare.module.notification.gateway;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Impl thật duy nhất của EmailGateway hiện tại — gửi qua Gmail SMTP
 * (spring.mail.*, xem application.yml). gateway_provider ghi vào
 * notification_delivery_logs = "GMAIL_SMTP" (docs/06-erd.md §3.7 chỉ liệt kê
 * TWILIO/FCM/SENDGRID làm ví dụ, không giới hạn cứng danh sách).
 */
@Component
@RequiredArgsConstructor
public class GmailSmtpEmailGateway implements EmailGateway {

    public static final String PROVIDER_NAME = "GMAIL_SMTP";

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromAddress;

    @Override
    public String send(String toEmail, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
        // JavaMailSender (SMTP) không trả provider message-id như API-based gateway (SendGrid...).
        return null;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }
}
