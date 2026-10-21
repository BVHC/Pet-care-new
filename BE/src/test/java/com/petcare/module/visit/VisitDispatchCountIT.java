package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.visit.dto.MoveVisitRequest;
import com.petcare.module.visit.service.VisitDispatchService;
import com.petcare.platform.security.AccessScope;
import com.petcare.platform.security.SecurityPrincipal;

import jakarta.persistence.EntityManagerFactory;

/**
 * Số câu SQL của sắp lại hàng đợi (docs/adr/0061 mục Q, khuôn {@code VisitQueueCountIT}): khi không phải dồn khóa, số câu
 * không đổi dù chi nhánh có 3 hay 40 lượt WAITING — khóa tập một câu, nạp lượt một câu, hàng đợi trả về theo lô
 * (docs/adr/0060). Mutation "khóa / nạp từng lượt" → số câu tăng theo số lượt → đỏ. Gọi service trực tiếp (principal
 * đặt tay) để không đếm câu của xác thực.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class VisitDispatchCountIT {

    @Autowired
    private VisitDispatchService dispatch;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private JdbcTemplate jdbc;

    private AppointmentItSupport data;
    private Statistics statistics;
    private long branch;
    private long receptionist;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled()).as("generate_statistics").isTrue();
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        receptionist = data.staff("RECEPTIONIST", branch);
        signIn(receptionist);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM orders WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)", branch);
        jdbc.update("DELETE FROM visits WHERE branch_id = ?", branch);
        data.cleanUp();
    }

    @Test
    void moveIsAFixedNumberOfStatementsForThreeAndFortyWaitingVisits() {
        List<Long> few = waiting(3, Instant.parse("2007-01-01T02:00:00Z"));
        long small = statements(() -> dispatch.moveVisitInQueue(few.get(2), new MoveVisitRequest(few.get(1))));

        List<Long> many = waiting(37, Instant.parse("2007-01-01T05:00:00Z"));
        long last = many.get(many.size() - 1);
        long large = statements(() -> dispatch.moveVisitInQueue(last, new MoveVisitRequest(many.get(0))));

        assertThat(large).as("40 lượt WAITING, không dồn khóa").isEqualTo(small);
    }

    private long statements(Supplier<?> work) {
        statistics.clear();
        work.get();
        return statistics.getPrepareStatementCount();
    }

    /** {@code count} lượt WAITING walk-in, khóa cách nhau 1 phút từ {@code from}, mỗi lượt một khách / thú, kèm Order. */
    private List<Long> waiting(int count, Instant from) {
        return IntStream.range(0, count).mapToObj(i -> {
            long customer = data.counterCustomer();
            long pet = data.pet(customer);
            long id = jdbc.queryForObject("""
                    INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                            queue_sort_key, checked_in_by, checked_in_at)
                    VALUES (?, ?, ?, ?, 'MEDICAL', 'WAITING', 'WALK_IN', ?, ?, ?) RETURNING id""",
                    Long.class, "DC-" + UUID.randomUUID().toString().substring(0, 12), branch, customer, pet,
                    java.sql.Timestamp.from(from.plusSeconds(60L * i)), receptionist,
                    java.sql.Timestamp.from(from.plusSeconds(60L * i)));
            jdbc.update("""
                    INSERT INTO orders (code, source, customer_id, branch_id, visit_id, status, created_by)
                    VALUES (?, 'VISIT', ?, ?, ?, 'OPEN', ?)""", "ODC-" + UUID.randomUUID().toString().substring(0, 12),
                    customer, branch, id, receptionist);
            return id;
        }).toList();
    }

    private void signIn(long accountId) {
        SecurityPrincipal principal = mock(SecurityPrincipal.class);
        doReturn(accountId).when(principal).accountId();
        doReturn("RECEPTIONIST").when(principal).role();
        doReturn(branch).when(principal).branchId();
        doReturn(AccessScope.BRANCH).when(principal).accessScope();
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }
}
