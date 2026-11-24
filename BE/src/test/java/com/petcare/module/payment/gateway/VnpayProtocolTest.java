package com.petcare.module.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.petcare.module.payment.api.PaymentGatewayClient.GatewayStatus;

/**
 * Chữ ký VNPay (task 127, BR-TG-07): vector đã biết (HMAC-SHA512 tính độc lập bằng Python {@code hmac}), sửa tham số /
 * chữ ký thì không hợp lệ, callback đọc đúng số tiền ({@code vnp_Amount} / 100) và trạng thái.
 */
class VnpayProtocolTest {

    private static final String SECRET = "SECRETKEY123";
    private static final String KNOWN_QUERY = "vnp_Amount=15000000&vnp_Command=pay&vnp_CreateDate=20261117100000"
            + "&vnp_CurrCode=VND&vnp_ExpireDate=20261117101500&vnp_IpAddr=127.0.0.1&vnp_Locale=vn"
            + "&vnp_OrderInfo=Thanh+toan+don+DH-2611-00001&vnp_OrderType=other"
            + "&vnp_ReturnUrl=http%3A%2F%2Flocalhost%3A5173%2Fcheckout%2Fresult&vnp_TmnCode=TESTCODE"
            + "&vnp_TxnRef=GD-2611-00001&vnp_Version=2.1.0";
    private static final String KNOWN_HASH = "55cd5edbbeda7fe444b05bb5b7da73d4452cd62ff5fbd0a69e179e42c6d4d691"
            + "9009fc024dcacd4e4abd2aacbf13ae605b267c2b06b5f8fb90422f820c22dc97";

    private static Map<String, String> payParams() {
        Map<String, String> p = new LinkedHashMap<>(); // thứ tự chèn cố ý lộn xộn: query phải tự sắp
        p.put("vnp_TxnRef", "GD-2611-00001");
        p.put("vnp_Version", "2.1.0");
        p.put("vnp_Command", "pay");
        p.put("vnp_TmnCode", "TESTCODE");
        p.put("vnp_Amount", "15000000");
        p.put("vnp_CurrCode", "VND");
        p.put("vnp_OrderInfo", "Thanh toan don DH-2611-00001");
        p.put("vnp_OrderType", "other");
        p.put("vnp_Locale", "vn");
        p.put("vnp_ReturnUrl", "http://localhost:5173/checkout/result");
        p.put("vnp_IpAddr", "127.0.0.1");
        p.put("vnp_CreateDate", "20261117100000");
        p.put("vnp_ExpireDate", "20261117101500");
        return p;
    }

    @Test
    void queryIsSortedUrlEncodedAndSkipsEmptyAndSignatureFields() {
        Map<String, String> p = payParams();
        p.put("vnp_Empty", "");
        p.put("vnp_Null", null);
        p.put("vnp_SecureHash", "abc");
        p.put("vnp_SecureHashType", "HmacSHA512");

        assertThat(VnpayProtocol.query(p)).isEqualTo(KNOWN_QUERY);
    }

    @Test
    void signMatchesTheIndependentlyComputedVector() {
        assertThat(VnpayProtocol.sign(payParams(), SECRET)).isEqualTo(KNOWN_HASH);
    }

    @Test
    void signedUrlEndsWithTheHashAndVerifies() {
        String url = VnpayProtocol.signedUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html", payParams(), SECRET);

        assertThat(url).isEqualTo("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?" + KNOWN_QUERY
                + "&vnp_SecureHash=" + KNOWN_HASH);
    }

    @Test
    void verifyAcceptsTheSignatureRegardlessOfHexCaseAndRejectsTampering() {
        Map<String, String> p = payParams();
        p.put("vnp_SecureHash", KNOWN_HASH.toUpperCase());
        assertThat(VnpayProtocol.verify(p, SECRET)).isTrue();

        Map<String, String> tampered = payParams();
        tampered.put("vnp_Amount", "100");
        tampered.put("vnp_SecureHash", KNOWN_HASH);
        assertThat(VnpayProtocol.verify(tampered, SECRET)).as("amount changed").isFalse();

        Map<String, String> wrongKey = payParams();
        wrongKey.put("vnp_SecureHash", KNOWN_HASH);
        assertThat(VnpayProtocol.verify(wrongKey, "OTHERKEY")).isFalse();
    }

    @Test
    void verifyRejectsMissingOrBlankSignature() {
        assertThat(VnpayProtocol.verify(payParams(), SECRET)).isFalse();
        Map<String, String> blank = payParams();
        blank.put("vnp_SecureHash", " ");
        assertThat(VnpayProtocol.verify(blank, SECRET)).isFalse();
    }

    @Test
    void signingWithoutASecretFailsLoudlyInsteadOfSigningWithAnEmptyKey() {
        assertThatThrownBy(() -> VnpayProtocol.sign(payParams(), "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> VnpayProtocol.sign(payParams(), null)).isInstanceOf(IllegalStateException.class);
    }

    private static Map<String, String> callback(String amount, String responseCode, String transactionStatus) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("vnp_TmnCode", "TESTCODE");
        p.put("vnp_TxnRef", "GD-2611-00001");
        p.put("vnp_Amount", amount);
        p.put("vnp_TransactionNo", "14123456");
        p.put("vnp_ResponseCode", responseCode);
        if (transactionStatus != null) {
            p.put("vnp_TransactionStatus", transactionStatus);
        }
        p.put("vnp_SecureHash", VnpayProtocol.sign(p, SECRET));
        return p;
    }

    @Test
    void parseCallbackReadsAmountInDongAndStatus() {
        var ok = VnpayProtocol.parseCallback(callback("15000000", "00", "00"), SECRET).orElseThrow();
        assertThat(ok.txnCode()).isEqualTo("GD-2611-00001");
        assertThat(ok.amount()).isEqualTo(150_000L);
        assertThat(ok.gatewayTxnId()).isEqualTo("14123456");
        assertThat(ok.status()).isEqualTo(GatewayStatus.SUCCEEDED);

        assertThat(VnpayProtocol.parseCallback(callback("15000000", "24", "02"), SECRET).orElseThrow().status())
                .isEqualTo(GatewayStatus.FAILED);
        assertThat(VnpayProtocol.parseCallback(callback("15000000", "00", "01"), SECRET).orElseThrow().status())
                .isEqualTo(GatewayStatus.PENDING);
        assertThat(VnpayProtocol.parseCallback(callback("15000000", "00", null), SECRET).orElseThrow().status())
                .as("không có vnp_TransactionStatus").isEqualTo(GatewayStatus.SUCCEEDED);
        assertThat(VnpayProtocol.parseCallback(callback("15000000", "07", "00"), SECRET).orElseThrow().status())
                .as("mã phản hồi lỗi dù trạng thái 00").isEqualTo(GatewayStatus.FAILED);
    }

    @Test
    void parseCallbackIsEmptyForBadSignatureMissingFieldsOrAmountsThatCannotBeWholeDong() {
        Map<String, String> tampered = callback("15000000", "00", "00");
        tampered.put("vnp_Amount", "100");
        assertThat(VnpayProtocol.parseCallback(tampered, SECRET)).as("amount changed after signing").isEmpty();

        assertThat(VnpayProtocol.parseCallback(callback("15000000", "00", "00"), "OTHERKEY")).isEmpty();

        Map<String, String> unsigned = callback("15000000", "00", "00");
        unsigned.remove("vnp_SecureHash");
        assertThat(VnpayProtocol.parseCallback(unsigned, SECRET)).isEmpty();
        assertThat(VnpayProtocol.parseCallback(null, SECRET)).isEmpty();

        assertThat(VnpayProtocol.parseCallback(callback("15000050", "00", "00"), SECRET)).as("không chia hết cho 100")
                .isEmpty();
        assertThat(VnpayProtocol.parseCallback(callback("abc", "00", "00"), SECRET)).isEmpty();

        Map<String, String> noRef = new LinkedHashMap<>();
        noRef.put("vnp_Amount", "15000000");
        noRef.put("vnp_ResponseCode", "00");
        noRef.put("vnp_SecureHash", VnpayProtocol.sign(noRef, SECRET));
        assertThat(VnpayProtocol.parseCallback(noRef, SECRET)).as("thiếu vnp_TxnRef").isEmpty();
    }

    @Test
    void formatDateUsesVietnamTime() {
        assertThat(VnpayProtocol.formatDate(Instant.parse("2026-11-17T03:00:00Z"))).isEqualTo("20261117100000");
        assertThat(VnpayProtocol.formatDate(Instant.parse("2026-11-17T18:00:00Z"))).isEqualTo("20261118010000");
    }
}
