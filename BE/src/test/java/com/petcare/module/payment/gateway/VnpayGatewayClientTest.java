package com.petcare.module.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.module.payment.api.PaymentGatewayClient.GatewayStatus;
import com.petcare.module.payment.api.PaymentGatewayClient.SessionRequest;
import com.petcare.module.payment.api.PaymentGatewayException;
import com.sun.net.httpserver.HttpServer;

/**
 * {@link VnpayGatewayClient} (task 127) không gọi sandbox: địa chỉ thanh toán được dựng và ký đúng, còn querydr
 * chạy trên một máy chủ HTTP giả cục bộ kiểm yêu cầu (trường, chữ ký) và dựng phản hồi có / không chữ ký hợp lệ.
 */
class VnpayGatewayClientTest {

    private static final String SECRET = "SECRETKEY123";
    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<Map<String, String>> requests = new ArrayList<>();
    private final AtomicReference<Map<String, String>> reply = new AtomicReference<>();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private VnpayGatewayClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/transaction", exchange -> {
            @SuppressWarnings("unchecked")
            Map<String, String> body = JSON.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
            synchronized (requests) {
                requests.add(body);
            }
            byte[] out = JSON.writeValueAsBytes(reply.get() == null ? Map.of() : reply.get());
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        client = new VnpayGatewayClient(new PaymentProperties("VNPAY", "TESTCODE", SECRET, null,
                "http://localhost:5173/checkout/result", null,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/transaction"),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static Map<String, String> query(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        Arrays.stream(URI.create(url).getRawQuery().split("&")).forEach(kv -> {
            String[] p = kv.split("=", 2);
            out.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), URLDecoder.decode(p[1], StandardCharsets.UTF_8));
        });
        return out;
    }

    private static String pipeSign(Map<String, String> fields, List<String> order) {
        return VnpayProtocol.hmacSha512(SECRET, VnpayGatewayClient.joinPipe(fields, order));
    }

    private static final List<String> QUERY_RESPONSE_ORDER = List.of("vnp_ResponseId", "vnp_Command", "vnp_ResponseCode",
            "vnp_Message", "vnp_TmnCode", "vnp_TxnRef", "vnp_Amount", "vnp_BankCode", "vnp_PayDate", "vnp_TransactionNo",
            "vnp_TransactionType", "vnp_TransactionStatus", "vnp_OrderInfo", "vnp_PromotionCode", "vnp_PromotionAmount");

    private Map<String, String> signedReply(String command, List<String> order, String code, String txnStatus,
            String amount) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("vnp_ResponseId", "resp-1");
        r.put("vnp_Command", command);
        r.put("vnp_ResponseCode", code);
        r.put("vnp_Message", "msg");
        r.put("vnp_TmnCode", "TESTCODE");
        r.put("vnp_TxnRef", "GD-2611-00001");
        r.put("vnp_Amount", amount);
        r.put("vnp_TransactionNo", "14123456");
        r.put("vnp_TransactionType", "01");
        r.put("vnp_TransactionStatus", txnStatus);
        r.put("vnp_SecureHash", pipeSign(r, order));
        return r;
    }

    // ---------------------------------------------------------------- createSession

    @Test
    void createSessionBuildsASignedVnpayUrlWithAmountTimes100AndGmtPlus7Dates() {
        String url = client.createSession(new SessionRequest("GD-2611-00001", 150_000L,
                "Thanh toán đơn hàng DH-2611-00001", Instant.parse("2026-11-17T03:15:00Z"), "203.0.113.7")).redirectUrl();

        assertThat(url).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
        Map<String, String> p = query(url);
        assertThat(p).containsEntry("vnp_Version", "2.1.0").containsEntry("vnp_Command", "pay")
                .containsEntry("vnp_TmnCode", "TESTCODE").containsEntry("vnp_Amount", "15000000")
                .containsEntry("vnp_CurrCode", "VND").containsEntry("vnp_TxnRef", "GD-2611-00001")
                .containsEntry("vnp_OrderInfo", "Thanh toan don hang DH-2611-00001")
                .containsEntry("vnp_OrderType", "other").containsEntry("vnp_Locale", "vn")
                .containsEntry("vnp_ReturnUrl", "http://localhost:5173/checkout/result")
                .containsEntry("vnp_IpAddr", "203.0.113.7").containsEntry("vnp_CreateDate", "20261117100000")
                .containsEntry("vnp_ExpireDate", "20261117101500");
        assertThat(VnpayProtocol.verify(p, SECRET)).isTrue();
        assertThat(VnpayProtocol.verify(p, "OTHERKEY")).isFalse();
        assertThat(url).doesNotContain(SECRET);
    }

    @Test
    void createSessionFallsBackToLoopbackIpWhenClientIpIsMissing() {
        String url = client.createSession(new SessionRequest("GD-1", 1_000L, "x", NOW.plusSeconds(60), null))
                .redirectUrl();

        assertThat(query(url)).containsEntry("vnp_IpAddr", "127.0.0.1");
    }

    @Test
    void verifyCallbackAcceptsOnlyGenuineSignatures() {
        Map<String, String> cb = new LinkedHashMap<>();
        cb.put("vnp_TxnRef", "GD-2611-00001");
        cb.put("vnp_Amount", "15000000");
        cb.put("vnp_ResponseCode", "00");
        cb.put("vnp_TransactionStatus", "00");
        cb.put("vnp_TransactionNo", "14123456");
        cb.put("vnp_SecureHash", VnpayProtocol.sign(cb, SECRET));

        assertThat(client.verifyCallback(cb)).hasValueSatisfying(t -> {
            assertThat(t.amount()).isEqualTo(150_000L);
            assertThat(t.status()).isEqualTo(GatewayStatus.SUCCEEDED);
        });
        cb.put("vnp_ResponseCode", "24");
        assertThat(client.verifyCallback(cb)).isEmpty();
    }

    @Test
    void vnpayHasNoSettlementReportApi() {
        assertThatThrownBy(() -> client.fetchSettlementReport(java.time.LocalDate.parse("2026-11-16")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void orderInfoIsReducedToAscii() {
        assertThat(VnpayGatewayClient.ascii("Thanh toán đơn hàng #DH-1 (Hạt)")).isEqualTo("Thanh toan don hang DH-1 Hat");
        assertThat(VnpayGatewayClient.ascii(null)).isEmpty();
    }

    // ---------------------------------------------------------------- querydr

    @Test
    void queryTransactionSendsASignedQuerydrAndMapsSuccess() {
        reply.set(signedReply("querydr", QUERY_RESPONSE_ORDER, "00", "00", "15000000"));

        var txn = client.queryTransaction("GD-2611-00001", Instant.parse("2026-11-17T02:50:00Z")).orElseThrow();

        assertThat(txn.status()).isEqualTo(GatewayStatus.SUCCEEDED);
        assertThat(txn.amount()).isEqualTo(150_000L);
        assertThat(txn.gatewayTxnId()).isEqualTo("14123456");
        Map<String, String> sent = requests.get(0);
        assertThat(sent).containsEntry("vnp_Command", "querydr").containsEntry("vnp_TmnCode", "TESTCODE")
                .containsEntry("vnp_TxnRef", "GD-2611-00001").containsEntry("vnp_Version", "2.1.0")
                .containsEntry("vnp_TransactionDate", "20261117095000").containsEntry("vnp_CreateDate", "20261117100000");
        assertThat(sent.get("vnp_RequestId")).hasSize(32);
        assertThat(sent.get("vnp_SecureHash")).isEqualTo(pipeSign(sent, List.of("vnp_RequestId", "vnp_Version",
                "vnp_Command", "vnp_TmnCode", "vnp_TxnRef", "vnp_TransactionDate", "vnp_CreateDate", "vnp_IpAddr",
                "vnp_OrderInfo")));
        assertThat(sent.values()).doesNotContain(SECRET);
    }

    @Test
    void queryTransactionMapsPendingFailedAndNotFound() {
        reply.set(signedReply("querydr", QUERY_RESPONSE_ORDER, "00", "01", "15000000"));
        assertThat(client.queryTransaction("GD-2611-00001", NOW).orElseThrow().status()).isEqualTo(GatewayStatus.PENDING);

        reply.set(signedReply("querydr", QUERY_RESPONSE_ORDER, "00", "02", "15000000"));
        assertThat(client.queryTransaction("GD-2611-00001", NOW).orElseThrow().status()).isEqualTo(GatewayStatus.FAILED);

        reply.set(signedReply("querydr", QUERY_RESPONSE_ORDER, "91", null, "0"));
        assertThat(client.queryTransaction("GD-2611-00001", NOW)).as("91 = không thấy giao dịch").isEmpty();
    }

    @Test
    void queryTransactionOneArgFallsBackToNowForTheTransactionDate() {
        reply.set(signedReply("querydr", QUERY_RESPONSE_ORDER, "00", "00", "15000000"));

        client.queryTransaction("GD-2611-00001");

        assertThat(requests.get(0)).containsEntry("vnp_TransactionDate", "20261117100000");
    }

    @Test
    void queryTransactionFailsLoudlyOnABadResponseSignatureAnErrorCodeOrHttpFailure() {
        Map<String, String> forged = signedReply("querydr", QUERY_RESPONSE_ORDER, "00", "00", "15000000");
        forged.put("vnp_ResponseCode", "00");
        forged.put("vnp_Message", "tampered");
        reply.set(forged);
        assertThatThrownBy(() -> client.queryTransaction("GD-1", NOW)).isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("Chữ ký");

        reply.set(signedReply("querydr", QUERY_RESPONSE_ORDER, "99", null, "0"));
        assertThatThrownBy(() -> client.queryTransaction("GD-1", NOW)).isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("99");

        status.set(500);
        assertThatThrownBy(() -> client.queryTransaction("GD-1", NOW)).isInstanceOf(PaymentGatewayException.class)
                .hasMessageNotContaining(SECRET);
    }
}
