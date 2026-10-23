package com.petcare;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.core.util.Yaml;

/**
 * Tài liệu OpenAPI sinh từ code ({@code /v3/api-docs}) khớp hợp đồng {@code docs/api/openapi/*.yaml} (docs/adr/0040,
 * docs/convention/backend/10-swagger-api-docs.md §5, §7). Với mọi endpoint đã cài của các module trong
 * {@link #DOCUMENTED}: method + path, {@code operationId}, tag, summary, description, security, mã thành công, tập mã lỗi
 * kèm mô tả từng lỗi, tên tham số, cây property của request body và của {@code data} phải bằng hợp đồng; mọi property lá
 * có ví dụ; response không lộ trường bí mật. Sửa generator hợp đồng mà quên annotation (hoặc ngược lại) thì đỏ.
 * <p>
 * Module của BE-2 chưa có annotation (branch, catalog — nợ D026) chỉ bị kiểm "endpoint có trong hợp đồng".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiContractParityIT {

    private static final Path CONTRACT_DIR = Path.of("..", "docs", "api", "openapi");
    /** File hợp đồng đã có annotation đầy đủ; thêm file khi module đó viết xong tài liệu (D026: branch, catalog). */
    private static final Set<String> DOCUMENTED = Set.of("identity-v1.yaml", "care-v1.yaml", "customer-v1.yaml",
            "inventory-v1.yaml", "sales-v1.yaml", "appointment-v1.yaml", "visit-v1.yaml", "boarding-v1.yaml", "content-v1.yaml");
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");
    private static final Pattern SECRET = Pattern.compile("(?i)(password|secret|hash)$|^otp(code)?$");
    /** Tên khớp {@link #SECRET} nhưng không phải bí mật: cờ bắt đổi mật khẩu (BR-TK-17). */
    private static final Set<String> NOT_SECRET = Set.of("mustChangePassword");
    private static final Pattern PATH_PARAM = Pattern.compile("\\{(\\w+)}");

    @Autowired
    private TestRestTemplate http;

    private JsonNode code;
    /** "POST /api/staff" → hợp đồng của operation và tên file chứa nó. */
    private final Map<String, ContractOp> contract = new TreeMap<>();

    record ContractOp(String file, JsonNode root, JsonNode op) {
    }

    record CodeOp(String key, String path, JsonNode op) {
    }

    @BeforeAll
    void loadDocuments() throws IOException {
        ResponseEntity<String> response = http.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        code = new ObjectMapper().readTree(response.getBody());
        // Bản spec để chạy validator ngoài (ví dụ openapi-spec-validator) khi smoke test.
        Files.writeString(Path.of("target", "openapi.json"), response.getBody());
        try (Stream<Path> files = Files.list(CONTRACT_DIR)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                JsonNode root = Yaml.mapper().readTree(file.toFile());
                root.path("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(m -> {
                    if (METHODS.contains(m.getKey())) {
                        contract.put(key(m.getKey(), "/api" + path.getKey()),
                                new ContractOp(file.getFileName().toString(), root, m.getValue()));
                    }
                }));
            }
        }
    }

    @Test
    void everyImplementedEndpointIsInTheContract() {
        List<String> missing = codeOps().stream().map(CodeOp::key).filter(k -> !contract.containsKey(k)).toList();

        assertThat(missing).as("endpoint của code không có trong docs/api/openapi").isEmpty();
    }

    @Test
    void documentedModulesCoverAllTheirImplementedEndpoints() {
        // 85 (identity + care + customer + inventory + sales, gồm 5 endpoint Order bán lẻ T29) + 9 appointment #1–9
        // + 10 visit #1 (T26 phần 1, docs/adr/0059), #2–4 (T26 phần 2, docs/adr/0060), #5–7 (T26 phần 3, docs/adr/0061),
        // #8, #11 (T26 phần 4, docs/adr/0062), #9 (T26 phần 5, docs/adr/0063), #10, #12–15 (T27, docs/adr/0066–0070)
        // + 10 sales cashier #12–21 (T30, docs/adr/0064) + 12 boarding #1–12 (T31, docs/adr/0065) + 22 content #1–22 (T32, docs/adr/0071).
        assertThat(documentedOps())
                .as("số endpoint identity + care + customer + inventory + sales + appointment + visit + boarding + content đã cài")
                .hasSize(153);
    }

    @Test
    void operationMetadataMatchesContract() {
        List<String> errors = new ArrayList<>();
        for (CodeOp op : documentedOps()) {
            JsonNode expected = contract.get(op.key()).op();
            for (String field : List.of("operationId", "summary", "description")) {
                if (!expected.path(field).asText().equals(op.op().path(field).asText())) {
                    errors.add(op.key() + " " + field + ": code=" + op.op().path(field).asText() + " | contract="
                            + expected.path(field).asText());
                }
            }
            if (!expected.path("tags").equals(op.op().path("tags"))) {
                errors.add(op.key() + " tags: code=" + op.op().path("tags") + " | contract=" + expected.path("tags"));
            }
        }
        assertThat(errors).isEmpty();
    }

    @Test
    void securityMatchesContractAndRuntimePublicPaths() {
        List<String> errors = new ArrayList<>();
        for (CodeOp op : documentedOps()) {
            boolean contractPublic = isEmptyArray(contract.get(op.key()).op().path("security"));
            boolean codePublic = isEmptyArray(op.op().path("security"));
            if (contractPublic != codePublic) {
                errors.add(op.key() + " public: code=" + codePublic + " | contract=" + contractPublic);
            }
        }
        assertThat(errors).isEmpty();
        assertThat(code.path("security").toString()).isEqualTo("[{\"bearerAuth\":[]}]");
        assertThat(code.path("components").path("securitySchemes").path("bearerAuth").path("scheme").asText())
                .isEqualTo("bearer");
    }

    @Test
    void responseCodesAndErrorDescriptionsMatchContract() {
        List<String> errors = new ArrayList<>();
        for (CodeOp op : documentedOps()) {
            JsonNode expected = contract.get(op.key()).op().path("responses");
            JsonNode actual = op.op().path("responses");
            Set<String> expectedCodes = fieldNames(expected);
            Set<String> actualCodes = fieldNames(actual);
            if (!actualCodes.remove("500")) {
                errors.add(op.key() + " thiếu 500");
            }
            if (!expectedCodes.equals(actualCodes)) {
                errors.add(op.key() + " mã: code=" + actualCodes + " | contract=" + expectedCodes);
                continue;
            }
            for (String status : expectedCodes) {
                if (status.startsWith("2")) {
                    continue;
                }
                JsonNode e = expected.path(status);
                JsonNode a = actual.path(status);
                if (e.has("$ref")) {
                    if (!e.path("$ref").asText().equals(a.path("$ref").asText())) {
                        errors.add(op.key() + " " + status + " $ref: code=" + a.path("$ref") + " | contract="
                                + e.path("$ref"));
                    }
                } else if (!e.path("description").asText().equals(a.path("description").asText())) {
                    errors.add(op.key() + " " + status + " mô tả: code=" + a.path("description").asText()
                            + " | contract=" + e.path("description").asText());
                } else if (!a.at("/content/application~1json/schema/$ref").asText()
                        .equals("#/components/schemas/ErrorResponse")) {
                    errors.add(op.key() + " " + status + " không trỏ schema ErrorResponse");
                }
            }
        }
        assertThat(errors).isEmpty();
    }

    @Test
    void parameterNamesMatchContract() {
        List<String> errors = new ArrayList<>();
        for (CodeOp op : documentedOps()) {
            ContractOp expected = contract.get(op.key());
            Set<String> e = parameters(expected.root(), expected.op());
            Set<String> a = parameters(code, op.op());
            if (!e.equals(a)) {
                errors.add(op.key() + " tham số: code=" + a + " | contract=" + e);
            }
        }
        assertThat(errors).isEmpty();
    }

    @Test
    void requestAndResponsePropertyTreesMatchContract() {
        List<String> errors = new ArrayList<>();
        for (CodeOp op : documentedOps()) {
            ContractOp expected = contract.get(op.key());
            Object e = shape(expected.root(), expected.op().at("/requestBody/content/application~1json/schema"));
            Object a = shape(code, op.op().at("/requestBody/content/application~1json/schema"));
            if (!e.equals(a)) {
                errors.add(op.key() + " request: code=" + a + " | contract=" + e);
            }
            Object ed = shape(expected.root(), dataSchema(expected.root(), expected.op()));
            Object ad = shape(code, dataSchema(code, op.op()));
            if (!ed.equals(ad)) {
                errors.add(op.key() + " response data: code=" + ad + " | contract=" + ed);
            }
        }
        assertThat(errors).isEmpty();
    }

    @Test
    void everyLeafPropertyHasARealisticExample() {
        List<String> errors = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (CodeOp op : documentedOps()) {
            collectExampleErrors(op.op().at("/requestBody/content/application~1json/schema"), visited, errors);
            op.op().path("responses").forEach(r -> collectExampleErrors(r.at("/content/application~1json/schema"),
                    visited, errors));
        }
        assertThat(errors).isEmpty();
    }

    @Test
    void responsesExposeNoSecretField() {
        List<String> errors = new ArrayList<>();
        for (CodeOp op : documentedOps()) {
            op.op().path("responses").fields().forEachRemaining(r -> {
                if (r.getKey().startsWith("2")) {
                    collectSecretFields(r.getValue().at("/content/application~1json/schema"), new HashSet<>(),
                            op.key() + " " + r.getKey(), errors);
                }
            });
        }
        assertThat(errors).isEmpty();
    }

    @Test
    void structureIsConsistent() {
        List<String> errors = new ArrayList<>();
        Set<String> operationIds = new HashSet<>();
        for (CodeOp op : codeOps()) {
            if (!operationIds.add(op.op().path("operationId").asText())) {
                errors.add("operationId trùng: " + op.op().path("operationId").asText());
            }
            Set<String> declared = new TreeSet<>();
            op.op().path("parameters").forEach(p -> {
                if ("path".equals(p.path("in").asText()) && p.path("required").asBoolean()) {
                    declared.add(p.path("name").asText());
                }
            });
            Matcher matcher = PATH_PARAM.matcher(op.path());
            while (matcher.find()) {
                if (!declared.contains(matcher.group(1))) {
                    errors.add(op.key() + " thiếu path param bắt buộc " + matcher.group(1));
                }
            }
        }
        for (CodeOp op : documentedOps()) {
            if (op.op().path("summary").asText().isBlank() || op.op().path("tags").isEmpty()) {
                errors.add(op.key() + " thiếu summary / tags");
            }
        }
        Matcher refs = Pattern.compile("\"\\$ref\":\"#/components/(\\w+)/([^\"]+)\"").matcher(code.toString());
        while (refs.find()) {
            if (code.path("components").path(refs.group(1)).path(refs.group(2)).isMissingNode()) {
                errors.add("$ref hỏng: " + refs.group());
            }
        }
        assertThat(errors).isEmpty();
    }

    @Test
    void docsAreAnonymousEvenWithAGarbageToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("rac.khong.phai.token");

        ResponseEntity<String> docs = http.exchange("/v3/api-docs", HttpMethod.GET, new HttpEntity<>(headers),
                String.class);
        ResponseEntity<String> ui = http.exchange("/swagger-ui/index.html", HttpMethod.GET, new HttpEntity<>(headers),
                String.class);

        assertThat(docs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ui.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- helpers ---

    private static String key(String method, String path) {
        return method.toUpperCase() + " " + path;
    }

    private List<CodeOp> codeOps() {
        List<CodeOp> ops = new ArrayList<>();
        code.path("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(m -> {
            if (METHODS.contains(m.getKey())) {
                ops.add(new CodeOp(key(m.getKey(), path.getKey()), path.getKey(), m.getValue()));
            }
        }));
        return ops;
    }

    private List<CodeOp> documentedOps() {
        return codeOps().stream()
                .filter(op -> contract.containsKey(op.key()) && DOCUMENTED.contains(contract.get(op.key()).file()))
                .toList();
    }

    private static boolean isEmptyArray(JsonNode node) {
        return node.isArray() && node.isEmpty();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode resolve(JsonNode root, JsonNode node) {
        JsonNode current = node;
        while (current != null && current.has("$ref")) {
            current = root.at(current.path("$ref").asText().substring(1));
        }
        return current;
    }

    private static Set<String> parameters(JsonNode root, JsonNode op) {
        Set<String> names = new TreeSet<>();
        op.path("parameters").forEach(p -> {
            JsonNode param = resolve(root, p);
            names.add(param.path("in").asText() + ":" + param.path("name").asText());
        });
        return names;
    }

    /** Schema của {@code data} trong response thành công; {@code MissingNode} khi 204. */
    private static JsonNode dataSchema(JsonNode root, JsonNode op) {
        Iterator<Map.Entry<String, JsonNode>> responses = op.path("responses").fields();
        while (responses.hasNext()) {
            Map.Entry<String, JsonNode> response = responses.next();
            if (response.getKey().startsWith("2")) {
                JsonNode envelope = resolve(root, response.getValue().at("/content/application~1json/schema"));
                return envelope == null ? null : envelope.path("properties").path("data");
            }
        }
        return null;
    }

    /**
     * Cây tên property của một schema: object → map tên → cây con, mảng → {@code List.of(cây phần tử)}, còn lại →
     * {@code "leaf"}. Giải {@code $ref}, gộp {@code allOf}, lấy nhánh khác null của {@code oneOf}/{@code anyOf}.
     */
    private static Object shape(JsonNode root, JsonNode node) {
        return shape(root, node, new HashSet<>());
    }

    private static Object shape(JsonNode root, JsonNode node, Set<String> refs) {
        if (node == null || node.isMissingNode()) {
            return "none";
        }
        if (node.has("$ref")) {
            String ref = node.path("$ref").asText();
            if (!refs.add(ref)) {
                return "cycle";
            }
            Object result = shape(root, root.at(ref.substring(1)), refs);
            refs.remove(ref);
            return result;
        }
        for (String combinator : List.of("oneOf", "anyOf")) {
            if (node.has(combinator)) {
                for (JsonNode option : node.path(combinator)) {
                    if (!"null".equals(option.path("type").asText())) {
                        return shape(root, option, refs);
                    }
                }
            }
        }
        if (node.has("allOf")) {
            Map<String, Object> merged = new TreeMap<>();
            Object single = null;
            for (JsonNode part : node.path("allOf")) {
                Object partShape = shape(root, part, refs);
                if (partShape instanceof Map<?, ?> map) {
                    map.forEach((k, v) -> merged.put((String) k, v));
                } else {
                    single = partShape;
                }
            }
            return merged.isEmpty() && single != null ? single : merged;
        }
        if (node.has("items")) {
            return List.of(shape(root, node.path("items"), refs));
        }
        if (node.has("properties")) {
            Map<String, Object> props = new TreeMap<>();
            node.path("properties").fields().forEachRemaining(p -> props.put(p.getKey(), shape(root, p.getValue(),
                    refs)));
            return props;
        }
        return "leaf";
    }

    private void collectExampleErrors(JsonNode node, Set<String> visited, List<String> errors) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        if (node.has("$ref")) {
            String ref = node.path("$ref").asText();
            if (visited.add(ref) && ref.startsWith("#/components/schemas/")) {
                JsonNode schema = code.at(ref.substring(1));
                String name = ref.substring(ref.lastIndexOf('/') + 1);
                schema.path("properties").fields().forEachRemaining(p -> {
                    JsonNode prop = p.getValue();
                    boolean nested = prop.has("$ref") || prop.has("items") || prop.has("allOf")
                            || prop.has("properties");
                    if (nested) {
                        collectExampleErrors(prop.has("items") ? prop.path("items") : prop, visited, errors);
                        return;
                    }
                    if ("data".equals(p.getKey()) && name.startsWith("ApiResponse")) {
                        return;
                    }
                    JsonNode example = prop.path("example");
                    boolean hasExample = !example.isMissingNode() && !example.isNull()
                            && !(example.isTextual() && Set.of("string", "TODO", "").contains(example.asText()));
                    if (!hasExample && !prop.has("enum")) {
                        errors.add(name + "." + p.getKey() + " thiếu example");
                    }
                });
            }
            return;
        }
        if (node.has("items")) {
            collectExampleErrors(node.path("items"), visited, errors);
        }
    }

    private void collectSecretFields(JsonNode node, Set<String> visited, String where, List<String> errors) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        if (node.has("$ref")) {
            String ref = node.path("$ref").asText();
            if (visited.add(ref)) {
                collectSecretFields(code.at(ref.substring(1)), visited, where, errors);
            }
            return;
        }
        if (node.has("items")) {
            collectSecretFields(node.path("items"), visited, where, errors);
        }
        node.path("allOf").forEach(part -> collectSecretFields(part, visited, where, errors));
        Map<String, JsonNode> props = new LinkedHashMap<>();
        node.path("properties").fields().forEachRemaining(p -> props.put(p.getKey(), p.getValue()));
        props.forEach((name, prop) -> {
            if (SECRET.matcher(name).find() && !NOT_SECRET.contains(name)) {
                errors.add(where + " lộ trường " + name);
            }
            collectSecretFields(prop, visited, where, errors);
        });
    }
}
