package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.visit.api.VisitQueryApi;

import jakarta.persistence.EntityManagerFactory;

/**
 * Số câu SQL của {@code VisitQueryApi} (docs/adr/0058, không N+1; khuôn {@code AppointmentQueryCountIT}): query theo lô
 * {@code appointmentId → visitId} đúng 1 câu với 1 và với 50 lịch, 0 câu với tập rỗng; danh sách lượt dở của nhân viên
 * đúng 1 câu với 1 và 50 dòng. Mutation "tra từng id" → 50 câu → đỏ. Context riêng bật Hibernate Statistics.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class VisitQueryCountIT {

    private static final LocalDate DAY = LocalDate.of(2031, 6, 2);

    @Autowired
    private VisitQueryApi visits;
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
    private long vet;
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
        vet = data.staff("VET", branch);
        customer = data.counterCustomer();
        pet = data.pet(customer);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    /** Số câu SQL Hibernate chuẩn bị trong một transaction ghi chạy {@code work}. */
    private long statements(Supplier<?> work) {
        return tx.execute(s -> {
            statistics.clear();
            work.get();
            return statistics.getPrepareStatementCount();
        });
    }

    @Test
    void visitIdsByAppointmentIsOneStatementForOneAndFiftyAppointments() {
        List<Long> ids = checkedInAppointments(0, 1);
        assertThat(statements(() -> visits.findVisitIdsByAppointmentIds(ids))).isEqualTo(1);

        ids.addAll(checkedInAppointments(1, 49));
        assertThat(visits.findVisitIdsByAppointmentIds(ids)).hasSize(50);
        assertThat(statements(() -> visits.findVisitIdsByAppointmentIds(ids))).isEqualTo(1);

        assertThat(statements(() -> visits.findVisitIdsByAppointmentIds(Set.of()))).isZero();
    }

    @Test
    void unfinishedVisitsIsOneStatementForOneAndFiftyRows() {
        assignedVisits(1);
        assertThat(statements(() -> visits.findUnfinishedVisitsAssignedTo(vet))).isEqualTo(1);

        assignedVisits(49);
        assertThat(visits.findUnfinishedVisitsAssignedTo(vet)).hasSize(50);
        assertThat(statements(() -> visits.findUnfinishedVisitsAssignedTo(vet))).isEqualTo(1);
    }

    private List<Long> checkedInAppointments(int from, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = from; i < from + count; i++) {
            long appointment = data.appointment(customer, pet, branch, medical, "MEDICAL", DAY.plusDays(i),
                    LocalTime.of(9, 0), "CHECKED_IN", staff);
            data.visit(appointment, staff);
            ids.add(appointment);
        }
        return ids;
    }

    private void assignedVisits(int count) {
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                    INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                            queue_sort_key, assignee_id, checked_in_by, checked_in_at)
                    VALUES (?, ?, ?, ?, 'MEDICAL', 'IN_PROGRESS', 'WALK_IN', now(), ?, ?, now())""",
                    "TN-IT-" + UUID.randomUUID().toString().substring(0, 8), branch, customer, pet, vet, staff);
        }
    }
}
