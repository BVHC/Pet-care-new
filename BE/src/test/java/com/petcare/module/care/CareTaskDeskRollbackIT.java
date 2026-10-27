package com.petcare.module.care;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.service.SessionService;

/**
 * Lỗi <b>sau khi đã flush</b> trong care-v1 #3 / #4 (docs/adr/0080, mục "Transaction"): UPDATE {@code care_tasks} đã
 * chạy, rồi bước ghép dữ liệu ({@code CustomerQueryApi.findContacts}) ném lỗi. Toàn bộ use case phải rollback — task còn
 * {@code OPEN}, không cột xử lý nào được giữ — và lời gọi sau đó (khi lỗi đã hết) vẫn xử lý được task. Tách khỏi
 * {@link CareTaskDeskIT} vì {@code @MockitoBean} thay bean của cả context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CareTaskDeskRollbackIT {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final String COMPLETE_BODY = "{'result':'REACHED','note':'Khách hẹn tuần sau'}";
    private static final String CANCEL_BODY = "{'reason':'Khách chuyển đi'}";

    @MockitoBean
    private CustomerQueryApi customers;

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SessionService sessions;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private CareTaskItSupport support;
    private long branch;
    private long customer;
    private long pet;
    private long desk;
    private String token;

    @BeforeEach
    void setUp() {
        support = new CareTaskItSupport(jdbc, transactionManager);
        branch = support.branch();
        customer = support.customer();
        pet = support.pet(customer);
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        desk = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', 'hash', 'RECEPTIONIST', 'ACTIVE') RETURNING id""", Long.class,
                "care-rollback-it-" + tag + "@petcare.test");
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, 'Lễ tân', ?)", desk,
                branch);
        token = new TransactionTemplate(transactionManager)
                .execute(status -> sessions.open(desk, "203.0.113.9", "CareTaskDeskRollbackIT")).accessToken();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM care_tasks WHERE branch_id = ?", branch);
        jdbc.update("DELETE FROM sessions WHERE account_id = ?", desk);
        jdbc.update("DELETE FROM staff_profiles WHERE account_id = ?", desk);
        jdbc.update("DELETE FROM accounts WHERE id = ?", desk);
        support.cleanUp();
    }

    private long openTask() {
        return support.task("VACCINE_DUE", support.vaccination(pet), branch, customer, pet, "OPEN",
                LocalDate.of(2031, 7, 1));
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("""
                SELECT status, result, note, cancel_reason, handled_by, handled_at, xmin::text AS xmin
                FROM care_tasks WHERE id = ?""", id);
    }

    private ResponseEntity<Map<String, Object>> post(long id, String action, String singleQuotedJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(URI.create(http.getRootUri() + "/api/care-tasks/" + id + "/" + action), HttpMethod.POST,
                new HttpEntity<>(singleQuotedJson.replace('\'', '"'), headers), MAP);
    }

    private void customersFail() {
        doThrow(new IllegalStateException("module khách lỗi")).when(customers).findContacts(anyCollection());
    }

    private void customersAreReadable() {
        doReturn(List.of(new CustomerContact(customer, "Khách thử", "0912345678", null, null, false))).when(customers)
                .findContacts(anyCollection());
    }

    @Test
    void aFailureWhileJoiningAfterTheFlushRollsBackCompleteAndTheTaskIsStillOpen() {
        long id = openTask();
        Map<String, Object> before = row(id);
        customersFail();

        ResponseEntity<Map<String, Object>> response = post(id, "complete", COMPLETE_BODY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("errorCode", "INTERNAL_ERROR");
        assertThat(row(id)).as("UPDATE đã flush phải được hoàn tác").isEqualTo(before);
    }

    @Test
    void aFailureWhileJoiningAfterTheFlushRollsBackCancelToo() {
        long id = openTask();
        Map<String, Object> before = row(id);
        customersFail();

        ResponseEntity<Map<String, Object>> response = post(id, "cancel", CANCEL_BODY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(row(id)).isEqualTo(before);
    }

    @Test
    void onceTheDependencyIsBackTheSameTaskCanBeCompletedExactlyOnce() {
        long id = openTask();
        customersFail();
        assertThat(post(id, "complete", COMPLETE_BODY).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        customersAreReadable();

        ResponseEntity<Map<String, Object>> retry = post(id, "complete", COMPLETE_BODY);
        ResponseEntity<Map<String, Object>> again = post(id, "complete", COMPLETE_BODY);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(row(id)).containsEntry("status", "DONE").containsEntry("handled_by", desk);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(support.invariantViolations()).isEmpty();
    }
}
