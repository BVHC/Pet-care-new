package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SessionService;

/**
 * Đặt lịch trên bean thật của app hiện tại (docs/adr/0043): {@code CustomerQueryApi} và {@code PetQueryApi} của BE-2 còn
 * là placeholder ném lỗi (nợ D010, D031), nên khách và lễ tân đều nhận 500 — không dòng {@code appointments} nào, không
 * số sequence nào bị tiêu, không khóa advisory nào còn treo. Vai trò sai vẫn 403 trước khi chạm placeholder.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BookAppointmentPlaceholderIT {

    private static final LocalDate DAY = LocalDate.now().plusDays(3);
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
    private long medical;
    private long pet;

    @BeforeEach
    void setUp() {
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        medical = data.service("MEDICAL");
        data.openingHours(branch, LocalDate.of(2020, 1, 1), LocalTime.of(8, 0), LocalTime.of(12, 0), null, null);
        data.branchService(branch, medical, true);
        pet = data.pet(data.counterCustomer());
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    @Test
    void customerAndReceptionistGet500AndNothingIsWrittenOrLeftLocked() {
        long appointmentsBefore = jdbc.queryForObject("SELECT count(*) FROM appointments", Long.class);
        Long sequenceBefore = jdbc.queryForObject("SELECT last_value FROM appointment_code_seq", Long.class);

        for (String token : List.of(token(data.customerAccount()), token(data.staff("RECEPTIONIST", branch)))) {
            ResponseEntity<Map<String, Object>> response = post(token);
            assertThat(response.getStatusCode()).as("%s", response.getBody())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).containsEntry("errorCode", "INTERNAL_ERROR");
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Long.class)).isEqualTo(appointmentsBefore);
        assertThat(jdbc.queryForObject("SELECT last_value FROM appointment_code_seq", Long.class))
                .isEqualTo(sequenceBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'", Long.class))
                .isZero();
    }

    @Test
    void wrongRoleIsStill403BeforeAnyPlaceholder() {
        ResponseEntity<Map<String, Object>> response = post(token(data.staff("VET", branch)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("errorCode", "ACCESS_DENIED");
    }

    private String token(long accountId) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> sessions.open(accountId, "203.0.113.9", "BookAppointmentPlaceholderIT"))
                .accessToken();
    }

    private ResponseEntity<Map<String, Object>> post(String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", pet);
        body.put("branchId", branch);
        body.put("serviceId", medical);
        body.put("slotDate", DAY.toString());
        body.put("slotStart", "08:30");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return http.exchange(URI.create(http.getRootUri() + "/api/appointments"), HttpMethod.POST,
                new HttpEntity<>(body, headers), MAP);
    }
}
