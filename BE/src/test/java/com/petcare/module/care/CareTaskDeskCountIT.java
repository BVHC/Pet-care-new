package com.petcare.module.care;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
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

import jakarta.persistence.EntityManagerFactory;

/**
 * Không N+1 ở care-v1 #1 (docs/adr/0080, mục "Query"): số câu SQL Hibernate của một lần xem danh sách là <b>một con
 * số cố định</b> cho 1 task và cho 20 task (ghép tên khách, tên thú, loại vaccine theo lô). Cả hai trang đều có task
 * tái chủng để cùng đi qua nhánh tra loại vaccine. Context riêng bật Hibernate Statistics.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CareTaskDeskCountIT {

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SessionService sessions;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private CareTaskItSupport support;
    private Statistics statistics;
    private long branch;
    private long customer;
    private long pet;
    private long vaccineType;
    private long desk;
    private String token;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled()).as("generate_statistics").isTrue();
        support = new CareTaskItSupport(jdbc, transactionManager);
        branch = support.branch();
        customer = support.customer();
        pet = support.pet(customer);
        vaccineType = support.vaccineType("Dại count");
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        desk = jdbc.queryForObject("""
                INSERT INTO accounts (email, phone, password_hash, role, status)
                VALUES (?, '0900000000', 'hash', 'RECEPTIONIST', 'ACTIVE') RETURNING id""", Long.class,
                "care-count-it-" + tag + "@petcare.test");
        jdbc.update("INSERT INTO staff_profiles (account_id, full_name, branch_id) VALUES (?, 'Lễ tân', ?)", desk,
                branch);
        token = new TransactionTemplate(transactionManager)
                .execute(status -> sessions.open(desk, "203.0.113.9", "CareTaskDeskCountIT")).accessToken();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM care_tasks WHERE branch_id = ?", branch);
        jdbc.update("DELETE FROM sessions WHERE account_id = ?", desk);
        jdbc.update("DELETE FROM staff_profiles WHERE account_id = ?", desk);
        jdbc.update("DELETE FROM accounts WHERE id = ?", desk);
        support.cleanUp();
    }

    private void addVaccineTasks(int count) {
        for (int i = 0; i < count; i++) {
            support.task("VACCINE_DUE", support.vaccination(pet, vaccineType), branch, customer, pet, "OPEN",
                    LocalDate.of(2031, 7, 1).plusDays(i));
        }
    }

    private void addFollowUpTasks(int count) {
        for (int i = 0; i < count; i++) {
            support.task("FOLLOW_UP_DUE", support.medicalRecord(branch, customer, pet), branch, customer, pet, "OPEN",
                    LocalDate.of(2031, 8, 1).plusDays(i));
        }
    }

    /** Số câu SQL Hibernate chuẩn bị trong một lần GET danh sách (gồm xác thực, hằng số giữa hai lần đo). */
    private long statementsOfOneList() {
        statistics.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = http.exchange(URI.create(http.getRootUri() + "/api/care-tasks?size=100"),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return statistics.getPrepareStatementCount();
    }

    @Test
    void theListRunsTheSameNumberOfStatementsForOneTaskAndForTwenty() {
        addVaccineTasks(1);
        statementsOfOneList(); // làm nóng: lần gọi đầu có thể nạp cấu hình / phiên
        long forOne = statementsOfOneList();

        addVaccineTasks(9);
        addFollowUpTasks(10);
        long forTwenty = statementsOfOneList();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM care_tasks WHERE branch_id = ?", Long.class, branch))
                .isEqualTo(20);
        assertThat(forTwenty).as("số câu SQL không phụ thuộc số dòng (không N+1)").isEqualTo(forOne);
    }
}
