package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.TestcontainersConfiguration;

import io.swagger.v3.core.util.Yaml;

/**
 * Enum của response {@code Visit} khớp hợp đồng (docs/adr/0059 E5). {@code OpenApiContractParityIT} chỉ so cây tên
 * property ({@code shape()} trả "leaf" cho mọi lá), không so tập giá trị enum, nên đổi tên một hằng số hay thêm
 * {@code BOARDING} vào {@code serviceGroup} không bị nó bắt.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class VisitContractEnumIT {

    private static final Path CONTRACT = Path.of("..", "docs", "api", "openapi", "visit-v1.yaml");
    private static final List<String> ENUM_FIELDS = List.of("species", "serviceGroup", "status", "priorityClass");

    @Autowired
    private TestRestTemplate http;

    @Test
    void enumValuesOfTheVisitResponseEqualTheContract() throws IOException {
        ResponseEntity<String> response = http.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode code = new ObjectMapper().readTree(response.getBody());
        JsonNode contract = Yaml.mapper().readTree(CONTRACT.toFile());

        JsonNode codeVisit = code.at("/components/schemas/VisitResponse/properties");
        JsonNode contractVisit = contract.at("/components/schemas/Visit/properties");
        assertThat(codeVisit.isMissingNode()).as("VisitResponse có trong /v3/api-docs").isFalse();
        for (String field : ENUM_FIELDS) {
            Set<String> expected = values(contract, contractVisit.path(field));
            assertThat(expected).as("hợp đồng có enum cho " + field).isNotEmpty();
            assertThat(values(code, codeVisit.path(field))).as(field).isEqualTo(expected);
        }
    }

    private static Set<String> values(JsonNode root, JsonNode node) {
        JsonNode current = node;
        while (current.has("$ref")) {
            current = root.at(current.path("$ref").asText().substring(1));
        }
        Set<String> values = new TreeSet<>();
        current.path("enum").forEach(v -> values.add(v.asText()));
        return values;
    }
}
