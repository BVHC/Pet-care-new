package com.petcare.module.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * Xác thực / phân quyền của {@code GET /me/shift-registrations/{weekStart}} (workforce-v1 #17; docs/adr/0149): 401 theo
 * từng nguyên nhân với body đồng nhất (không lộ lý do), 401 theo trạng thái phiên / tài khoản, BR-TK-17 đứng trước
 * binder, bảy vai trò (chỉ A06–A08), thứ tự binder → role → service, role đọc lại từ DB ở mỗi request, IDOR ngang, và
 * lỗi 401 / 403 không để lại dấu vết. Era <b>năm 2084</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRegistrationReadSecurityIT.TestBeans.class})
class ShiftRegistrationReadSecurityIT extends ShiftRegistrationItSupport {

    private static final Instant NOW = Instant.parse("2084-03-14T01:00:00Z");
    private static final LocalDate WEEK = mondayOnOrAfter(LocalDate.of(2084, 3, 14));

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private long branchA;
    private long manager;
    private long receptionist;
    private long vet;
    private long caretaker;
    private long morning;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branchA = data.branch();
        manager = data.staff("BRANCH_MANAGER", branchA);
        receptionist = data.staff("RECEPTIONIST", branchA);
        vet = data.staff("VET", branchA);
        caretaker = data.staff("CARETAKER", branchA);
        morning = definition(branchA, "Ca sáng", "08:00", "12:00", true);
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    // ---------------------------------------------------------------- xác thực (401)

    @Test
    void withoutAValidTokenTheRegistrationIs401() {
        for (String header : new String[] {null, "", "Bearer", "rac.khong.phai.token"}) {
            ResponseEntity<Map<String, Object>> response = registration(header, WEEK);

            assertThat(response.getStatusCode().value()).as("token=%s", header).isEqualTo(401);
            assertThat(errorCode(response)).isEqualTo("UNAUTHENTICATED");
        }
    }

    @Test
    void aWrongSchemeAndATamperedSignatureAre401() {
        String token = token(receptionist);
        int signature = token.lastIndexOf('.') + 5;
        char swapped = token.charAt(signature) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, signature) + swapped + token.substring(signature + 1);

        assertThat(registration(tampered, WEEK).getStatusCode().value()).isEqualTo(401);
        assertThat(http.exchange(REGISTRATIONS + WEEK, HttpMethod.GET, new org.springframework.http.HttpEntity<>(
                headers("Basic " + token)), MAP).getStatusCode().value()).isEqualTo(401);
        assertThat(registration(token, WEEK).getStatusCode().value()).as("token gốc vẫn dùng được").isEqualTo(200);
    }

    @Test
    void anExpiredTokenIs401() {
        String token = token(receptionist);
        assertThat(registration(token, WEEK).getStatusCode().value()).isEqualTo(200);

        // access_ttl của profile test là 720 phút (V9000): phải qua 12 giờ chứ không phải 10 phút.
        clock.set(NOW.plus(Duration.ofHours(13)));

        assertThat(registration(token, WEEK).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aRevokedSessionAndALockedOrDisabledAccountAre401AndComeBackWhenRestored() {
        String revoked = token(receptionist);
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", receptionist);
        assertThat(registration(revoked, WEEK).getStatusCode().value()).isEqualTo(401);

        String locked = token(vet);
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", vet);
        assertThat(registration(locked, WEEK).getStatusCode().value()).isEqualTo(401);
        jdbc.update("UPDATE accounts SET is_locked = false, status = 'DISABLED' WHERE id = ?", vet);
        assertThat(registration(locked, WEEK).getStatusCode().value()).isEqualTo(401);
        jdbc.update("UPDATE accounts SET status = 'ACTIVE' WHERE id = ?", vet);
        assertThat(registration(locked, WEEK).getStatusCode().value()).as("khôi phục thì dùng lại được").isEqualTo(200);
    }

    @Test
    void everyCauseOf401HasTheSameErrorCodeAndMessageSoNoReasonLeaks() {
        List<ResponseEntity<Map<String, Object>>> responses = new ArrayList<>();
        responses.add(registration(null, WEEK));
        responses.add(registration("rac.khong.phai.token", WEEK));
        String revoked = token(receptionist);
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", receptionist);
        responses.add(registration(revoked, WEEK));
        String locked = token(vet);
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", vet);
        responses.add(registration(locked, WEEK));
        String disabled = token(caretaker);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", caretaker);
        responses.add(registration(disabled, WEEK));

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode().value()).isEqualTo(401));
        assertThat(responses).extracting(ShiftDefinitionItSupport::errorCode).containsOnly("UNAUTHENTICATED");
        assertThat(responses).extracting(ShiftDefinitionItSupport::message).containsOnly(message(responses.get(0)));
    }

    // ---------------------------------------------------------------- BR-TK-17

    @Test
    void aStaffMemberWhoMustChangeThePasswordGetsBrTk17EvenForAMalformedWeek() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", receptionist);
        String token = token(receptionist);

        assertBusinessRule(registration(token, WEEK), "BR-TK-17");
        assertBusinessRule(registration(token, "abc"), "BR-TK-17");
        assertBusinessRule(registration(token, WEEK.plusDays(1)), "BR-TK-17");
    }

    // ---------------------------------------------------------------- phân quyền (403)

    @Test
    void onlyReceptionistVetAndCaretakerMayReadAndEveryoneElseGets403WithoutATrace() {
        long admin = data.staff("ADMIN", null);
        long superManager = data.staff("SUPER_MANAGER", null);
        long customer = customerAccount();
        data.assignment(branchA, receptionist, morning, WEEK, "SCHEDULED", "SELF", null);
        List<String> allowed = new ArrayList<>();
        for (long staff : new long[] {receptionist, vet, caretaker}) {
            allowed.add(token(staff));
        }
        List<String> denied = new ArrayList<>();
        for (long account : new long[] {manager, superManager, admin, customer}) {
            denied.add(token(account));
        }
        String before = footprint(receptionist, vet, caretaker, manager);

        for (String token : allowed) {
            assertThat(registration(token, WEEK).getStatusCode().value()).isEqualTo(200);
        }
        for (String token : denied) {
            ResponseEntity<Map<String, Object>> response = registration(token, WEEK);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            assertThat(errorCode(response)).isEqualTo("ACCESS_DENIED");
        }
        assertThat(footprint(receptionist, vet, caretaker, manager)).isEqualTo(before);
    }

    @Test
    void bindingRunsBeforeTheRoleCheckAndTheRoleCheckRunsBeforeTheBusinessRule() {
        String managerToken = token(manager);
        String staffToken = token(receptionist);

        assertThat(errorCode(registration(managerToken, "abc"))).isEqualTo("MALFORMED_REQUEST");
        assertThat(registration(managerToken, WEEK).getStatusCode().value()).isEqualTo(403);
        assertThat(registration(managerToken, WEEK.plusDays(1)).getStatusCode().value())
                .as("vai trò chặn trước guard BR-NS-11").isEqualTo(403);
        assertBusinessRule(registration(staffToken, WEEK.plusDays(1)), "BR-NS-11");
    }

    @Test
    void aRoleChangeTakesEffectOnTheNextRequestWithTheSameTokenInBothDirections() {
        String staffToken = token(receptionist);
        assertThat(registration(staffToken, WEEK).getStatusCode().value()).isEqualTo(200);
        jdbc.update("UPDATE accounts SET role = 'BRANCH_MANAGER' WHERE id = ?", receptionist);
        assertThat(registration(staffToken, WEEK).getStatusCode().value()).isEqualTo(403);

        String managerToken = token(manager);
        assertThat(registration(managerToken, WEEK).getStatusCode().value()).isEqualTo(403);
        jdbc.update("UPDATE accounts SET role = 'VET' WHERE id = ?", manager);
        assertThat(registration(managerToken, WEEK).getStatusCode().value()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- dữ liệu của người khác

    @Test
    void aTokenOnlyEverShowsItsOwnersRowsEvenWhenAnotherStaffMemberAsksForTheirs() {
        long mine = data.assignment(branchA, receptionist, morning, WEEK, "SCHEDULED", "SELF", null);
        long theirs = data.assignment(branchA, vet, morning, WEEK, "SCHEDULED", "SELF", null);

        ResponseEntity<Map<String, Object>> asReceptionist = call(HttpMethod.GET,
                REGISTRATIONS + WEEK + "?staffId=" + vet + "&accountId=" + vet, token(receptionist));
        ResponseEntity<Map<String, Object>> asVet = registration(token(vet), WEEK);

        assertThat(assignmentsOf(asReceptionist)).extracting(row -> idOf(row, "shiftAssignmentId"))
                .containsExactly(mine);
        assertThat(assignmentsOf(asVet)).extracting(row -> idOf(row, "shiftAssignmentId")).containsExactly(theirs);
    }

    // ---------------------------------------------------------------- hạ tầng

    private static org.springframework.http.HttpHeaders headers(String authorization) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set(org.springframework.http.HttpHeaders.AUTHORIZATION, authorization);
        return headers;
    }
}
