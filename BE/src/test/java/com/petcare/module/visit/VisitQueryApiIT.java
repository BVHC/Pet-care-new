package com.petcare.module.visit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.api.VisitQueryApi.AssignedVisit;
import com.petcare.module.visit.api.VisitQueryApi.VisitStatus;
import com.petcare.module.visit.api.VisitQueryApi.VisitSummary;
import com.petcare.module.visit.service.VisitQueryService;

/**
 * {@code VisitQueryApi} bản thật (docs/adr/0058; trả D016 phía visit, D028) trên Postgres 17. Dữ liệu chèn bằng SQL với
 * FK thật (chi nhánh, khách, thú, nhân viên riêng của mỗi test, tên UUID), xóa ở {@link AppointmentItSupport#cleanUp()}.
 * {@code hasOpenVisit} / {@code existsByPet} / {@code findVisitIdsByPet} / {@code hasMedicalHistory} còn được
 * {@code CrossModuleQueryIT} (customer) kiểm trên cùng bean.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class VisitQueryApiIT {

    private static final LocalDate DAY = LocalDate.of(2031, 5, 12);

    @Autowired
    private VisitQueryApi visits;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private AppointmentItSupport data;
    private TransactionTemplate tx;
    private long branch;
    private long otherBranch;
    private long medical;
    private long receptionist;
    private long vet;
    private long otherVet;
    private long customer;
    private long pet;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        otherBranch = data.branch("ACTIVE");
        medical = data.service("MEDICAL");
        receptionist = data.staff("RECEPTIONIST", branch);
        vet = data.staff("VET", branch);
        otherVet = data.staff("VET", branch);
        customer = data.counterCustomer();
        pet = data.pet(customer);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    @Test
    void theRealBeanReplacesThePlaceholder() {
        assertThat(visits).isInstanceOf(VisitQueryService.class);
    }

    @Test
    void unfinishedVisitsOfOneStaffAreWaitingAndInProgressInIdOrder() {
        long inProgress = visit(branch, "IN_PROGRESS", vet, null);
        visit(branch, "COMPLETED", vet, null);
        visit(branch, "CANCELLED", vet, null);
        long waiting = visit(otherBranch, "WAITING", vet, null);
        visit(branch, "WAITING", otherVet, null);
        visit(branch, "WAITING", null, null);

        assertThat(visits.findUnfinishedVisitsAssignedTo(vet)).containsExactly(
                new AssignedVisit(inProgress, branch, VisitStatus.IN_PROGRESS),
                new AssignedVisit(waiting, otherBranch, VisitStatus.WAITING));
        assertThat(visits.findUnfinishedVisitsAssignedTo(receptionist)).isEmpty();
    }

    @Test
    void findVisitReadsTheRowOrNothing() {
        long id = visit(branch, "IN_PROGRESS", vet, null);

        assertThat(visits.findVisit(id)).contains(
                new VisitSummary(id, branch, customer, pet, ServiceGroup.MEDICAL, VisitStatus.IN_PROGRESS, vet));
        assertThat(visits.findVisit(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void petQueriesFollowTheStatus() {
        assertThat(visits.hasOpenVisit(pet)).isFalse();
        assertThat(visits.existsByPet(pet)).isFalse();

        long done = visit(branch, "COMPLETED", vet, null);
        assertThat(visits.hasOpenVisit(pet)).isFalse();
        assertThat(visits.existsByPet(pet)).isTrue();

        long waiting = visit(branch, "WAITING", null, null);
        assertThat(visits.hasOpenVisit(pet)).isTrue();
        assertThat(visits.findVisitIdsByPet(pet)).containsExactly(done, waiting);
        assertThat(visits.hasOpenVisit(data.pet(customer))).as("thú khác").isFalse();
    }

    @Test
    void visitIdsByAppointmentKeepOnlyAppointmentsThatHaveAVisit() {
        long a = appointment(LocalTime.of(8, 0), "CHECKED_IN");
        long b = appointment(LocalTime.of(8, 30), "BOOKED");
        long c = appointment(LocalTime.of(9, 0), "CANCELLED");
        long visitA = visit(branch, "WAITING", null, a);
        long visitC = visit(branch, "CANCELLED", null, c);
        visit(branch, "WAITING", null, null);

        assertThat(visits.findVisitIdsByAppointmentIds(List.of(a, b, c, Long.MAX_VALUE)))
                .containsOnly(entry(a, visitA), entry(c, visitC));
        assertThat(visits.findVisitIdsByAppointmentIds(Set.of())).isEmpty();
    }

    /**
     * Class {@code readOnly} tham gia transaction ghi của bên gọi (identity dưới khóa tài khoản): thấy dòng bên gọi vừa
     * ghi, chưa commit. Mutation {@code REQUIRES_NEW} / {@code NOT_SUPPORTED} → không thấy → đỏ.
     */
    @Test
    void lookupJoinsCallerWriteTransaction() {
        List<AssignedVisit> seen = tx.execute(status -> {
            long id = visit(branch, "WAITING", vet, null);
            status.setRollbackOnly();
            List<AssignedVisit> found = visits.findUnfinishedVisitsAssignedTo(vet);
            assertThat(found).extracting(AssignedVisit::visitId).containsExactly(id);
            return found;
        });

        assertThat(seen).hasSize(1);
        assertThat(visits.findUnfinishedVisitsAssignedTo(vet)).as("đã rollback").isEmpty();
    }

    private long appointment(LocalTime start, String status) {
        long id = data.appointment(customer, pet, branch, medical, "MEDICAL", DAY, start, status, receptionist);
        if ("CANCELLED".equals(status)) {
            jdbc.update("UPDATE appointments SET cancel_source = 'STAFF', cancelled_at = now() WHERE id = ?", id);
        }
        return id;
    }

    /** Một Visit với FK thật; xóa theo thú ở {@link AppointmentItSupport#cleanUp()}. */
    private long visit(long branchId, String status, Long assignee, Long appointmentId) {
        return jdbc.queryForObject("""
                INSERT INTO visits (code, branch_id, customer_id, pet_id, appointment_id, service_group, status,
                        priority_class, queue_sort_key, assignee_id, checked_in_by, checked_in_at)
                VALUES (?, ?, ?, ?, ?, 'MEDICAL', ?, 'WALK_IN', now(), ?, ?, now()) RETURNING id""", Long.class,
                "TN-IT-" + UUID.randomUUID().toString().substring(0, 8), branchId, customer, pet, appointmentId, status,
                assignee, receptionist);
    }
}
