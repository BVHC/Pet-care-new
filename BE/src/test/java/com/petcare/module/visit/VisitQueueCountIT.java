package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.util.List;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.visit.dto.VisitQueueFilter;
import com.petcare.module.visit.service.VisitQueueService;
import com.petcare.platform.security.AccessScope;
import com.petcare.platform.security.SecurityPrincipal;

import jakarta.persistence.EntityManagerFactory;

/**
 * Số câu SQL của hàng đợi (docs/adr/0060 mục 9, không N+1; khuôn {@code VisitQueryCountIT}): một câu {@code visits} +
 * mỗi module một câu cho cả trang, bất kể 1 hay 50 lượt với 50 thú / khách khác nhau; danh sách rỗng đúng 1 câu.
 * Mutation "tra từng id" → 50 câu → đỏ. Gọi service trực tiếp (principal đặt tay) để không đếm câu của xác thực.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class VisitQueueCountIT {

    /**
     * visits + orders + pets (2 câu cho cả lô: thú + cân nặng mới nhất, {@code PetQueryService.findPets}) + customers +
     * staff_profiles⋈accounts.
     */
    private static final long PAGE_WITH_ASSIGNEE = 6;

    @Autowired
    private VisitQueueService queue;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private JdbcTemplate jdbc;

    private AppointmentItSupport data;
    private Statistics statistics;
    private long branch;
    private long receptionist;
    private long vet;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled()).as("generate_statistics").isTrue();
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        receptionist = data.staff("RECEPTIONIST", branch);
        vet = data.staff("VET", branch);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM orders WHERE visit_id IN (SELECT id FROM visits WHERE branch_id = ?)", branch);
        jdbc.update("DELETE FROM visits WHERE branch_id = ?", branch);
        data.cleanUp();
    }

    @Test
    void queueAndMyQueueAreOneStatementPerModuleForOneAndFiftyVisits() {
        long first = visits(1).get(0);
        signIn(receptionist, "RECEPTIONIST");
        assertThat(statements(() -> queue.listQueue(new VisitQueueFilter(null, null, null, null, null))))
                .isEqualTo(PAGE_WITH_ASSIGNEE);
        assertThat(statements(() -> queue.getVisit(first))).isEqualTo(PAGE_WITH_ASSIGNEE);

        visits(49);
        assertThat(queue.listQueue(new VisitQueueFilter(null, null, null, null, null))).hasSize(50);
        assertThat(statements(() -> queue.listQueue(new VisitQueueFilter(null, null, null, null, null))))
                .isEqualTo(PAGE_WITH_ASSIGNEE);

        signIn(vet, "VET");
        assertThat(queue.listMyQueue()).hasSize(50);
        assertThat(statements(() -> queue.listMyQueue())).isEqualTo(PAGE_WITH_ASSIGNEE);
    }

    @Test
    void emptyQueueIsOneStatement() {
        signIn(receptionist, "RECEPTIONIST");

        assertThat(statements(() -> queue.listQueue(new VisitQueueFilter(null, null, null, null, null)))).isEqualTo(1);
    }

    private long statements(Supplier<?> work) {
        statistics.clear();
        work.get();
        return statistics.getPrepareStatementCount();
    }

    /** {@code count} lượt IN_PROGRESS gán cho VET, mỗi lượt một khách / thú riêng, kèm Order VISIT. */
    private List<Long> visits(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> {
            long customer = data.counterCustomer();
            long pet = data.pet(customer);
            long id = jdbc.queryForObject("""
                    INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                            queue_sort_key, assignee_id, checked_in_by, checked_in_at, called_at)
                    VALUES (?, ?, ?, ?, 'MEDICAL', 'IN_PROGRESS', 'WALK_IN', now(), ?, ?, now(), now()) RETURNING id""",
                    Long.class, "QC-" + UUID.randomUUID().toString().substring(0, 12), branch, customer, pet, vet,
                    receptionist);
            jdbc.update("""
                    INSERT INTO orders (code, source, customer_id, branch_id, visit_id, status, created_by)
                    VALUES (?, 'VISIT', ?, ?, ?, 'OPEN', ?)""", "OQC-" + UUID.randomUUID().toString().substring(0, 12),
                    customer, branch, id, receptionist);
            return id;
        }).toList();
    }

    private void signIn(long accountId, String role) {
        SecurityPrincipal principal = mock(SecurityPrincipal.class);
        doReturn(accountId).when(principal).accountId();
        doReturn(role).when(principal).role();
        doReturn(branch).when(principal).branchId();
        doReturn(AccessScope.BRANCH).when(principal).accessScope();
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }
}
