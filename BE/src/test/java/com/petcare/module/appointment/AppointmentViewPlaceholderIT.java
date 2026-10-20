package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SessionService;

/**
 * Xem lịch hẹn trên bean thật của app hiện tại (docs/adr/0048). Từ 2026-10-20 {@code CustomerQueryApi} /
 * {@code PetQueryApi} của BE-2 là bản thật (nợ D010, D031 đã trả): nhân viên thấy trang có dòng kèm tên thú, tên khách;
 * tài khoản khách <b>không có hồ sơ</b> (dữ liệu sai, BR-KH-01) vẫn 500. Vai trò sai vẫn 403.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AppointmentViewPlaceholderIT {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionService sessions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private AppointmentItSupport data;
    private long branch;
    private long receptionist;

    @BeforeEach
    void setUp() {
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        receptionist = data.staff("RECEPTIONIST", branch);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    @Test
    void receptionistGets200ForAnEmptyPageAndForRowsWithNames() {
        String token = token(receptionist);

        ResponseEntity<Map<String, Object>> empty = get(token, "");
        assertThat(empty.getStatusCode()).as("%s", empty.getBody()).isEqualTo(HttpStatus.OK);

        long customer = data.counterCustomer();
        long id = data.appointment(customer, data.pet(customer), branch, data.service("MEDICAL"), "MEDICAL",
                LocalDate.now().plusDays(2), LocalTime.of(8, 0), "BOOKED", receptionist);

        for (ResponseEntity<Map<String, Object>> response : List.of(get(token, ""), get(token, "/" + id))) {
            assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().toString()).contains("petName=Milu");
        }
    }

    @Test
    void customerAlwaysGets500() {
        String token = token(data.customerAccount());

        for (ResponseEntity<Map<String, Object>> response : List.of(get(token, ""), get(token, "/1"))) {
            assertThat(response.getStatusCode()).as("%s", response.getBody())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Test
    void wrongRoleIsStill403BeforeAnyPlaceholder() {
        ResponseEntity<Map<String, Object>> response = get(token(data.staff("VET", branch)), "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("errorCode", "ACCESS_DENIED");
    }

    private String token(long accountId) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> sessions.open(accountId, "203.0.113.9", "AppointmentViewPlaceholderIT"))
                .accessToken();
    }

    private ResponseEntity<Map<String, Object>> get(String token, String suffix) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(URI.create(http.getRootUri() + "/api/appointments" + suffix), HttpMethod.GET,
                new HttpEntity<>(headers), MAP);
    }
}
