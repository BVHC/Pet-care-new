package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
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
import com.petcare.platform.config.TimeConfig;

/**
 * G8 (docs/adr/0046, D034): {@code AppointmentQueryApi} đã thật, nhưng {@code BoardingQueryApi} của BE-2 còn là
 * placeholder (D033), nên thêm ngày nghỉ vào chi nhánh {@code ACTIVE} vẫn 500 — ở boarding chứ không còn ở
 * appointment — và không ghi gì: không có dòng {@code holidays}, lịch hẹn nguyên vẹn. Dùng bean thật hoàn toàn.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AppointmentQueryApiPlaceholderIT {

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
    private long appointment;
    private String token;
    private LocalDate day;

    @BeforeEach
    void setUp() {
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        long medical = data.service("MEDICAL");
        long staff = data.staff("RECEPTIONIST", branch);
        long customer = data.counterCustomer();
        day = LocalDate.now(TimeConfig.BUSINESS_ZONE).plusDays(25);
        appointment = data.appointment(customer, data.pet(customer), branch, medical, "MEDICAL", day,
                LocalTime.of(9, 0), "BOOKED", staff);
        token = new TransactionTemplate(transactionManager)
                .execute(s -> sessions.open(data.staff("BRANCH_MANAGER", branch), "203.0.113.9",
                        "AppointmentQueryApiPlaceholderIT"))
                .accessToken();
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    @Test
    void addHolidayWithBookedStill500AndWritesNothing() {
        String xmin = jdbc.queryForObject("SELECT xmin::text FROM appointments WHERE id = ?", String.class,
                appointment);

        ResponseEntity<Map<String, Object>> response = post("/holidays", "{\"holidayDate\":\"" + day + "\"}");

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holidays WHERE branch_id = ?", Long.class, branch))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT xmin::text FROM appointments WHERE id = ?", String.class, appointment))
                .isEqualTo(xmin);
    }

    @Test
    void impactPreviewStill500AtBoarding() {
        ResponseEntity<Map<String, Object>> response = post("/holidays/impact",
                "{\"holidayDate\":\"" + day + "\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<Map<String, Object>> post(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return http.exchange(URI.create(http.getRootUri() + "/api/branches/" + branch + path), HttpMethod.POST,
                new HttpEntity<>(json, headers), MAP);
    }
}
