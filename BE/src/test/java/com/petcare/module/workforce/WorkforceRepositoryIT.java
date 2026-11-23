package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.entity.ShiftDefinition;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.module.workforce.repository.ShiftDefinitionRepository;
import com.petcare.support.MutableClock;

/**
 * Truy vấn của repository workforce (docs/adr/0149): tên ca trùng không phân biệt hoa thường, đếm phân ca
 * {@code SCHEDULED} từ hôm nay, "có phân ca bất kỳ", danh sách ca theo giờ, và entity {@code ShiftDefinition} ghi / đọc
 * đúng cột (lần đầu {@code ddl-auto=validate} kiểm bảng V36 + V61 với entity). Era <b>năm 2044</b>.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, WorkforceRepositoryIT.TestBeans.class})
class WorkforceRepositoryIT {

    private static final Instant NOW = Instant.parse("2044-03-14T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2044, 3, 14);

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ShiftDefinitionRepository definitions;
    @Autowired
    private ShiftAssignmentRepository assignments;
    @Autowired
    private MutableClock clock;

    private WorkforceItSupport data;
    private TransactionTemplate tx;
    private long branch;
    private long otherBranch;
    private long staff;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        data = new WorkforceItSupport(jdbc);
        tx = new TransactionTemplate(transactionManager);
        branch = data.branch();
        otherBranch = data.branch();
        staff = data.staff("RECEPTIONIST", branch);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    private ShiftDefinition save(long branchId, String name, LocalTime start, LocalTime end) {
        return tx.execute(status -> definitions.saveAndFlush(ShiftDefinition.create(branchId, name, start, end)));
    }

    @Test
    void entityRoundTripsThroughTheV36Table() {
        ShiftDefinition saved = save(branch, "Ca sáng", LocalTime.of(8, 0), LocalTime.of(12, 0));

        ShiftDefinition loaded = definitions.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getBranchId()).isEqualTo(branch);
        assertThat(loaded.getName()).isEqualTo("Ca sáng");
        assertThat(loaded.getStartTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(loaded.getEndTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(loaded.isActive()).isTrue();
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(loaded.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void nameExistsIgnoresCaseAndHonoursBranchAndExclusion() {
        ShiftDefinition saved = save(branch, "Ca Sáng", LocalTime.of(8, 0), LocalTime.of(12, 0));

        assertThat(definitions.existsByBranchAndName(branch, "ca sáng", null)).isTrue();
        assertThat(definitions.existsByBranchAndName(branch, "CA SÁNG", null)).isTrue();
        assertThat(definitions.existsByBranchAndName(branch, "Ca chiều", null)).isFalse();
        assertThat(definitions.existsByBranchAndName(otherBranch, "Ca Sáng", null)).isFalse();
        assertThat(definitions.existsByBranchAndName(branch, "ca sáng", saved.getId())).isFalse();
    }

    @Test
    void definitionsAreListedByStartTimeAndActiveOnlyFiltersStoppedOnes() {
        ShiftDefinition evening = save(branch, "Tối", LocalTime.of(14, 0), LocalTime.of(19, 0));
        ShiftDefinition morning = save(branch, "Sáng", LocalTime.of(8, 0), LocalTime.of(12, 0));
        ShiftDefinition stopped = save(branch, "Cũ", LocalTime.of(6, 0), LocalTime.of(7, 0));
        tx.executeWithoutResult(status -> definitions.findByIdForUpdate(stopped.getId()).orElseThrow().deactivate());
        save(otherBranch, "Khác chi nhánh", LocalTime.of(5, 0), LocalTime.of(6, 0));

        assertThat(definitions.findByBranch(branch, false)).extracting(ShiftDefinition::getId)
                .containsExactly(stopped.getId(), morning.getId(), evening.getId());
        assertThat(definitions.findByBranch(branch, true)).extracting(ShiftDefinition::getId)
                .containsExactly(morning.getId(), evening.getId());
    }

    @Test
    void scheduledCountStartsFromGivenDateAndIgnoresOtherStatuses() {
        long definition = data.definition(branch, "Sáng", LocalTime.of(8, 0), LocalTime.of(12, 0), true);
        data.assignment(branch, staff, definition, TODAY.minusDays(1), "SCHEDULED", "SELF", null);
        data.assignment(branch, staff, definition, TODAY, "SCHEDULED", "SELF", null);
        data.assignment(branch, staff, definition, TODAY.plusDays(1), "SCHEDULED", "MANAGER", null);
        data.assignment(branch, staff, definition, TODAY.plusDays(2), "CANCELLED", "SELF", staff);
        data.assignment(branch, staff, definition, TODAY.plusDays(3), "ON_LEAVE", "SELF", staff);

        assertThat(assignments.countScheduledFrom(definition, TODAY)).isEqualTo(2);
        assertThat(assignments.countScheduledFrom(definition, TODAY.plusDays(2))).isZero();
    }

    @Test
    void scheduledInWeekIsTheStaffsOwnScheduledRowsInclusiveOfBothEndsSortedAndOfAnyBranch() {
        long morning = data.definition(branch, "Sáng", LocalTime.of(8, 0), LocalTime.of(12, 0), true);
        long afternoon = data.definition(branch, "Chiều", LocalTime.of(13, 0), LocalTime.of(17, 0), true);
        long elsewhereDefinition = data.definition(otherBranch, "Khác", LocalTime.of(8, 0), LocalTime.of(12, 0), true);
        long colleague = data.staff("VET", branch);
        long late = data.assignment(branch, staff, afternoon, TODAY, "SCHEDULED", "SELF", null);
        long early = data.assignment(branch, staff, morning, TODAY, "SCHEDULED", "MANAGER", null);
        long elsewhere = data.assignment(otherBranch, staff, elsewhereDefinition, TODAY.plusDays(3), "SCHEDULED",
                "MANAGER", null);
        long last = data.assignment(branch, staff, morning, TODAY.plusDays(6), "SCHEDULED", "SELF", null);
        data.assignment(branch, staff, morning, TODAY.minusDays(1), "SCHEDULED", "SELF", null);
        data.assignment(branch, staff, morning, TODAY.plusDays(7), "SCHEDULED", "SELF", null);
        data.assignment(branch, staff, morning, TODAY.plusDays(1), "ON_LEAVE", "SELF", staff);
        data.assignment(branch, staff, morning, TODAY.plusDays(2), "CANCELLED", "SELF", staff);
        data.assignment(branch, staff, morning, TODAY.plusDays(4), "ABSENT", "MANAGER", staff);
        data.assignment(branch, colleague, morning, TODAY, "SCHEDULED", "SELF", null);

        assertThat(assignments.findScheduledInWeek(staff, TODAY, TODAY.plusDays(6)))
                .extracting(ShiftAssignment::getId).containsExactly(early, late, elsewhere, last);
        assertThat(assignments.findScheduledInWeek(staff, TODAY.plusDays(100), TODAY.plusDays(106))).isEmpty();
    }

    @Test
    void countScheduledInWeekGroupsByStaffCountsBothSourcesAndBranchesIncludesBothEndsAndSkipsStaffWithoutRows() {
        long morning = data.definition(branch, "Sáng", LocalTime.of(8, 0), LocalTime.of(12, 0), true);
        long afternoon = data.definition(branch, "Chiều", LocalTime.of(13, 0), LocalTime.of(17, 0), true);
        long elsewhereDefinition = data.definition(otherBranch, "Khác", LocalTime.of(8, 0), LocalTime.of(12, 0), true);
        long colleague = data.staff("VET", branch);
        long idle = data.staff("CARETAKER", branch);
        long outsider = data.staff("VET", otherBranch);
        data.assignment(branch, staff, morning, TODAY, "SCHEDULED", "SELF", null); // đầu tuần
        data.assignment(branch, staff, afternoon, TODAY, "SCHEDULED", "MANAGER", null);
        data.assignment(otherBranch, staff, elsewhereDefinition, TODAY.plusDays(3), "SCHEDULED", "MANAGER", null);
        data.assignment(branch, staff, morning, TODAY.plusDays(6), "SCHEDULED", "SELF", null); // cuối tuần
        data.assignment(branch, staff, morning, TODAY.minusDays(1), "SCHEDULED", "SELF", null);
        data.assignment(branch, staff, morning, TODAY.plusDays(7), "SCHEDULED", "SELF", null);
        data.assignment(branch, staff, morning, TODAY.plusDays(1), "ON_LEAVE", "SELF", staff);
        data.assignment(branch, staff, morning, TODAY.plusDays(2), "CANCELLED", "SELF", staff);
        data.assignment(branch, staff, morning, TODAY.plusDays(4), "ABSENT", "MANAGER", staff);
        data.assignment(branch, colleague, morning, TODAY, "SCHEDULED", "SELF", null);
        data.assignment(otherBranch, outsider, elsewhereDefinition, TODAY, "SCHEDULED", "SELF", null);

        Map<Long, Long> counts = new HashMap<>();
        tx.executeWithoutResult(status -> assignments
                .countScheduledInWeek(List.of(staff, colleague, idle), TODAY, TODAY.plusDays(6))
                .forEach(row -> counts.put(row.getStaffId(), row.getShiftCount())));

        assertThat(counts).containsOnly(Map.entry(staff, 4L), Map.entry(colleague, 1L));
        assertThat(counts).as("không dòng cho người 0 ca và người ngoài tập truyền vào").doesNotContainKeys(idle, outsider);
        assertThat(assignments.countScheduledInWeek(List.of(staff), TODAY.plusDays(100), TODAY.plusDays(106))).isEmpty();
    }

    @Test
    void anyAssignmentIncludesFinishedAndPastOnesButOnlyForThatDefinition() {
        long used = data.definition(branch, "Đã dùng", LocalTime.of(8, 0), LocalTime.of(12, 0), true);
        long unused = data.definition(branch, "Chưa dùng", LocalTime.of(14, 0), LocalTime.of(19, 0), true);
        data.assignment(branch, staff, used, TODAY.minusDays(30), "CANCELLED", "SELF", staff);

        assertThat(assignments.existsAnyForDefinition(branch, used)).isTrue();
        assertThat(assignments.existsAnyForDefinition(branch, unused)).isFalse();
    }

    @Test
    void definitionsOfOneBranchAreNotReturnedForAnother() {
        save(branch, "Sáng", LocalTime.of(8, 0), LocalTime.of(12, 0));

        List<ShiftDefinition> other = definitions.findByBranch(otherBranch, false);

        assertThat(other).isEmpty();
    }

    @Test
    void lockScheduledInWeekReturnsOnlyTheStaffsScheduledRowsOfThatWeekByIdAndLocksExactlyThem() {
        long morning = data.definition(branch, "Sáng", LocalTime.of(8, 0), LocalTime.of(12, 0), true);
        long colleague = data.staff("VET", branch);
        long mine1 = data.assignment(branch, staff, morning, TODAY.plusDays(2), "SCHEDULED", "SELF", null);
        long mine2 = data.assignment(branch, staff, morning, TODAY, "SCHEDULED", "MANAGER", null);
        long onLeave = data.assignment(branch, staff, morning, TODAY.plusDays(1), "ON_LEAVE", "SELF", staff);
        long nextWeek = data.assignment(branch, staff, morning, TODAY.plusDays(7), "SCHEDULED", "SELF", null);
        long theirs = data.assignment(branch, colleague, morning, TODAY, "SCHEDULED", "SELF", null);

        List<Long> locked = tx.execute(status -> {
            List<Long> ids = assignments.lockScheduledInWeek(staff, TODAY, TODAY.plusDays(6)).stream()
                    .map(ShiftAssignment::getId).toList();
            // Trong cùng transaction các dòng này đã bị khóa: một kết nối khác không lấy được NOWAIT.
            assertThat(canLockNowaitElsewhere(mine1)).as("dòng trong tuần bị khóa").isFalse();
            assertThat(canLockNowaitElsewhere(mine2)).isFalse();
            assertThat(canLockNowaitElsewhere(onLeave)).as("không phải SCHEDULED nên không khóa").isTrue();
            assertThat(canLockNowaitElsewhere(nextWeek)).as("tuần khác không khóa").isTrue();
            assertThat(canLockNowaitElsewhere(theirs)).as("người khác không khóa").isTrue();
            return ids;
        });

        assertThat(locked).as("tăng dần theo id").containsExactly(Math.min(mine1, mine2), Math.max(mine1, mine2));
    }

    @Test
    void lockForShareInBranchLocksOnlyTheBranchsShiftsByIdAndSkipsOtherBranchesAndUnknownIds() {
        long first = data.definition(branch, "Sáng", LocalTime.of(8, 0), LocalTime.of(12, 0), true);
        long second = data.definition(branch, "Chiều", LocalTime.of(13, 0), LocalTime.of(17, 0), false);
        long elsewhere = data.definition(otherBranch, "Khác", LocalTime.of(8, 0), LocalTime.of(12, 0), true);

        List<Long> locked = tx.execute(status -> {
            List<Long> ids = definitions.lockForShareInBranch(java.util.Set.of(second, first, elsewhere, 987_654_321L),
                    branch).stream().map(ShiftDefinition::getId).toList();
            // FOR SHARE: chặn FOR NO KEY UPDATE (PATCH ca) nhưng không chặn một FOR SHARE khác.
            assertThat(canLockNowaitElsewhere("shift_definitions", first)).as("ca trong lô bị khóa chia sẻ").isFalse();
            assertThat(canLockNowaitElsewhere("shift_definitions", elsewhere)).as("ca chi nhánh khác không khóa").isTrue();
            return ids;
        });

        assertThat(locked).containsExactly(Math.min(first, second), Math.max(first, second));
        assertThat(locked).doesNotContain(elsewhere);
        assertThat(definitions.findById(second).orElseThrow().isActive()).as("đọc được cờ is_active dưới khóa").isFalse();
    }

    /** Một kết nối khác (autocommit) thử khóa {@code FOR NO KEY UPDATE NOWAIT}: {@code false} khi dòng đang bị giữ. */
    private boolean canLockNowaitElsewhere(long assignmentId) {
        return canLockNowaitElsewhere("shift_assignments", assignmentId);
    }

    private boolean canLockNowaitElsewhere(String table, long id) {
        try {
            new TransactionTemplate(transactionManager, newTransaction()).execute(status -> jdbc.queryForObject(
                    "SELECT id FROM " + table + " WHERE id = ? FOR NO KEY UPDATE NOWAIT", Long.class, id));
            return true;
        } catch (org.springframework.dao.DataAccessException e) {
            return false;
        }
    }

    private static org.springframework.transaction.support.DefaultTransactionDefinition newTransaction() {
        org.springframework.transaction.support.DefaultTransactionDefinition definition =
                new org.springframework.transaction.support.DefaultTransactionDefinition();
        definition.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return definition;
    }
}
