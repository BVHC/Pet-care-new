package com.petcare.module.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.ai.FakeEmbeddingClient;
import com.petcare.module.ai.FakeLlmClient;
import com.petcare.module.ai.llm.AnswerCategory;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.identity.service.SessionService;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.support.MutableClock;

/**
 * Chế độ mở qua HTTP trên Postgres thật (BR-AI-33, docs/adr/0136 "Đã cài ở T92 (phần 2)"), với nhúng và LLM giả: kiểm thứ lớp
 * giả nhận được (lược đồ nào, lời nhắc nào), phản hồi và dòng {@code ai_chat_logs} (nguồn, ý định) do {@code ChatService}
 * ghi. Không có tài liệu nào được truy hồi, nên mọi câu đi thẳng tới LLM. Era <b>năm 2049</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, OpenDomainIT.TestBeans.class})
class OpenDomainIT {

    private static final String TAG = "OpenDomainIT";
    private static final Instant T0 = Instant.parse("2049-03-10T03:00:00Z");
    private static final String ME_CHAT = "/api/me/ai/chat";
    private static final String PUBLIC_CHAT = "/api/public/ai/chat";
    private static final String OUT_OF_DOMAIN_QUESTION = "Thủ đô của Pháp là gì";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }

        @Bean
        @Primary
        FakeEmbeddingClient fakeEmbeddingClient() {
            return new FakeEmbeddingClient();
        }

        @Bean
        @Primary
        FakeLlmClient fakeLlmClient() {
            return new FakeLlmClient();
        }
    }

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private FakeEmbeddingClient embeddings;
    @Autowired
    private FakeLlmClient llm;
    @Autowired
    private SessionService sessions;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private SystemConfigService configService;

    private AppointmentItSupport support;
    private String token;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        embeddings.reset();
        llm.reset();
        support = new AppointmentItSupport(jdbc);
        jdbc.update("DELETE FROM ai_chat_logs WHERE created_at >= ?", java.sql.Timestamp.from(T0));
        setSwitch("ai.chat.general_knowledge_enabled", true);
        setSwitch("ai.chat.open_domain_enabled", true);
        long account = support.customerAccount("od-" + UUID.randomUUID() + "@example.com", "ACTIVE");
        support.onlineCustomer(account, "Khách chế độ mở");
        token = new TransactionTemplate(transactionManager).execute(status -> sessions.open(account, "203.0.113.9", TAG))
                .accessToken();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM ai_chat_logs WHERE created_at >= ?", java.sql.Timestamp.from(T0));
        support.cleanUp();
        setSwitch("ai.chat.general_knowledge_enabled", false);
        setSwitch("ai.chat.open_domain_enabled", false);
    }

    @Test
    void aSignedInCustomerGetsAnOutOfDomainAnswerWithTheOpenPrefixAndTheLogRecordsTheSourceAndTheIntent() {
        llm.reply(AnswerCategory.OUT_OF_DOMAIN, "Paris.");

        Map<String, Object> body = ask(ME_CHAT, token, OUT_OF_DOMAIN_QUESTION);

        assertThat(body).containsEntry("result", "ANSWERED").containsEntry("sourceType", "GENERAL_KNOWLEDGE")
                .containsEntry("answer", ChatTexts.OPEN_PREFIX + " Paris.").containsEntry("disclaimer", ChatTexts.OPEN_DISCLAIMER);
        assertThat((List<?>) body.get("sources")).isEmpty();
        assertThat(llm.calls()).hasSize(1);
        assertThat(llm.calls().get(0).categorized()).isTrue();
        assertThat(llm.calls().get(0).systemPrompt()).isEqualTo(ChatPrompt.SYSTEM_OPEN);
        Map<String, Object> row = loggedRow();
        assertThat(row).containsEntry("result", "ANSWERED").containsEntry("source_type", "GENERAL_KNOWLEDGE")
                .containsEntry("intent", "OUT_OF_DOMAIN");
    }

    @Test
    void aMedicalRiskCategoryIsRefusedAndLoggedAsSafetyAndMedicalRisk() {
        llm.reply(AnswerCategory.MEDICAL_RISK, "Bạn nên cho uống thuốc.");

        Map<String, Object> body = ask(ME_CHAT, token, "Mình bị đau đầu thì sao");

        assertThat(body).containsEntry("result", "MEDICAL_REFUSAL").containsEntry("answer", ChatTexts.MEDICAL_REFUSAL)
                .containsEntry("sourceType", "SAFETY");
        assertThat(loggedRow()).containsEntry("intent", "MEDICAL_RISK").containsEntry("source_type", "SAFETY");
    }

    @Test
    void withTheOpenSwitchOffTheSameCustomerGetsTheOldSchemaAndIsLoggedAsPetCare() {
        setSwitch("ai.chat.open_domain_enabled", false);

        Map<String, Object> body = ask(ME_CHAT, token, OUT_OF_DOMAIN_QUESTION);

        assertThat(body).containsEntry("result", "ANSWERED");
        assertThat((String) body.get("answer")).startsWith(ChatTexts.GENERAL_PREFIX);
        assertThat(llm.calls()).hasSize(1);
        assertThat(llm.calls().get(0).categorized()).isFalse();
        assertThat(llm.calls().get(0).systemPrompt()).isEqualTo(ChatPrompt.SYSTEM_GENERAL);
        assertThat(loggedRow()).containsEntry("intent", "PET_CARE").containsEntry("source_type", "GENERAL_KNOWLEDGE");
    }

    @Test
    void theAnonymousEndpointNeverUsesTheOpenMode() {
        ask(PUBLIC_CHAT, null, OUT_OF_DOMAIN_QUESTION);

        assertThat(llm.calls()).hasSize(1);
        assertThat(llm.calls().get(0).categorized()).isFalse();
        assertThat(llm.calls().get(0).systemPrompt()).isEqualTo(ChatPrompt.SYSTEM_GENERAL);
    }

    @Test
    void cannedBranchesAreLoggedWithTheirIntentToo() {
        ask(ME_CHAT, token, "Chó bị co giật liên tục");
        ask(ME_CHAT, token, "Chó nôn vàng uống thuốc gì");

        assertThat(jdbc.queryForList("SELECT intent FROM ai_chat_logs WHERE created_at >= ? ORDER BY id", String.class,
                java.sql.Timestamp.from(T0))).containsExactly("EMERGENCY", "MEDICAL_RISK");
        assertThat(llm.calls()).isEmpty();
    }

    /** Công tắc [CFG] đọc từ cache; sửa DB rồi nạp lại như UC10 làm sau khi commit. */
    private void setSwitch(String key, boolean on) {
        jdbc.update("UPDATE system_configs SET value = ? WHERE key = ?", String.valueOf(on), key);
        configService.reload();
    }

    private Map<String, Object> ask(String path, String bearer, String question) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        ResponseEntity<Map<String, Object>> response = http.exchange(URI.create(http.getRootUri() + path), HttpMethod.POST,
                new HttpEntity<>(Map.of("question", question), headers), MAP);
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        return data;
    }

    private Map<String, Object> loggedRow() {
        return jdbc.queryForMap("SELECT result, source_type, intent FROM ai_chat_logs WHERE created_at >= ? ORDER BY id DESC LIMIT 1",
                java.sql.Timestamp.from(T0));
    }
}
