package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Lịch làm việc của tôi (workforce-v1 #8; docs/adr/0138 bước 4) trên Postgres thật, gồm cả xác thực / phân quyền:
 * chỉ lịch của chính người gọi (tham số {@code staffId} / {@code branchId} gửi thêm bị bỏ qua), mọi trạng thái và mọi
 * chi nhánh (nhân viên đã điều chuyển vẫn thấy ca cũ), thứ tự, {@code pendingRequestId}, trần 62 ngày hai đầu, đầu vào
 * sai, 401, BR-TK-17, ma trận vai trò (chỉ A06–A08) và vai trò đọc lại từ DB. Era <b>năm 2057</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftAssignmentMyScheduleIT.TestBeans.class})
class ShiftAssignmentMyScheduleIT extends ShiftAssignmentItSupport {

    private static final Instant NOW = Instant.parse("2057-03-14T01:00:00Z");
    private static final LocalDate D1 = LocalDate.of(2057, 3, 16);

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private long branchA;
    private long branchB;
    private long manager;
    private long receptionist;
    private long vet;
    private long morning;
    private long afternoon;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branchA = data.branch();
        branchB = data.branch();
        manager = data.staff("BRANCH_MANAGER", branchA);
        receptionist = data.staff("RECEPTIONIST", branchA);
        vet = data.staff("VET", branchA);
        morning = definition(branchA, "Ca sáng", "08:00", "12:00", true);
        afternoon = definition(branchA, "Ca chiều", "13:00", "17:00", true);
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    // ---------------------------------------------------------------- nội dung

    @Test
    void returnsOnlyTheCallersOwnRowsOfEveryStatusSortedByDateThenStartTime() {
        long pm = data.assignment(branchA, receptionist, afternoon, D1, "SCHEDULED", "SELF", null);
        long am = data.assignment(branchA, receptionist, morning, D1, "SCHEDULED", "MANAGER", null);
        data.assignment(branchA, receptionist, morning, D1.plusDays(1), "ON_LEAVE", "SELF", manager);
        data.assignment(branchA, receptionist, morning, D1.plusDays(2), "CANCELLED", "SELF", manager);
        data.assignment(branchA, receptionist, morning, D1.plusDays(3), "ABSENT", "MANAGER", manager);
        data.assignment(branchA, vet, morning, D1, "SCHEDULED", "SELF", null); // người khác cùng chi nhánh
        long request = pendingRequest(pm, receptionist);

        ResponseEntity<Map<String, Object>> response = mine(token(receptionist), D1, D1.plusDays(6));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> rows = listOf(response);
        assertThat(rows).hasSize(5);
        assertThat(rows).extracting(row -> row.get("status")).containsExactly("SCHEDULED", "SCHEDULED", "ON_LEAVE",
                "CANCELLED", "ABSENT");
        assertThat(rows).extracting(row -> row.get("startTime")).startsWith("08:00", "13:00");
        assertThat(rows).extracting(row -> idOf(row, "shiftAssignmentId")).startsWith(am, pm);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.keySet()).containsExactlyInAnyOrderElementsOf(CONTRACT_KEYS);
            assertThat(idOf(row, "staffId")).isEqualTo(receptionist);
            assertThat(row.get("staffName")).isNotNull();
        });
        assertThat(rows.get(1)).containsEntry("pendingRequestId", (int) request);
        assertThat(rows.get(0)).containsEntry("pendingRequestId", null);
        assertThat(rows.get(2)).containsEntry("reason", "Lý do").containsEntry("source", "SELF");
    }

    @Test
    void aStaffMemberTransferredToAnotherBranchStillSeesTheOldBranchHistoryAndTheNewRows() {
        data.assignment(branchA, receptionist, morning, D1, "SCHEDULED", "SELF", null);
        long inB = definition(branchB, "Ca B", "08:00", "12:00", true);
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, receptionist);
        data.assignment(branchB, receptionist, inB, D1.plusDays(1), "SCHEDULED", "MANAGER", null);

        List<Map<String, Object>> rows = listOf(mine(token(receptionist), D1, D1.plusDays(1)));

        assertThat(rows).extracting(row -> idOf(row, "branchId")).containsExactly(branchA, branchB);
    }

    @Test
    void extraStaffIdAndBranchIdParametersCannotShowAnotherPersonsSchedule() {
        data.assignment(branchA, vet, morning, D1, "SCHEDULED", "SELF", null);
        data.assignment(branchA, receptionist, afternoon, D1, "SCHEDULED", "SELF", null);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET,
                MINE + "?from=" + D1 + "&to=" + D1 + "&staffId=" + vet + "&branchId=" + branchB, token(receptionist));

        assertThat(listOf(response)).singleElement().satisfies(row -> assertThat(idOf(row, "staffId"))
                .isEqualTo(receptionist));
    }

    @Test
    void anEmptyRangeIsAnEmptyArrayNotAnError() {
        ResponseEntity<Map<String, Object>> response = mine(token(receptionist), D1, D1.plusDays(6));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(listOf(response)).isEmpty();
    }

    @Test
    void theRangeCapIsSixtyTwoDaysCountingBothEndsAndTheRangeMustNotBeReversed() {
        String token = token(receptionist);

        assertThat(mine(token, D1, D1.plusDays(61)).getStatusCode().value()).as("62 ngày").isEqualTo(200);
        assertBusinessRule(mine(token, D1, D1.plusDays(62)), "BR-NS-03");
        assertBusinessRule(mine(token, D1, D1.minusDays(1)), "BR-NS-03");
        assertThat(mine(token, D1, D1).getStatusCode().value()).as("một ngày").isEqualTo(200);
    }

    @Test
    void aMissingOrMalformedDateIs400Malformed() {
        String token = token(receptionist);

        for (String query : List.of("", "?from=" + D1, "?to=" + D1, "?from=khong&to=" + D1, "?from=" + D1 + "&to=13-13")) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, MINE + query, token);

            assertThat(response.getStatusCode().value()).as(query).isEqualTo(400);
            assertThat(errorCode(response)).isEqualTo("MALFORMED_REQUEST");
        }
    }

    // ---------------------------------------------------------------- xác thực

    @Test
    void withoutAValidTokenTheScheduleIs401() {
        for (String token : new String[] {null, "", "rac.khong.phai.token", "Bearer"}) {
            ResponseEntity<Map<String, Object>> response = mine(token, D1, D1);

            assertThat(response.getStatusCode().value()).as("token=%s", token).isEqualTo(401);
            assertThat(errorCode(response)).isEqualTo("UNAUTHENTICATED");
        }
    }

    @Test
    void aRevokedSessionAndALockedOrDisabledAccountAre401() {
        String revoked = token(receptionist);
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", receptionist);
        assertThat(mine(revoked, D1, D1).getStatusCode().value()).isEqualTo(401);

        String locked = token(vet);
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", vet);
        assertThat(mine(locked, D1, D1).getStatusCode().value()).isEqualTo(401);
        jdbc.update("UPDATE accounts SET is_locked = false, status = 'DISABLED' WHERE id = ?", vet);
        assertThat(mine(locked, D1, D1).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aStaffMemberWhoMustChangeThePasswordGetsBrTk17() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", receptionist);

        assertBusinessRule(mine(token(receptionist), D1, D1), "BR-TK-17");
    }

    // ---------------------------------------------------------------- phân quyền

    @Test
    void onlyReceptionistVetAndCaretakerMayReadTheirScheduleAndEveryoneElseGets403() {
        long caretaker = data.staff("CARETAKER", branchA);
        for (long allowed : new long[] {receptionist, vet, caretaker}) {
            assertThat(mine(token(allowed), D1, D1).getStatusCode().value()).isEqualTo(200);
        }

        long admin = data.staff("ADMIN", null);
        long superManager = data.staff("SUPER_MANAGER", null);
        long customer = customerAccount();
        for (long denied : new long[] {manager, admin, superManager, customer}) {
            ResponseEntity<Map<String, Object>> response = mine(token(denied), D1, D1);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            assertThat(errorCode(response)).isEqualTo("ACCESS_DENIED");
        }
    }

    @Test
    void parameterBindingRunsBeforeTheRoleCheckSoABadQueryFromTheWrongRoleIs400AndAGoodOneIs403() {
        String token = token(manager);

        assertThat(call(HttpMethod.GET, MINE, token).getStatusCode().value()).isEqualTo(400);
        assertThat(mine(token, D1, D1).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void aRoleChangeTakesEffectOnTheNextRequestWithTheSameToken() {
        String token = token(receptionist);
        assertThat(mine(token, D1, D1).getStatusCode().value()).isEqualTo(200);

        jdbc.update("UPDATE accounts SET role = 'BRANCH_MANAGER' WHERE id = ?", receptionist);

        assertThat(mine(token, D1, D1).getStatusCode().value()).isEqualTo(403);
    }

    // ---------------------------------------------------------------- hạ tầng

    private ResponseEntity<Map<String, Object>> mine(String token, LocalDate from, LocalDate to) {
        return call(HttpMethod.GET, MINE + "?from=" + from + "&to=" + to, token);
    }
}
