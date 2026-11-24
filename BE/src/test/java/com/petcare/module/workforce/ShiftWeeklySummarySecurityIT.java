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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Xác thực / phân quyền của {@code GET /shift-weekly-summaries} (workforce-v1 #16; docs/adr/0149): 401 theo từng nguyên
 * nhân với body đồng nhất (không lộ lý do), 401 theo trạng thái phiên / tài khoản, BR-TK-17 đứng trước binder, bảy vai
 * trò (chỉ BRANCH_MANAGER, SUPER_MANAGER cũng 403), thứ tự binder → role → service, vai trò và chi nhánh đọc lại từ DB ở
 * mỗi request, quản lý không có chi nhánh, không xem được chi nhánh khác (kể cả khi gửi tham số thừa), lỗi 401 / 403
 * không để lại dấu vết. Era <b>năm 2091</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftWeeklySummarySecurityIT.TestBeans.class})
class ShiftWeeklySummarySecurityIT extends ShiftWeeklySummaryItSupport {

    private static final Instant NOW = Instant.parse("2091-03-14T01:00:00Z");
    private static final LocalDate WEEK = mondayOnOrAfter(LocalDate.of(2091, 3, 14));

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
    private long caretaker;
    private long vetOfB;
    private long managerOfB;
    private long morning;

    @BeforeEach
    void setUp() {
        begin(NOW);
        branchA = data.branch();
        branchB = data.branch();
        manager = data.staff("BRANCH_MANAGER", branchA);
        receptionist = data.staff("RECEPTIONIST", branchA);
        vet = data.staff("VET", branchA);
        caretaker = data.staff("CARETAKER", branchA);
        managerOfB = data.staff("BRANCH_MANAGER", branchB);
        vetOfB = data.staff("VET", branchB);
        morning = definition(branchA, "Ca sáng", "08:00", "12:00", true);
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    // ---------------------------------------------------------------- xác thực (401)

    @Test
    void withoutAValidTokenTheSummaryIs401() {
        for (String header : new String[] {null, "", "Bearer", "rac.khong.phai.token"}) {
            ResponseEntity<Map<String, Object>> response = summaries(header, WEEK);

            assertThat(response.getStatusCode().value()).as("token=%s", header).isEqualTo(401);
            assertThat(errorCode(response)).isEqualTo("UNAUTHENTICATED");
        }
    }

    @Test
    void aWrongSchemeAndATamperedSignatureAre401() {
        String token = token(manager);
        int signature = token.lastIndexOf('.') + 5;
        char swapped = token.charAt(signature) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, signature) + swapped + token.substring(signature + 1);

        assertThat(summaries(tampered, WEEK).getStatusCode().value()).isEqualTo(401);
        HttpHeaders basic = new HttpHeaders();
        basic.set(HttpHeaders.AUTHORIZATION, "Basic " + token);
        assertThat(http.exchange(SUMMARIES + "?weekStart=" + WEEK, HttpMethod.GET, new HttpEntity<>(basic), MAP)
                .getStatusCode().value()).isEqualTo(401);
        assertThat(summaries(token, WEEK).getStatusCode().value()).as("token gốc vẫn dùng được").isEqualTo(200);
    }

    @Test
    void anExpiredTokenIs401() {
        String token = token(manager);
        assertThat(summaries(token, WEEK).getStatusCode().value()).isEqualTo(200);

        // access_ttl của profile test là 720 phút (V9000): phải qua 12 giờ chứ không phải 10 phút.
        clock.set(NOW.plus(Duration.ofHours(13)));

        assertThat(summaries(token, WEEK).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aRevokedSessionAndALockedOrDisabledManagerAre401AndComeBackWhenRestored() {
        String revoked = token(manager);
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", manager);
        assertThat(summaries(revoked, WEEK).getStatusCode().value()).isEqualTo(401);

        String locked = token(managerOfB);
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", managerOfB);
        assertThat(summaries(locked, WEEK).getStatusCode().value()).isEqualTo(401);
        jdbc.update("UPDATE accounts SET is_locked = false, status = 'DISABLED' WHERE id = ?", managerOfB);
        assertThat(summaries(locked, WEEK).getStatusCode().value()).isEqualTo(401);
        jdbc.update("UPDATE accounts SET status = 'ACTIVE' WHERE id = ?", managerOfB);
        assertThat(summaries(locked, WEEK).getStatusCode().value()).as("khôi phục thì dùng lại được").isEqualTo(200);
    }

    @Test
    void everyCauseOf401HasTheSameErrorCodeAndMessageSoNoReasonLeaks() {
        List<ResponseEntity<Map<String, Object>>> responses = new ArrayList<>();
        responses.add(summaries(null, WEEK));
        responses.add(summaries("rac.khong.phai.token", WEEK));
        String revoked = token(manager);
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", manager);
        responses.add(summaries(revoked, WEEK));
        String locked = token(managerOfB);
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", managerOfB);
        responses.add(summaries(locked, WEEK));
        long other = data.staff("BRANCH_MANAGER", branchA);
        String disabled = token(other);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", other);
        responses.add(summaries(disabled, WEEK));

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode().value()).isEqualTo(401));
        assertThat(responses).extracting(ShiftDefinitionItSupport::errorCode).containsOnly("UNAUTHENTICATED");
        assertThat(responses).extracting(ShiftDefinitionItSupport::message).containsOnly(message(responses.get(0)));
    }

    // ---------------------------------------------------------------- BR-TK-17

    @Test
    void aManagerWhoMustChangeThePasswordGetsBrTk17EvenForAMalformedOrMissingWeek() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", manager);
        String token = token(manager);

        assertBusinessRule(summaries(token, WEEK), "BR-TK-17");
        assertBusinessRule(summaries(token, "abc"), "BR-TK-17");
        assertBusinessRule(summaries(token, WEEK.plusDays(1)), "BR-TK-17");
        assertBusinessRule(summariesRaw(token, ""), "BR-TK-17");
    }

    // ---------------------------------------------------------------- phân quyền (403)

    @Test
    void onlyTheBranchManagerMayReadAndEveryoneElseGets403WithoutATrace() {
        long admin = data.staff("ADMIN", null);
        long superManager = data.staff("SUPER_MANAGER", null);
        long customer = customerAccount();
        data.assignment(branchA, receptionist, morning, WEEK, "SCHEDULED", "SELF", null);
        List<String> denied = new ArrayList<>();
        for (long account : new long[] {receptionist, vet, caretaker, superManager, admin, customer}) {
            denied.add(token(account));
        }
        String allowed = token(manager);
        String before = footprint(manager, receptionist, vet, caretaker);

        assertThat(summaries(allowed, WEEK).getStatusCode().value()).isEqualTo(200);
        for (String token : denied) {
            ResponseEntity<Map<String, Object>> response = summaries(token, WEEK);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            assertThat(errorCode(response)).isEqualTo("ACCESS_DENIED");
        }
        assertThat(footprint(manager, receptionist, vet, caretaker)).isEqualTo(before);
    }

    @Test
    void bindingRunsBeforeTheRoleCheckAndTheRoleCheckRunsBeforeTheBusinessRule() {
        String customerToken = token(customerAccount());
        String staffToken = token(receptionist);
        String managerToken = token(manager);

        assertThat(errorCode(summaries(customerToken, "abc"))).isEqualTo("MALFORMED_REQUEST");
        assertThat(errorCode(summariesRaw(staffToken, ""))).isEqualTo("MALFORMED_REQUEST");
        assertThat(summaries(customerToken, WEEK).getStatusCode().value()).isEqualTo(403);
        assertThat(summaries(staffToken, WEEK.plusDays(1)).getStatusCode().value())
                .as("vai trò chặn trước guard BR-NS-11").isEqualTo(403);
        assertBusinessRule(summaries(managerToken, WEEK.plusDays(1)), "BR-NS-11");
    }

    @Test
    void aRoleChangeTakesEffectOnTheNextRequestWithTheSameTokenInBothDirections() {
        String managerToken = token(manager);
        assertThat(summaries(managerToken, WEEK).getStatusCode().value()).isEqualTo(200);
        jdbc.update("UPDATE accounts SET role = 'VET' WHERE id = ?", manager);
        assertThat(summaries(managerToken, WEEK).getStatusCode().value()).isEqualTo(403);

        String staffToken = token(receptionist);
        assertThat(summaries(staffToken, WEEK).getStatusCode().value()).isEqualTo(403);
        jdbc.update("UPDATE accounts SET role = 'BRANCH_MANAGER' WHERE id = ?", receptionist);
        assertThat(summaries(staffToken, WEEK).getStatusCode().value()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- phạm vi chi nhánh

    @Test
    void aManagerWithoutABranchIs403ScopeMismatch() {
        long homeless = data.staff("BRANCH_MANAGER", null);

        ResponseEntity<Map<String, Object>> response = summaries(token(homeless), WEEK);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo("ACCESS_DENIED_SCOPE_MISMATCH");
    }

    @Test
    void aManagerOnlySeesOwnBranchEvenWhenTheOtherBranchHasShiftsInTheSameWeekAndExtraParametersAreIgnored() {
        data.assignment(branchA, receptionist, morning, WEEK, "SCHEDULED", "SELF", null);
        data.assignment(branchB, vetOfB, definition(branchB, "Ca B", "08:00", "12:00", true), WEEK, "SCHEDULED", "SELF",
                null);

        Map<Long, Map<String, Object>> asA = byStaff(summaries(token(manager), WEEK));
        Map<Long, Map<String, Object>> asB = byStaff(summaries(token(managerOfB), WEEK));
        ResponseEntity<Map<String, Object>> greedy = summariesRaw(token(manager),
                "weekStart=" + WEEK + "&branchId=" + branchB + "&staffId=" + vetOfB);

        assertThat(asA.keySet()).containsExactlyInAnyOrder(receptionist, vet, caretaker);
        assertThat(asB.keySet()).containsExactly(vetOfB);
        assertThat(byStaff(greedy).keySet()).containsExactlyInAnyOrder(receptionist, vet, caretaker);
    }

    @Test
    void aTransferTakesEffectOnTheNextRequestWithTheSameToken() {
        String managerToken = token(manager);
        assertThat(byStaff(summaries(managerToken, WEEK)).keySet()).containsExactlyInAnyOrder(receptionist, vet, caretaker);

        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", branchB, manager);

        assertThat(byStaff(summaries(managerToken, WEEK)).keySet()).containsExactly(vetOfB);
    }
}
