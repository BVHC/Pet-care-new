package com.petcare.module.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.api.AppointmentApi;
import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.platform.config.TimeConfig;

import jakarta.persistence.EntityManagerFactory;

/**
 * Số câu SQL của {@code AppointmentQueryApi} / {@code AppointmentApi} (docs/adr/0050, không N+1): mỗi query đúng 1
 * câu với 1 dòng và với 50 dòng (cùng con số ⇒ không phụ thuộc số dòng); mỗi lời gọi ghi đúng 2 câu
 * ({@code SELECT … FOR NO KEY UPDATE} + {@code UPDATE}). Context riêng bật Hibernate Statistics.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AppointmentQueryCountIT {

    private static final LocalDate DAY = LocalDate.of(2031, 4, 7);

    @Autowired
    private AppointmentQueryApi query;
    @Autowired
    private AppointmentApi api;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private Statistics statistics;
    private long branch;
    private long medical;
    private long staff;
    private long customer;
    private long pet;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled()).as("generate_statistics").isTrue();
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        medical = data.service("MEDICAL");
        staff = data.staff("RECEPTIONIST", branch);
        customer = data.counterCustomer();
        pet = data.pet(customer);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    private void insertBooked(int from, int rows) {
        for (int i = from; i < from + rows; i++) {
            data.appointment(customer, pet, branch, medical, "MEDICAL", DAY.plusDays(i), LocalTime.of(9, 0),
                    "BOOKED", staff);
        }
    }

    /** Số câu SQL Hibernate chuẩn bị trong một transaction ghi chạy {@code work}. */
    private long statements(Supplier<?> work) {
        return tx.execute(s -> {
            statistics.clear();
            work.get();
            return statistics.getPrepareStatementCount();
        });
    }

    private long allQueryStatements() {
        return statements(() -> {
            query.findBookedFrom(branch, DAY);
            return null;
        });
    }

    @Test
    void findBookedFromIsOneStatementForOneRowAndFiftyRows() {
        insertBooked(0, 1);
        assertThat(allQueryStatements()).isEqualTo(1);

        insertBooked(1, 49);
        assertThat(query.findBookedFrom(branch, DAY)).hasSize(50);
        assertThat(allQueryStatements()).isEqualTo(1);
    }

    @Test
    void findBookedByCustomerIsOneStatementForOneRowAndFiftyRows() {
        insertBooked(0, 1);
        assertThat(statements(() -> query.findBookedByCustomer(customer))).isEqualTo(1);

        insertBooked(1, 49);
        assertThat(query.findBookedByCustomer(customer)).hasSize(50);
        assertThat(statements(() -> query.findBookedByCustomer(customer))).isEqualTo(1);
    }

    @Test
    void existsQueriesAreOneStatementWhateverTheRowCount() {
        assertThat(statements(() -> query.existsByPet(pet))).isEqualTo(1);
        assertThat(statements(() -> query.existsByCustomer(customer))).isEqualTo(1);

        insertBooked(0, 50);
        assertThat(statements(() -> query.existsByPet(pet))).isEqualTo(1);
        assertThat(statements(() -> query.existsByCustomer(customer))).isEqualTo(1);
        assertThat(query.existsByPet(pet)).isTrue();
    }

    @Test
    void visitCallsAreExactlyTwoStatements() {
        long a = data.appointment(customer, pet, branch, medical, "MEDICAL", DAY, LocalTime.of(9, 0), "BOOKED", staff);
        Instant at = ZonedDateTime.of(LocalDateTime.of(DAY, LocalTime.of(9, 0)), TimeConfig.BUSINESS_ZONE).toInstant();

        assertThat(statements(() -> api.checkIn(a, branch, at))).isEqualTo(2);
        assertThat(statements(() -> {
            api.completeByVisit(a);
            return null;
        })).isEqualTo(2);

        long b = data.appointment(customer, data.pet(customer), branch, medical, "MEDICAL", DAY.plusDays(1),
                LocalTime.of(9, 0), "CHECKED_IN", staff);
        assertThat(statements(() -> {
            api.cancelByVisit(b, at);
            return null;
        })).isEqualTo(2);
    }
}
