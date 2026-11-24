package com.petcare.module.payment.gateway;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.petcare.module.payment.api.PaymentGatewayClient.GatewayStatus;
import com.petcare.module.payment.api.PaymentGatewayClient.GatewayTransaction;
import com.petcare.platform.config.TimeConfig;

/**
 * Giao thức ký của VNPay, dùng chung cho {@link VnpayGatewayClient} và {@link FakeGatewayClient} (task 127, BR-TG-07,
 * BR-TG-11). Hàm thuần, không đọc cấu hình: khóa truyền vào.
 * <p>
 * Giả định VNPay (task 127), theo tài liệu sandbox công khai và docs/payment-gateway-setup.md §5 — chưa đối chiếu với
 * tài khoản sandbox thật:
 * <ul>
 *   <li>Chuỗi ký = các tham số (bỏ giá trị rỗng, bỏ {@code vnp_SecureHash} / {@code vnp_SecureHashType}) sắp theo tên tăng
 *       dần, mỗi cặp {@code tên=giá-trị} với tên và giá trị mã hóa URL (US-ASCII, khoảng trắng thành {@code +}), nối bằng
 *       {@code &}; chữ ký = HMAC-SHA512 hex (chữ thường) của chuỗi đó với {@code vnp_HashSecret}.</li>
 *   <li>{@code vnp_Amount} = số tiền đồng x 100; ngày giờ {@code yyyyMMddHHmmss} múi giờ GMT+7.</li>
 *   <li>Callback / IPN: thành công khi {@code vnp_ResponseCode = 00} và {@code vnp_TransactionStatus = 00} (hoặc vắng);
 *       {@code vnp_TransactionStatus = 01} là cổng chưa có kết quả; còn lại là thất bại.</li>
 * </ul>
 */
public final class VnpayProtocol {

    public static final String SECURE_HASH = "vnp_SecureHash";
    public static final String SECURE_HASH_TYPE = "vnp_SecureHashType";
    public static final String SUCCESS_CODE = "00";

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(TimeConfig.BUSINESS_ZONE);

    private VnpayProtocol() {
    }

    /** {@code yyyyMMddHHmmss} theo giờ Việt Nam. */
    public static String formatDate(Instant instant) {
        return DATE_TIME.format(instant);
    }

    /** Chuỗi dùng để ký và làm query: tham số đã sắp, loại giá trị rỗng và hai trường chữ ký, mã hóa URL. */
    public static String query(Map<String, String> params) {
        StringBuilder out = new StringBuilder();
        new TreeMap<>(params).forEach((name, value) -> {
            if (value == null || value.isEmpty() || SECURE_HASH.equals(name) || SECURE_HASH_TYPE.equals(name)) {
                return;
            }
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(URLEncoder.encode(name, StandardCharsets.US_ASCII)).append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.US_ASCII));
        });
        return out.toString();
    }

    /** HMAC-SHA512 hex chữ thường của {@code data}. */
    public static String hmacSha512(String secret, String data) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalStateException("Thiếu khóa bí mật của cổng thanh toán (PAYMENT_SECRET)");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Không ký được HMAC-SHA512", e);
        }
    }

    /** Chữ ký của một tập tham số {@code vnp_*}. */
    public static String sign(Map<String, String> params, String secret) {
        return hmacSha512(secret, query(params));
    }

    /** {@code base?query&vnp_SecureHash=…}: địa chỉ chuyển hướng khách sang cổng. */
    public static String signedUrl(String baseUrl, Map<String, String> params, String secret) {
        return baseUrl + (baseUrl.contains("?") ? "&" : "?") + query(params) + "&" + SECURE_HASH + "="
                + sign(params, secret);
    }

    /** Chữ ký có mặt và đúng (so sánh thời gian cố định, không phân biệt hoa thường của hex). */
    public static boolean verify(Map<String, String> params, String secret) {
        String given = params.get(SECURE_HASH);
        if (given == null || given.isBlank()) {
            return false;
        }
        byte[] expected = sign(params, secret).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = given.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * Đọc callback / IPN: rỗng khi thiếu chữ ký, chữ ký sai, thiếu mã tham chiếu hoặc số tiền không phải bội của 100
     * (không thể khớp số tiền đồng của hệ thống).
     */
    public static Optional<GatewayTransaction> parseCallback(Map<String, String> params, String secret) {
        if (params == null || !verify(params, secret)) {
            return Optional.empty();
        }
        String txnRef = params.get("vnp_TxnRef");
        String amountText = params.get("vnp_Amount");
        if (txnRef == null || txnRef.isBlank() || amountText == null || !amountText.matches("\\d{1,18}")) {
            return Optional.empty();
        }
        long raw = Long.parseLong(amountText);
        if (raw % 100 != 0) {
            return Optional.empty();
        }
        return Optional.of(new GatewayTransaction(txnRef, blankToNull(params.get("vnp_TransactionNo")), raw / 100,
                status(params.get("vnp_ResponseCode"), params.get("vnp_TransactionStatus"))));
    }

    /** Ánh xạ mã trả về của cổng sang {@link GatewayStatus}. */
    public static GatewayStatus status(String responseCode, String transactionStatus) {
        if ("01".equals(transactionStatus)) {
            return GatewayStatus.PENDING;
        }
        boolean ok = SUCCESS_CODE.equals(responseCode) && (transactionStatus == null || transactionStatus.isBlank()
                || SUCCESS_CODE.equals(transactionStatus));
        return ok ? GatewayStatus.SUCCEEDED : GatewayStatus.FAILED;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
