package com.petcare.module.payment.api;

/**
 * Cổng không trả lời được hoặc trả lời sai (hết giờ, HTTP lỗi, chữ ký phản hồi sai, mã lỗi lạ) khi truy vấn giao dịch.
 * Khác {@link java.util.Optional#empty()} (cổng nói rõ không biết giao dịch): bên gọi (ST11) giữ giao dịch
 * {@code PENDING} và thử lại lần quét sau, không đoán kết quả.
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
