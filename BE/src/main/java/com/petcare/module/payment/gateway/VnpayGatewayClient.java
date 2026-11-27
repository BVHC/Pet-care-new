package com.petcare.module.payment.gateway;

import java.net.http.HttpClient;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.module.payment.api.PaymentGatewayClient;
import com.petcare.module.payment.api.PaymentGatewayException;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link PaymentGatewayClient} nói chuyện với VNPay (task 127, BR-TG-11), chọn bằng {@code PAYMENT_GATEWAY=VNPAY}.
 * Tạo địa chỉ thanh toán ký HMAC-SHA512 ({@link VnpayProtocol}), xác thực callback, truy vấn ({@code querydr}) qua API
 * merchant bằng {@link RestClient} (5 s nối, 10 s đọc; mẫu {@code GeminiClient}). Khóa chỉ
 * dùng để ký, không vào log hay thông báo lỗi.
 * <p>
 * Giả định VNPay (task 127) về API merchant, chưa kiểm với tài khoản sandbox (tài liệu: sandbox.vnpayment.vn/apis):
 * <ul>
 *   <li>Địa chỉ thanh toán: {@code vnp_Version=2.1.0}, {@code vnp_Command=pay}, {@code vnp_CurrCode=VND},
 *       {@code vnp_OrderType=other}, {@code vnp_Locale=vn}, {@code vnp_OrderInfo} chỉ ASCII; {@code vnp_IpnUrl} không gửi
 *       (IPN đăng ký ở trang quản trị merchant).</li>
 *   <li>{@code querydr}: POST JSON tới {@code merchant_webapi/api/transaction}; trường chữ ký
 *       {@code vnp_SecureHash} = HMAC-SHA512 của các trường nối bằng {@code |} theo đúng thứ tự dưới đây (trường vắng là
 *       chuỗi rỗng). Yêu cầu {@code querydr}: RequestId, Version, Command, TmnCode, TxnRef, TransactionDate, CreateDate,
 *       IpAddr, OrderInfo. Phản hồi {@code querydr}: ResponseId,
 *       Command, ResponseCode, Message, TmnCode, TxnRef, Amount, BankCode, PayDate, TransactionNo, TransactionType,
 *       TransactionStatus, OrderInfo, PromotionCode, PromotionAmount.</li>
 *   <li>{@code vnp_TransactionDate} = {@code vnp_CreateDate} của lệnh thanh toán gốc, nên truy vấn cần
 *       {@code txnCreatedAt}. {@code vnp_RequestId} của {@code querydr} là UUID.</li>
 *   <li>{@code querydr}: {@code vnp_ResponseCode=00} thì đọc {@code vnp_TransactionStatus} (00 thành công, 01 chưa có kết
 *       quả, còn lại thất bại); {@code 91} là không thấy giao dịch; mã khác là lỗi cổng.</li>
 *   <li>Không có API báo cáo đối soát: {@link #fetchSettlementReport} không hỗ trợ (task 135 quyết định cách đối soát).</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.payment", name = "gateway", havingValue = "VNPAY")
public class VnpayGatewayClient implements PaymentGatewayClient {

    static final String SANDBOX_PAY_URL = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    static final String SANDBOX_API_URL = "https://sandbox.vnpayment.vn/merchant_webapi/api/transaction";
    private static final String VERSION = "2.1.0";
    private static final String DEFAULT_IP = "127.0.0.1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PaymentProperties props;
    private final Clock clock;
    private final RestClient http;

    public VnpayGatewayClient(PaymentProperties props, Clock clock) {
        if (isBlank(props.merchantCode()) || isBlank(props.secret())) {
            throw new IllegalStateException("PAYMENT_GATEWAY=VNPAY cần PAYMENT_MERCHANT_CODE và PAYMENT_SECRET");
        }
        if (isBlank(props.returnUrl())) {
            throw new IllegalStateException("PAYMENT_GATEWAY=VNPAY cần PAYMENT_RETURN_URL");
        }
        this.props = props;
        this.clock = clock;
        // HTTP/1.1 như GeminiClient: tránh nâng cấp h2c trên máy chủ giả của test.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public PaymentSession createSession(SessionRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_Version", VERSION);
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", props.merchantCode());
        params.put("vnp_Amount", String.valueOf(Math.multiplyExact(request.amount(), 100L)));
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", request.txnCode());
        params.put("vnp_OrderInfo", ascii(request.orderInfo()));
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", props.returnUrl());
        params.put("vnp_IpAddr", orDefault(request.clientIp(), DEFAULT_IP));
        params.put("vnp_CreateDate", VnpayProtocol.formatDate(Instant.now(clock)));
        params.put("vnp_ExpireDate", VnpayProtocol.formatDate(request.expiresAt()));
        String base = isBlank(props.baseUrl()) ? SANDBOX_PAY_URL : props.baseUrl();
        return new PaymentSession(VnpayProtocol.signedUrl(base, params, props.secret()));
    }

    @Override
    public Optional<GatewayTransaction> verifyCallback(Map<String, String> parameters) {
        return VnpayProtocol.parseCallback(parameters, props.secret());
    }

    @Override
    public Optional<GatewayTransaction> queryTransaction(String txnCode) {
        // Giả định VNPay (task 127): thiếu thời điểm tạo giao dịch thì dùng "bây giờ"; bên gọi nên dùng bản hai tham số.
        return queryTransaction(txnCode, null);
    }

    @Override
    public Optional<GatewayTransaction> queryTransaction(String txnCode, Instant txnCreatedAt) {
        Instant now = Instant.now(clock);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("vnp_RequestId", UUID.randomUUID().toString().replace("-", ""));
        body.put("vnp_Version", VERSION);
        body.put("vnp_Command", "querydr");
        body.put("vnp_TmnCode", props.merchantCode());
        body.put("vnp_TxnRef", txnCode);
        body.put("vnp_OrderInfo", "Truy van giao dich " + txnCode);
        body.put("vnp_TransactionDate", VnpayProtocol.formatDate(txnCreatedAt == null ? now : txnCreatedAt));
        body.put("vnp_CreateDate", VnpayProtocol.formatDate(now));
        body.put("vnp_IpAddr", DEFAULT_IP);
        body.put("vnp_SecureHash", pipeHash(body, List.of("vnp_RequestId", "vnp_Version", "vnp_Command", "vnp_TmnCode",
                "vnp_TxnRef", "vnp_TransactionDate", "vnp_CreateDate", "vnp_IpAddr", "vnp_OrderInfo")));

        Map<String, String> response = post(body);
        verifyResponse(response, List.of("vnp_ResponseId", "vnp_Command", "vnp_ResponseCode", "vnp_Message",
                "vnp_TmnCode", "vnp_TxnRef", "vnp_Amount", "vnp_BankCode", "vnp_PayDate", "vnp_TransactionNo",
                "vnp_TransactionType", "vnp_TransactionStatus", "vnp_OrderInfo", "vnp_PromotionCode",
                "vnp_PromotionAmount"));
        String code = response.get("vnp_ResponseCode");
        if ("91".equals(code)) {
            return Optional.empty();
        }
        if (!VnpayProtocol.SUCCESS_CODE.equals(code)) {
            throw new PaymentGatewayException("VNPay querydr trả mã " + code);
        }
        String amount = response.get("vnp_Amount");
        if (amount == null || !amount.matches("\\d{1,18}") || Long.parseLong(amount) % 100 != 0) {
            throw new PaymentGatewayException("VNPay querydr trả số tiền không hợp lệ");
        }
        return Optional.of(new GatewayTransaction(txnCode, emptyToNull(response.get("vnp_TransactionNo")),
                Long.parseLong(amount) / 100,
                VnpayProtocol.status(code, response.get("vnp_TransactionStatus"))));
    }

    @Override
    public List<GatewayTransaction> fetchSettlementReport(LocalDate date) {
        // Giả định VNPay (task 127): sandbox không có API báo cáo đối soát theo ngày; ST14 (task 135) tự chọn cách đối soát.
        throw new UnsupportedOperationException("VNPay không có API báo cáo đối soát: ST14 (task 135) quyết định cách làm");
    }

    // ---------------------------------------------------------------- HTTP

    private Map<String, String> post(Map<String, String> body) {
        try {
            String raw = http.post().uri(isBlank(props.apiUrl()) ? SANDBOX_API_URL : props.apiUrl())
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
            JsonNode node = JSON.readTree(raw == null ? "" : raw);
            if (!node.isObject()) {
                throw new PaymentGatewayException("VNPay trả phản hồi không phải JSON object");
            }
            Map<String, String> out = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
            return out;
        } catch (RestClientException e) {
            // không đưa body / URL có thể chứa dữ liệu vào thông báo
            throw new PaymentGatewayException("Không gọi được API merchant của VNPay: " + e.getClass().getSimpleName(), e);
        } catch (java.io.IOException e) {
            throw new PaymentGatewayException("VNPay trả phản hồi không đọc được", e);
        }
    }

    private String pipeHash(Map<String, String> fields, List<String> order) {
        return VnpayProtocol.hmacSha512(props.secret(), joinPipe(fields, order));
    }

    private void verifyResponse(Map<String, String> response, List<String> order) {
        String given = response.get(VnpayProtocol.SECURE_HASH);
        String expected = pipeHash(response, order);
        if (given == null || !java.security.MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                given.trim().toLowerCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new PaymentGatewayException("Chữ ký phản hồi của VNPay không hợp lệ");
        }
    }

    static String joinPipe(Map<String, String> fields, List<String> order) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < order.size(); i++) {
            if (i > 0) {
                out.append('|');
            }
            String value = fields.get(order.get(i));
            out.append(value == null ? "" : value);
        }
        return out.toString();
    }

    /** {@code vnp_OrderInfo} chỉ ASCII: bỏ dấu, {@code đ} thành {@code d}, ký tự lạ thành khoảng trắng. */
    static String ascii(String text) {
        if (text == null) {
            return "";
        }
        String plain = Normalizer.normalize(text.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return plain.replaceAll("[^A-Za-z0-9 .,:_-]", " ").replaceAll(" +", " ").trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String orDefault(String s, String fallback) {
        return isBlank(s) ? fallback : s;
    }

    private static String emptyToNull(String s) {
        return isBlank(s) ? null : s;
    }
}
