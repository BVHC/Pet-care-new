package com.petcare.module.ai.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.module.ai.entity.ChatErrorKind;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;

/**
 * Gemini qua {@link RestClient}, không thêm thư viện AI (docs/adr/0109 D4, 0130). Khóa chỉ đi trong header
 * {@code x-goog-api-key}, không vào URL hay message lỗi (BR-AI-25). Thiếu khóa không làm BE hỏng lúc khởi động: lỗi
 * {@code NO_KEY} chỉ xuất hiện khi gọi (BR-AI-10). Hết giờ theo [CFG] {@code ai.llm.timeout_seconds}, đọc ở mỗi lần gọi.
 * Lớp này chỉ phân loại lỗi và <b>không thử lại</b>: chat không được thử lại trong cùng request (BR-AI-10), còn ST22 tự
 * thử lại khi 429 (07 §5.3). Logic gốc: {@code BE/scripts/spike/gemini.mjs} (T56).
 */
@Component
@EnableConfigurationProperties(AiProperties.class)
public class GeminiClient implements LlmClient, EmbeddingClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> ANSWER_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of("inScope", Map.of("type", "BOOLEAN"), "answer", Map.of("type", "STRING")),
            "required", List.of("inScope", "answer"));

    private final AiProperties props;
    private final SystemConfigApi config;
    private final JdkClientHttpRequestFactory requestFactory;
    private final RestClient http;

    public GeminiClient(AiProperties props, SystemConfigApi config) {
        this.props = props;
        this.config = config;
        // HTTP/1.1: đủ cho Gemini và tránh nâng cấp h2c trên máy chủ giả của test.
        this.requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build());
        this.http = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public String embeddingModel() {
        return props.embeddingModel();
    }

    @Override
    public String llmModel() {
        return props.llmModel();
    }

    @Override
    public float[] embedQuery(String text) {
        JsonNode response = post(props.embeddingModel(), "embedContent", embedRequest(text, "RETRIEVAL_QUERY"));
        return vector(response.path("embedding").path("values"));
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> requests = new ArrayList<>();
        for (String text : texts) {
            requests.add(embedRequest(text, "RETRIEVAL_DOCUMENT"));
        }
        JsonNode embeddings = post(props.embeddingModel(), "batchEmbedContents", Map.of("requests", requests))
                .path("embeddings");
        if (!embeddings.isArray() || embeddings.size() != texts.size()) {
            throw providerError("Nhà cung cấp trả " + embeddings.size() + " vector cho " + texts.size() + " đoạn");
        }
        List<float[]> vectors = new ArrayList<>();
        for (JsonNode embedding : embeddings) {
            vectors.add(vector(embedding.path("values")));
        }
        return vectors;
    }

    @Override
    public LlmAnswer generate(String systemPrompt, String userBlock) {
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("temperature", 0.2);
        generation.put("maxOutputTokens", 600);
        generation.put("thinkingConfig", Map.of("thinkingBudget", 0));
        generation.put("responseMimeType", "application/json");
        generation.put("responseSchema", ANSWER_SCHEMA);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userBlock)))));
        body.put("generationConfig", generation);

        JsonNode response = post(props.llmModel(), "generateContent", body);
        JsonNode candidate = response.path("candidates").path(0);
        JsonNode parsed;
        try {
            parsed = JSON.readTree(candidate.path("content").path("parts").path(0).path("text").asText(""));
        } catch (java.io.IOException e) {
            throw providerError("Câu trả lời không phải JSON (finishReason=" + candidate.path("finishReason").asText("?") + ")");
        }
        if (!parsed.path("inScope").isBoolean() || !parsed.path("answer").isTextual()) {
            throw providerError("JSON thiếu inScope / answer (finishReason=" + candidate.path("finishReason").asText("?") + ")");
        }
        JsonNode usage = response.path("usageMetadata");
        return new LlmAnswer(parsed.get("inScope").asBoolean(), parsed.get("answer").asText(), token(usage, "promptTokenCount"),
                token(usage, "candidatesTokenCount"), token(usage, "thoughtsTokenCount"));
    }

    private Map<String, Object> embedRequest(String text, String taskType) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "models/" + props.embeddingModel());
        request.put("content", Map.of("parts", List.of(Map.of("text", text))));
        request.put("taskType", taskType);
        request.put("outputDimensionality", props.embeddingDim());
        return request;
    }

    private JsonNode post(String model, String method, Object body) {
        if ("off".equalsIgnoreCase(props.provider()) || props.apiKey() == null || props.apiKey().isBlank()) {
            throw new AiProviderException(ChatErrorKind.NO_KEY, "Chưa cấu hình khóa API của nhà cung cấp AI (GEMINI_API_KEY)");
        }
        requestFactory.setReadTimeout(Duration.ofSeconds(config.getInt(ConfigKey.AI_LLM_TIMEOUT_SECONDS)));
        try {
            JsonNode response = http.post().uri(URI.create(props.baseUrl() + model + ":" + method))
                    .header("x-goog-api-key", props.apiKey()).contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(JsonNode.class);
            if (response == null) {
                throw providerError("Nhà cung cấp trả thân rỗng");
            }
            return response;
        } catch (RestClientResponseException e) {
            throw classify(e);
        } catch (ResourceAccessException e) {
            boolean timeout = false;
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                timeout |= cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException;
            }
            throw new AiProviderException(timeout ? ChatErrorKind.TIMEOUT : ChatErrorKind.PROVIDER_ERROR,
                    timeout ? "Nhà cung cấp AI quá hạn" : "Không kết nối được nhà cung cấp AI", e);
        } catch (RestClientException e) {
            throw new AiProviderException(ChatErrorKind.PROVIDER_ERROR, "Không đọc được phản hồi của nhà cung cấp AI", e);
        }
    }

    /** 429 → hết hạn mức; 401 / 403 và 400 {@code API_KEY_INVALID} → khóa bị từ chối; còn lại → lỗi nhà cung cấp. */
    private static AiProviderException classify(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        ChatErrorKind kind;
        if (status == 429) {
            kind = ChatErrorKind.PROVIDER_RATE_LIMIT;
        } else if (status == 401 || status == 403
                || status == 400 && e.getResponseBodyAsString().contains("API_KEY_INVALID")) {
            kind = ChatErrorKind.NO_KEY;
        } else {
            kind = ChatErrorKind.PROVIDER_ERROR;
        }
        return new AiProviderException(kind, "Nhà cung cấp AI trả HTTP " + status, e);
    }

    private float[] vector(JsonNode values) {
        if (!values.isArray() || values.size() != props.embeddingDim()) {
            throw providerError("Số chiều vector " + values.size() + ", cần " + props.embeddingDim());
        }
        float[] vector = new float[values.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) values.get(i).asDouble();
        }
        return vector;
    }

    private static Integer token(JsonNode usage, String field) {
        return usage.path(field).isNumber() ? usage.get(field).asInt() : null;
    }

    private static AiProviderException providerError(String message) {
        return new AiProviderException(ChatErrorKind.PROVIDER_ERROR, message);
    }
}
