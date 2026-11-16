package com.petcare.module.payment.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.payment} (task 127, ADR-0137 D4, docs/payment-gateway-setup.md §3): cấu hình cổng thanh toán qua biến môi
 * trường. Không phải [CFG]: khóa bí mật không nằm trong CSDL, log hay audit (BR-TG-07). BE khởi động được khi thiếu khóa
 * (mặc định {@code FAKE}); {@code VNPAY} thiếu mã website / khóa thì lỗi ngay lúc dựng bean.
 *
 * @param gateway      {@code VNPAY} hoặc {@code FAKE} (chọn bean {@code PaymentGatewayClient}), biến {@code PAYMENT_GATEWAY}
 * @param merchantCode {@code vnp_TmnCode}, biến {@code PAYMENT_MERCHANT_CODE}
 * @param secret       {@code vnp_HashSecret}, biến {@code PAYMENT_SECRET}; không bao giờ in ra
 * @param baseUrl      URL tạo thanh toán; trống thì VNPay dùng sandbox, Fake dùng trang giả lập của FE
 * @param returnUrl    trang FE nhận khách quay về ({@code vnp_ReturnUrl}), biến {@code PAYMENT_RETURN_URL}
 * @param ipnUrl       địa chỉ công khai của callback, chỉ để đăng ký với cổng (không gửi trong yêu cầu), {@code PAYMENT_IPN_URL}
 * @param apiUrl       API merchant (querydr, refund); trống thì VNPay dùng sandbox; chỉ đổi trong test
 */
@ConfigurationProperties("app.payment")
public record PaymentProperties(String gateway, String merchantCode, String secret, String baseUrl, String returnUrl,
        String ipnUrl, String apiUrl) {

    /** Không bao giờ in khóa: record tự sinh {@code toString} sẽ in mọi trường. */
    @Override
    public String toString() {
        return "PaymentProperties[gateway=" + gateway + ", merchantCode=" + merchantCode + ", secret="
                + (secret == null || secret.isBlank() ? "<unset>" : "<masked>") + ", baseUrl=" + baseUrl
                + ", returnUrl=" + returnUrl + ", ipnUrl=" + ipnUrl + ", apiUrl=" + apiUrl + "]";
    }
}
