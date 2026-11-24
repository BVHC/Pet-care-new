package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.entity.ShiftAssignmentSource;
import com.petcare.module.workforce.entity.ShiftAssignmentStatus;
import com.petcare.module.workforce.fsm.ShiftAssignmentTransitionHandler;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.support.MutableClock;

/**
 * FSM #12 "Phân ca" trên bean thật (không {@code @MockitoBean}): mọi cạnh hợp lệ của bảng 03 đi qua handler của context
 * rồi method entity, và <b>DB chấp nhận</b> dòng kết quả (hai CHECK {@code ck_shift_assignments_status_changed},
 * {@code _reason} của V36 — {@code Tier3Be1SchemaIT} kiểm chúng bằng SQL thô, ở đây kiểm bằng entity); mọi cặp không
 * hợp lệ bị handler từ chối và dòng không đổi. Khác {@code CareTaskFsmIT}: không đi qua endpoint vì chưa có (T59), nên
 * trạng thái nguồn được chèn bằng SQL.
 * <p>
 * Era <b>năm 2042</b>: IT không có job nên lấy era tương lai (docs/architecture/testing-notes.md). Dữ liệu gắn chi
 * nhánh và nhân viên tạo riêng cho từng test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftAssignmentFsmIT.TestBeans.class})
class ShiftAssignmentFsmIT {

    private static final Instant NOW = Instant.parse("2042-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2042, 3, 20);

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
    private ShiftAssignmentRepository assignments;
    @Autowired
    private ShiftAssignmentTransitionHandler handler;
    @Autowired
    private MutableClock clock;

    private WorkforceItSupport data;
    private TransactionTemplate tx;
    private long branch;
    private long staff;
    private long manager;
    private long morning;
    private long evening;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        data = new WorkforceItSupport(jdbc);
        tx = new TransactionTemplate(transactionManager);
        branch = data.branch();
        staff = data.staff("RECEPTIONIST", branch);
        manager = data.staff("BRANCH_MANAGER", branch);
        morning = data.definition(branch, "Sáng", LocalTime.of(8, 0), LocalTime.of(12, 0), true);
        evening = data.definition(branch, "Tối", LocalTime.of(14, 0), LocalTime.of(19, 0), true);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    private static Stream<Arguments> validEdges() {
        return Stream.of(
                Arguments.of("#2 hủy phân ca", ShiftAssignmentStatus.CANCELLED),
                Arguments.of("#6 nhân viên rời chi nhánh (hệ thống)", ShiftAssignmentStatus.CANCELLED),
                Arguments.of("#7 nhân viên sửa đăng ký tuần", ShiftAssignmentStatus.CANCELLED),
                Arguments.of("#3 nghỉ được duyệt", ShiftAssignmentStatus.ON_LEAVE),
                Arguments.of("#4 vắng đột xuất", ShiftAssignmentStatus.ABSENT),
                Arguments.of("#5 đổi ca", ShiftAssignmentStatus.SCHEDULED));
    }

    /** Thao tác entity của từng cạnh; đọc id nhân viên / ca lúc chạy vì {@code @MethodSource} chạy trước {@code setUp}. */
    private void apply(String edge, ShiftAssignment assignment) {
        Instant now = clock.instant();
        switch (edge.substring(0, 2)) {
            case "#2" -> assignment.cancel("Đổi lịch", manager, now);
            case "#6" -> assignment.cancel("Nhân viên rời chi nhánh", null, now);
            case "#7" -> assignment.cancel("Nhân viên sửa đăng ký tuần", staff, now);
            case "#3" -> assignment.markOnLeave("Việc gia đình", manager, now);
            case "#4" -> assignment.markAbsent("Không đến", manager, now);
            case "#5" -> assignment.swapTo(evening);
            default -> throw new IllegalArgumentException(edge);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validEdges")
    void validEdgeIsAcceptedByHandlerAndByDatabase(String edge, ShiftAssignmentStatus to) {
        long id = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);

        tx.executeWithoutResult(status -> {
            ShiftAssignment loaded = assignments.findByIdForUpdate(id).orElseThrow();
            handler.validateTransition(loaded.getStatus(), to);
            apply(edge, loaded);
            assignments.saveAndFlush(loaded);
        });

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM shift_assignments WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo(to.name());
        boolean stillScheduled = to == ShiftAssignmentStatus.SCHEDULED;
        assertThat(row.get("status_changed_at") == null).isEqualTo(stillScheduled);
        assertThat(row.get("source")).isEqualTo("SELF");
        assertThat(row.get("branch_id")).isEqualTo(branch);
        assertThat(row.get("staff_id")).isEqualTo(staff);
        assertThat(row.get("work_date").toString()).isEqualTo(DAY.toString());
        if (edge.startsWith("#5")) {
            assertThat(row.get("shift_definition_id")).isEqualTo(evening);
        }
        if (edge.startsWith("#6")) {
            assertThat(row.get("status_changed_by")).isNull();
        }
    }

    @ParameterizedTest(name = "{0} → {1} bị từ chối")
    @MethodSource("invalidPairs")
    void invalidPairIsRejectedAndRowIsUntouched(ShiftAssignmentStatus from, ShiftAssignmentStatus to) {
        long id = data.assignment(branch, staff, morning, DAY, from.name(), "MANAGER", manager);
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM shift_assignments WHERE id = ?", id);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            ShiftAssignment loaded = assignments.findByIdForUpdate(id).orElseThrow();
            handler.validateTransition(loaded.getStatus(), to);
        })).isInstanceOf(InvalidStateTransitionException.class);

        assertThat(jdbc.queryForMap("SELECT * FROM shift_assignments WHERE id = ?", id)).isEqualTo(before);
    }

    private static Stream<Arguments> invalidPairs() {
        return Stream.of(ShiftAssignmentStatus.values())
                .filter(from -> from != ShiftAssignmentStatus.SCHEDULED)
                .flatMap(from -> Stream.of(ShiftAssignmentStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @Test
    void scheduleInsertsRowThatDatabaseAcceptsForBothSources() {
        for (ShiftAssignmentSource source : ShiftAssignmentSource.values()) {
            LocalDate day = DAY.plusDays(source.ordinal());
            handler.validateInitial(ShiftAssignmentStatus.SCHEDULED);

            Long id = tx.execute(status -> assignments
                    .saveAndFlush(ShiftAssignment.schedule(branch, staff, morning, day, source)).getId());

            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM shift_assignments WHERE id = ?", id);
            assertThat(row.get("status")).isEqualTo("SCHEDULED");
            assertThat(row.get("source")).isEqualTo(source.name());
            assertThat(row.get("status_changed_at")).isNull();
            assertThat(row.get("created_at")).isNotNull();
        }
    }

    @Test
    void lockMethodRequiresAnOpenTransaction() {
        long id = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);

        assertThatThrownBy(() -> assignments.findByIdForUpdate(id))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
