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
import com.petcare.module.workforce.entity.ShiftRequest;
import com.petcare.module.workforce.entity.ShiftRequestStatus;
import com.petcare.module.workforce.entity.ShiftRequestType;
import com.petcare.module.workforce.fsm.ShiftRequestTransitionHandler;
import com.petcare.module.workforce.repository.ShiftRequestRepository;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.support.MutableClock;

/**
 * FSM #11 "Yêu cầu nghỉ / đổi ca" trên bean thật: mọi cạnh hợp lệ của bảng 03 đi qua handler của context rồi method
 * entity, và DB chấp nhận dòng kết quả ({@code ck_shift_requests_closed_at}, {@code _rejected_note},
 * {@code _approved_by}, {@code _swap_target} của V36); mọi cặp không hợp lệ bị handler từ chối và dòng không đổi. Bảng
 * này <b>không</b> có cạnh X → X. Không đi qua endpoint vì chưa có (T59).
 * <p>
 * Era <b>năm 2043</b> (không job → era tương lai, docs/architecture/testing-notes.md).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRequestFsmIT.TestBeans.class})
class ShiftRequestFsmIT {

    private static final Instant NOW = Instant.parse("2043-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2043, 3, 20);

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
    private ShiftRequestRepository requests;
    @Autowired
    private ShiftRequestTransitionHandler handler;
    @Autowired
    private MutableClock clock;

    private WorkforceItSupport data;
    private TransactionTemplate tx;
    private long staff;
    private long manager;
    private long assignment;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        data = new WorkforceItSupport(jdbc);
        tx = new TransactionTemplate(transactionManager);
        long branch = data.branch();
        staff = data.staff("RECEPTIONIST", branch);
        manager = data.staff("BRANCH_MANAGER", branch);
        long morning = data.definition(branch, "Sáng", LocalTime.of(8, 0), LocalTime.of(12, 0), true);
        assignment = data.assignment(branch, staff, morning, DAY, "SCHEDULED", "SELF", null);
    }

    @AfterEach
    void tearDown() {
        data.cleanUp();
    }

    private static Stream<Arguments> validEdges() {
        return Stream.of(
                Arguments.of("#2 rút yêu cầu", ShiftRequestStatus.WITHDRAWN),
                Arguments.of("#3 duyệt", ShiftRequestStatus.APPROVED),
                Arguments.of("#4 từ chối", ShiftRequestStatus.REJECTED),
                Arguments.of("#5 phân ca không còn hiệu lực (hệ thống)", ShiftRequestStatus.REJECTED));
    }

    /** Thao tác entity của từng cạnh; đọc id nhân viên lúc chạy vì {@code @MethodSource} chạy trước {@code setUp}. */
    private void apply(String edge, ShiftRequest request) {
        Instant now = clock.instant();
        switch (edge.substring(0, 2)) {
            case "#2" -> request.withdraw(now);
            case "#3" -> request.approve(manager, now);
            case "#4" -> request.reject(manager, "Thiếu người", now);
            case "#5" -> request.rejectBySystem("Phân ca không còn hiệu lực", now);
            default -> throw new IllegalArgumentException(edge);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validEdges")
    void validEdgeIsAcceptedByHandlerAndByDatabase(String edge, ShiftRequestStatus to) {
        long id = data.leaveRequest(assignment, staff, "PENDING", null);

        tx.executeWithoutResult(status -> {
            ShiftRequest loaded = requests.findByIdForUpdate(id).orElseThrow();
            handler.validateTransition(loaded.getStatus(), to);
            apply(edge, loaded);
            requests.saveAndFlush(loaded);
        });

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM shift_requests WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo(to.name());
        assertThat(row.get("closed_at")).isNotNull();
        assertThat(row.get("request_type")).isEqualTo("LEAVE");
        assertThat(row.get("requester_id")).isEqualTo(staff);
        assertThat(row.get("reason")).isEqualTo("Việc gia đình");
        switch (edge.substring(0, 2)) {
            case "#2" -> {
                assertThat(row.get("decided_by")).isNull();
                assertThat(row.get("decision_note")).isNull();
            }
            case "#3" -> assertThat(row.get("decided_by")).isEqualTo(manager);
            case "#4" -> {
                assertThat(row.get("decided_by")).isEqualTo(manager);
                assertThat(row.get("decision_note")).isEqualTo("Thiếu người");
            }
            default -> {
                assertThat(row.get("decided_by")).isNull();
                assertThat(row.get("decision_note")).isEqualTo("Phân ca không còn hiệu lực");
            }
        }
    }

    @ParameterizedTest(name = "{0} → {1} bị từ chối")
    @MethodSource("invalidPairs")
    void invalidPairIsRejectedAndRowIsUntouched(ShiftRequestStatus from, ShiftRequestStatus to) {
        long id = data.leaveRequest(assignment, staff, from.name(), manager);
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM shift_requests WHERE id = ?", id);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            ShiftRequest loaded = requests.findByIdForUpdate(id).orElseThrow();
            handler.validateTransition(loaded.getStatus(), to);
        })).isInstanceOf(InvalidStateTransitionException.class);

        assertThat(jdbc.queryForMap("SELECT * FROM shift_requests WHERE id = ?", id)).isEqualTo(before);
    }

    /** Mọi cặp xuất phát từ trạng thái cuối, cộng cạnh PENDING → PENDING (bảng #11 không có X → X). */
    private static Stream<Arguments> invalidPairs() {
        return Stream.of(ShiftRequestStatus.values())
                .filter(from -> from != ShiftRequestStatus.PENDING)
                .flatMap(from -> Stream.of(ShiftRequestStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @Test
    void pendingToPendingIsRejectedBecauseTheTableHasNoSelfEdge() {
        assertThatThrownBy(() -> handler.validateTransition(ShiftRequestStatus.PENDING, ShiftRequestStatus.PENDING))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void submitInsertsRowsThatDatabaseAcceptsForLeaveAndSwap() {
        long evening = data.definition(branchOfAssignment(), "Tối", LocalTime.of(14, 0), LocalTime.of(19, 0), true);
        handler.validateInitial(ShiftRequestStatus.PENDING);

        Long leave = tx.execute(status -> requests.saveAndFlush(
                ShiftRequest.submit(assignment, staff, currentShift(), ShiftRequestType.LEAVE, null, "Việc nhà"))
                .getId());
        // Yêu cầu SWAP thứ hai cho cùng phân ca bị unique PENDING chặn, nên rút yêu cầu nghỉ trước.
        jdbc.update("UPDATE shift_requests SET status = 'WITHDRAWN', closed_at = now() WHERE id = ?", leave);
        Long swap = tx.execute(status -> requests.saveAndFlush(ShiftRequest.submit(assignment, staff, currentShift(),
                ShiftRequestType.SWAP, evening, "Đi học buổi sáng")).getId());

        assertThat(jdbc.queryForObject("SELECT request_type FROM shift_requests WHERE id = ?", String.class, leave))
                .isEqualTo("LEAVE");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM shift_requests WHERE id = ?", swap);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("request_type")).isEqualTo("SWAP");
        assertThat(row.get("target_shift_definition_id")).isEqualTo(evening);
        assertThat(row.get("closed_at")).isNull();
    }

    @Test
    void lockMethodRequiresAnOpenTransaction() {
        long id = data.leaveRequest(assignment, staff, "PENDING", null);

        assertThatThrownBy(() -> requests.findByIdForUpdate(id)).isInstanceOf(IllegalTransactionStateException.class);
    }

    private long branchOfAssignment() {
        return jdbc.queryForObject("SELECT branch_id FROM shift_assignments WHERE id = ?", Long.class, assignment);
    }

    private long currentShift() {
        return jdbc.queryForObject("SELECT shift_definition_id FROM shift_assignments WHERE id = ?", Long.class,
                assignment);
    }
}
