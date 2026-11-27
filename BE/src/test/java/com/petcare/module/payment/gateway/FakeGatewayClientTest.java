package com.petcare.module.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.petcare.module.payment.api.PaymentGatewayClient.GatewayStatus;
import com.petcare.module.payment.api.PaymentGatewayClient.GatewayTransaction;
import com.petcare.module.payment.api.PaymentGatewayClient.SessionRequest;

/** {@link FakeGatewayClient} (task 127): xác định, không mạng, cùng thuật toán ký với VNPay, trạng thái trong bộ nhớ. */
class FakeGatewayClientTest {

    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private static final PaymentProperties PROPS = new PaymentProperties("FAKE", "TESTMERCHANT", "fake-secret", null,
            "http://localhost:5173/checkout/result", null, null);
    private final FakeGatewayClient fake = new FakeGatewayClient(PROPS, Clock.fixed(NOW, ZoneOffset.UTC));

    private SessionRequest session() {
        return new SessionRequest("GD-2611-00001", 150_000L, "Thanh toan don DH-2611-00001",
                Instant.parse("2026-11-17T03:15:00Z"), "127.0.0.1");
    }

    private static Map<String, String> query(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        Arrays.stream(URI.create(url).getRawQuery().split("&")).forEach(kv -> {
            String[] p = kv.split("=", 2);
            out.put(java.net.URLDecoder.decode(p[0], java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(p[1], java.nio.charset.StandardCharsets.UTF_8));
        });
        return out;
    }

    @Test
    void createSessionIsDeterministicAndSignedWithTheConfiguredSecret() {
        String url = fake.createSession(session()).redirectUrl();

        assertThat(fake.createSession(session()).redirectUrl()).isEqualTo(url);
        assertThat(url).startsWith("http://localhost:5173/payment/mock-gateway?code=GD-2611-00001&amount=150000&");
        Map<String, String> params = query(url);
        assertThat(params).containsEntry("code", "GD-2611-00001").containsEntry("amount", "150000");
        params.keySet().removeIf(k -> !k.startsWith("vnp_")); // code / amount nằm ngoài chữ ký
        assertThat(params).containsEntry("vnp_Amount", "15000000").containsEntry("vnp_TxnRef", "GD-2611-00001")
                .containsEntry("vnp_ExpireDate", "20261117101500");
        assertThat(VnpayProtocol.verify(params, "fake-secret")).isTrue();
        assertThat(VnpayProtocol.verify(params, "other")).isFalse();
    }

    @Test
    void createSessionUsesTheConfiguredBaseUrl() {
        FakeGatewayClient custom = new FakeGatewayClient(new PaymentProperties("FAKE", "M", "s", "http://gw.test/pay",
                null, null, null), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(custom.createSession(session()).redirectUrl()).startsWith("http://gw.test/pay?code=GD-2611-00001");
    }

    @Test
    void signedCallbackVerifiesAndMapsEachStatus() {
        var ok = fake.verifyCallback(fake.signedCallback("GD-1", 150_000L, GatewayStatus.SUCCEEDED, "N1")).orElseThrow();
        assertThat(ok).isEqualTo(new GatewayTransaction("GD-1", "N1", 150_000L, GatewayStatus.SUCCEEDED));
        assertThat(fake.verifyCallback(fake.signedCallback("GD-1", 150_000L, GatewayStatus.FAILED, "N1")).orElseThrow()
                .status()).isEqualTo(GatewayStatus.FAILED);
        assertThat(fake.verifyCallback(fake.signedCallback("GD-1", 150_000L, GatewayStatus.PENDING, "N1")).orElseThrow()
                .status()).isEqualTo(GatewayStatus.PENDING);
    }

    @Test
    void tamperedOrUnsignedCallbackIsRejected() {
        Map<String, String> tampered = fake.signedCallback("GD-1", 150_000L, GatewayStatus.FAILED, "N1");
        tampered.put("vnp_ResponseCode", "00");
        tampered.put("vnp_TransactionStatus", "00");
        assertThat(fake.verifyCallback(tampered)).isEmpty();

        Map<String, String> unsigned = fake.signedCallback("GD-1", 150_000L, GatewayStatus.SUCCEEDED, "N1");
        unsigned.remove("vnp_SecureHash");
        assertThat(fake.verifyCallback(unsigned)).isEmpty();
    }

    @Test
    void worksWithoutAnyConfiguredSecretOrMerchantCode() {
        FakeGatewayClient bare = new FakeGatewayClient(new PaymentProperties("FAKE", null, "", null, null, null, null),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(bare.createSession(session()).redirectUrl()).contains("vnp_TmnCode=FAKEMERCHANT");
        assertThat(bare.verifyCallback(bare.signedCallback("GD-1", 1_000L, GatewayStatus.SUCCEEDED, "N")))
                .isPresent();
    }

    @Test
    void queryAndSettlementAnswerFromMemoryOnly() {
        assertThat(fake.queryTransaction("GD-9")).isEmpty();
        assertThat(fake.queryTransaction("GD-9", NOW)).as("bản hai tham số mặc định chuyển về bản một").isEmpty();
        GatewayTransaction txn = new GatewayTransaction("GD-9", "N9", 50_000L, GatewayStatus.SUCCEEDED);
        fake.setTransaction(txn);
        fake.recordSettlement(LocalDate.parse("2026-11-16"), txn);

        assertThat(fake.queryTransaction("GD-9")).contains(txn);
        assertThat(fake.queryTransaction("GD-9", NOW)).contains(txn);
        assertThat(fake.fetchSettlementReport(LocalDate.parse("2026-11-16"))).containsExactly(txn);
        assertThat(fake.fetchSettlementReport(LocalDate.parse("2026-11-17"))).isEmpty();

        fake.reset();
        assertThat(fake.queryTransaction("GD-9")).isEmpty();
    }
}
