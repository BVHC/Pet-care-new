package com.petcare.module.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.module.ai.entity.ChatErrorKind;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.sun.net.httpserver.HttpServer;

/**
 * Lớp Gemini (BR-AI-10, ADR-0109 D4) trên một máy chủ HTTP giả của JDK, không gọi mạng. Kiểm tham số lớp nhận được và
 * cách phân loại lỗi; câu trả lời của mô hình thật chỉ đo ở T77c. Khóa giả {@value #KEY} chỉ để chứng minh khóa không
 * rò vào URL, message lỗi hay {@code toString}.
 */
class GeminiClientTest {

    private static final String KEY = "TEST-KEY-NOT-REAL-123";
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile long delayMillis = 0;
    private SystemConfigApi config;

    record Recorded(String path, String query, String apiKeyHeader, JsonNode body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            requests.add(new Recorded(exchange.getRequestURI().getPath(), exchange.getRequestURI().getQuery(),
                    exchange.getRequestHeaders().getFirst("x-goog-api-key"),
                    raw.length == 0 ? null : JSON.readTree(raw)));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(out);
            } catch (IOException ignored) {
                // máy khách đã bỏ cuộc vì hết giờ
            }
        });
        server.start();
        config = mock(SystemConfigApi.class);
        when(config.getInt(ConfigKey.AI_LLM_TIMEOUT_SECONDS)).thenReturn(5);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AiProperties props(String provider, String key) {
        return new AiProperties(provider, key, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/models/",
                "gemini-3.1-flash-lite", "gemini-embedding-001", 768);
    }

    private GeminiClient client() {
        return new GeminiClient(props("gemini", KEY), config);
    }

    private static String vectorJson(int dims) {
        return "[" + String.join(",", java.util.Collections.nCopies(dims, "0.25")) + "]";
    }

    private static String generateJson(String text) {
        return """
                {"candidates":[{"content":{"parts":[{"text":%s}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":30,"thoughtsTokenCount":0}}"""
                .formatted(JSON.valueToTree(text));
    }

    // ---------------------------------------------------------------- nhúng

    @Test
    void embedQueryUsesQueryTaskType768DimensionsAndSendsTheKeyOnlyInTheHeader() {
        body = "{\"embedding\":{\"values\":" + vectorJson(768) + "}}";

        float[] vector = client().embedQuery("Chó con tiêm mấy mũi?");

        assertThat(vector).hasSize(768);
        Recorded request = requests.get(0);
        assertThat(request.path()).isEqualTo("/v1beta/models/gemini-embedding-001:embedContent");
        assertThat(request.apiKeyHeader()).isEqualTo(KEY);
        assertThat(request.query()).isNull();
        assertThat(request.body().path("taskType").asText()).isEqualTo("RETRIEVAL_QUERY");
        assertThat(request.body().path("outputDimensionality").asInt()).isEqualTo(768);
        assertThat(request.body().at("/content/parts/0/text").asText()).isEqualTo("Chó con tiêm mấy mũi?");
    }

    @Test
    void embedDocumentsSendsOneBatchWithDocumentTaskTypeAndKeepsTheOrder() {
        body = "{\"embeddings\":[{\"values\":" + vectorJson(768) + "},{\"values\":" + vectorJson(768) + "}]}";

        List<float[]> vectors = client().embedDocuments(List.of("đoạn một", "đoạn hai"));

        assertThat(vectors).hasSize(2);
        Recorded request = requests.get(0);
        assertThat(request.path()).isEqualTo("/v1beta/models/gemini-embedding-001:batchEmbedContents");
        JsonNode sent = request.body().path("requests");
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).path("taskType").asText()).isEqualTo("RETRIEVAL_DOCUMENT");
        assertThat(sent.get(0).path("model").asText()).isEqualTo("models/gemini-embedding-001");
        assertThat(sent.get(0).at("/content/parts/0/text").asText()).isEqualTo("đoạn một");
        assertThat(sent.get(1).at("/content/parts/0/text").asText()).isEqualTo("đoạn hai");
    }

    @Test
    void embeddingWithTheWrongNumberOfDimensionsIsAProviderError() {
        body = "{\"embedding\":{\"values\":" + vectorJson(3072) + "}}";

        assertThatThrownBy(() -> client().embedQuery("x")).isInstanceOfSatisfying(AiProviderException.class,
                e -> assertThat(e.kind()).isEqualTo(ChatErrorKind.PROVIDER_ERROR));
    }

    @Test
    void batchWithAMissingEmbeddingIsAProviderError() {
        body = "{\"embeddings\":[{\"values\":" + vectorJson(768) + "}]}";

        assertThatThrownBy(() -> client().embedDocuments(List.of("a", "b"))).isInstanceOfSatisfying(
                AiProviderException.class, e -> assertThat(e.kind()).isEqualTo(ChatErrorKind.PROVIDER_ERROR));
    }

    // ---------------------------------------------------------------- sinh

    @Test
    void generateSendsThePromptWithThinkingOffAndAJsonSchemaAndParsesTheAnswer() {
        body = generateJson("{\"inScope\":true,\"answer\":\"Mũi đầu từ 6 tuần tuổi.\"}");

        LlmAnswer answer = client().generate("HỆ THỐNG", "NGƯỜI DÙNG");

        assertThat(answer.inScope()).isTrue();
        assertThat(answer.answer()).isEqualTo("Mũi đầu từ 6 tuần tuổi.");
        assertThat(answer.inputTokens()).isEqualTo(120);
        assertThat(answer.outputTokens()).isEqualTo(30);
        assertThat(answer.thoughtTokens()).isZero();
        Recorded request = requests.get(0);
        assertThat(request.path()).isEqualTo("/v1beta/models/gemini-3.1-flash-lite:generateContent");
        assertThat(request.apiKeyHeader()).isEqualTo(KEY);
        assertThat(request.body().at("/systemInstruction/parts/0/text").asText()).isEqualTo("HỆ THỐNG");
        assertThat(request.body().at("/contents/0/role").asText()).isEqualTo("user");
        assertThat(request.body().at("/contents/0/parts/0/text").asText()).isEqualTo("NGƯỜI DÙNG");
        JsonNode generation = request.body().path("generationConfig");
        assertThat(generation.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(generation.path("maxOutputTokens").asInt()).isEqualTo(600);
        assertThat(generation.at("/thinkingConfig/thinkingBudget").asInt(-1)).isZero();
        assertThat(generation.path("responseMimeType").asText()).isEqualTo("application/json");
        assertThat(generation.at("/responseSchema/required")).hasSize(2);
    }

    @Test
    void answerThatIsNotTheExpectedJsonIsAProviderError() {
        body = generateJson("không phải JSON");

        assertThatThrownBy(() -> client().generate("s", "u")).isInstanceOfSatisfying(AiProviderException.class,
                e -> assertThat(e.kind()).isEqualTo(ChatErrorKind.PROVIDER_ERROR));
    }

    @Test
    void answerMissingARequiredFieldIsAProviderError() {
        body = generateJson("{\"inScope\":true}");

        assertThatThrownBy(() -> client().generate("s", "u")).isInstanceOfSatisfying(AiProviderException.class,
                e -> assertThat(e.kind()).isEqualTo(ChatErrorKind.PROVIDER_ERROR));
    }

    // ---------------------------------------------------------------- phân loại lỗi (BR-AI-10)

    @Test
    void http429IsAProviderRateLimit() {
        status = 429;
        body = "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\"}}";

        assertThatThrownBy(() -> client().embedQuery("x")).isInstanceOfSatisfying(AiProviderException.class,
                e -> assertThat(e.kind()).isEqualTo(ChatErrorKind.PROVIDER_RATE_LIMIT));
    }

    @Test
    void http5xxIsAProviderError() {
        status = 503;
        body = "{\"error\":{\"code\":503}}";

        assertThatThrownBy(() -> client().generate("s", "u")).isInstanceOfSatisfying(AiProviderException.class,
                e -> assertThat(e.kind()).isEqualTo(ChatErrorKind.PROVIDER_ERROR));
    }

    @Test
    void http401And403MeanTheKeyIsRejected() {
        for (int code : new int[] {401, 403}) {
            status = code;
            body = "{\"error\":{\"code\":" + code + "}}";

            assertThatThrownBy(() -> client().embedQuery("x")).as("HTTP %d", code).isInstanceOfSatisfying(
                    AiProviderException.class, e -> assertThat(e.kind()).isEqualTo(ChatErrorKind.NO_KEY));
        }
    }

    @Test
    void http400WithAnInvalidKeyReasonMeansTheKeyIsRejected() {
        status = 400;
        body = "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}";

        assertThatThrownBy(() -> client().embedQuery("x")).isInstanceOfSatisfying(AiProviderException.class,
                e -> assertThat(e.kind()).isEqualTo(ChatErrorKind.NO_KEY));
    }

    @Test
    void otherHttp400IsAProviderError() {
        status = 400;
        body = "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\"}}";

        assertThatThrownBy(() -> client().embedQuery("x")).isInstanceOfSatisfying(AiProviderException.class,
                e -> assertThat(e.kind()).isEqualTo(ChatErrorKind.PROVIDER_ERROR));
    }

    @Test
    void slowProviderIsATimeoutAtTheConfiguredSeconds() {
        when(config.getInt(ConfigKey.AI_LLM_TIMEOUT_SECONDS)).thenReturn(1);
        delayMillis = 2500;
        body = "{\"embedding\":{\"values\":" + vectorJson(768) + "}}";

        assertThatThrownBy(() -> client().embedQuery("x")).isInstanceOfSatisfying(AiProviderException.class,
                e -> assertThat(e.kind()).isEqualTo(ChatErrorKind.TIMEOUT));
    }

    @Test
    void unreachableProviderIsAProviderError() {
        AiProperties dead = new AiProperties("gemini", KEY, "http://127.0.0.1:1/v1beta/models/", "m", "e", 768);

        assertThatThrownBy(() -> new GeminiClient(dead, config).embedQuery("x")).isInstanceOfSatisfying(
                AiProviderException.class, e -> assertThat(e.kind()).isEqualTo(ChatErrorKind.PROVIDER_ERROR));
    }

    @Test
    void blankKeyFailsWithNoKeyBeforeAnyHttpCall() {
        for (String key : new String[] {"", "   ", null}) {
            GeminiClient noKey = new GeminiClient(props("gemini", key), config);

            assertThatThrownBy(() -> noKey.embedQuery("x")).isInstanceOfSatisfying(AiProviderException.class,
                    e -> assertThat(e.kind()).isEqualTo(ChatErrorKind.NO_KEY));
            assertThatThrownBy(() -> noKey.embedDocuments(List.of("x"))).isInstanceOf(AiProviderException.class);
            assertThatThrownBy(() -> noKey.generate("s", "u")).isInstanceOf(AiProviderException.class);
        }
        assertThat(requests).isEmpty();
    }

    @Test
    void providerOffFailsWithNoKeyEvenWhenAKeyIsSet() {
        GeminiClient off = new GeminiClient(props("off", KEY), config);

        assertThatThrownBy(() -> off.generate("s", "u")).isInstanceOfSatisfying(AiProviderException.class,
                e -> assertThat(e.kind()).isEqualTo(ChatErrorKind.NO_KEY));
        assertThat(requests).isEmpty();
    }

    @Test
    void theKeyNeverAppearsInTheUrlOrInAnyErrorMessage() {
        status = 503;
        body = "{\"error\":{\"code\":503}}";

        assertThatThrownBy(() -> client().embedQuery("x")).satisfies(e -> {
            assertThat(e.getMessage()).doesNotContain(KEY);
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                assertThat(String.valueOf(cause.getMessage())).doesNotContain(KEY);
            }
        });
        assertThat(requests).allSatisfy(request -> {
            assertThat(String.valueOf(request.query())).doesNotContain(KEY);
            assertThat(request.path()).doesNotContain(KEY);
        });
    }

    @Test
    void propertiesToStringMasksTheKey() {
        assertThat(props("gemini", KEY).toString()).doesNotContain(KEY);
    }
}
