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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * Xác thực và phân quyền của đăng ký ca theo tuần (workforce-v1 #15; docs/adr/0149 mục "Đăng ký tuần — ghi #15") trên
 * Postgres thật: 401 theo từng nguyên nhân (và cùng một body), {@code BR-TK-17}, ma trận bảy vai trò, thứ tự lỗi (hình
 * thức → vai trò → {@code BR-NS-11}), vai trò đọc lại từ DB ở mỗi request, không ghi được dữ liệu của người khác (body
 * có {@code staffId}, {@code branchId}, {@code source} lạ bị bỏ qua) và ca của chi nhánh khác không dùng được. Không lệnh
 * nào bị từ chối để lại dấu vết. Era <b>năm 2087</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ShiftRegistrationPutSecurityIT.TestBeans.class})
class ShiftRegistrationPutSecurityIT extends ShiftRegistrationItSupport {

    private static final Instant NOW = Instant.parse("2087-03-14T01:00:00Z");
    private static final LocalDate WEEK = mondayOnOrAfter(LocalDate.of(2087, 3, 15));

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
    private long outsider;
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
        caretaker = data.staff("CARETAKER", branchA);
        outsider = data.staff("VET", branchB);
        morning = definition(branchA, "Ca sáng", "08:00", "12:00", true);
        afternoon = definition(branchA, "Ca chiều", "13:00", "17:00", true);
    }

    @AfterEach
    void tearDown() {
        finish();
    }

    // ---------------------------------------------------------------- xác thực (401)

    @Test
    void withoutAValidTokenThePutIs401AndSavesNothing() {
        String before = trace(receptionist, vet);

        for (String header : new String[] {null, "", "Bearer", "rac.khong.phai.token"}) {
            ResponseEntity<Map<String, Object>> response = put(header, WEEK, ten());

            assertThat(response.getStatusCode().value()).as("token=%s", header).isEqualTo(401);
            assertThat(errorCode(response)).isEqualTo("UNAUTHENTICATED");
        }
        assertThat(trace(receptionist, vet)).isEqualTo(before);
    }

    @Test
    void aWrongSchemeAndATamperedSignatureAre401ButTheOriginalTokenStillWorks() {
        String token = token(receptionist);
        int signature = token.lastIndexOf('.') + 5;
        char swapped = token.charAt(signature) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, signature) + swapped + token.substring(signature + 1);

        assertThat(put(tampered, WEEK, ten()).getStatusCode().value()).isEqualTo(401);
        HttpHeaders basic = new HttpHeaders();
        basic.set(HttpHeaders.AUTHORIZATION, "Basic " + token);
        basic.setContentType(MediaType.APPLICATION_JSON);
        assertThat(http.exchange(REGISTRATIONS + WEEK, HttpMethod.PUT,
                new HttpEntity<>(ten().replace('\'', '"'), basic), MAP).getStatusCode().value()).isEqualTo(401);
        assertThat(scheduledRowsOf(receptionist)).isEmpty();
        assertThat(put(token, WEEK, ten()).getStatusCode().value()).as("token gốc vẫn dùng được").isEqualTo(200);
    }

    @Test
    void anExpiredTokenIs401() {
        String token = token(receptionist);
        assertThat(put(token, WEEK, ten()).getStatusCode().value()).isEqualTo(200);
        String before = trace(receptionist, vet);

        // access_ttl của profile test là 720 phút (V9000): phải qua 12 giờ chứ không phải 10 phút.
        clock.set(NOW.plus(Duration.ofHours(13)));

        assertThat(put(token, WEEK, ten()).getStatusCode().value()).isEqualTo(401);
        assertThat(trace(receptionist, vet)).isEqualTo(before);
    }

    @Test
    void aRevokedSessionAndALockedOrDisabledAccountAre401AndComeBackWhenRestored() {
        String revoked = token(receptionist);
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", receptionist);
        assertThat(put(revoked, WEEK, ten()).getStatusCode().value()).isEqualTo(401);

        String locked = token(vet);
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", vet);
        assertThat(put(locked, WEEK, ten()).getStatusCode().value()).isEqualTo(401);
        jdbc.update("UPDATE accounts SET is_locked = false, status = 'DISABLED' WHERE id = ?", vet);
        assertThat(put(locked, WEEK, ten()).getStatusCode().value()).isEqualTo(401);
        assertThat(scheduledRowsOf(vet)).as("không lệnh 401 nào ghi được").isEmpty();
        jdbc.update("UPDATE accounts SET status = 'ACTIVE' WHERE id = ?", vet);
        assertThat(put(locked, WEEK, ten()).getStatusCode().value()).as("khôi phục thì dùng lại được").isEqualTo(200);
    }

    @Test
    void everyCauseOf401HasTheSameErrorCodeAndMessageSoNoReasonLeaks() {
        List<ResponseEntity<Map<String, Object>>> responses = new ArrayList<>();
        responses.add(put(null, WEEK, ten()));
        responses.add(put("rac.khong.phai.token", WEEK, ten()));
        String revoked = token(receptionist);
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE account_id = ?", receptionist);
        responses.add(put(revoked, WEEK, ten()));
        String locked = token(vet);
        jdbc.update("UPDATE accounts SET is_locked = true WHERE id = ?", vet);
        responses.add(put(locked, WEEK, ten()));
        String disabled = token(caretaker);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", caretaker);
        responses.add(put(disabled, WEEK, ten()));

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode().value()).isEqualTo(401));
        assertThat(responses).extracting(ShiftDefinitionItSupport::errorCode).containsOnly("UNAUTHENTICATED");
        assertThat(responses).extracting(ShiftDefinitionItSupport::message).containsOnly(message(responses.get(0)));
    }

    // ---------------------------------------------------------------- BR-TK-17

    @Test
    void aStaffMemberWhoMustChangeThePasswordGetsBrTk17BeforeAnyBodyOrWeekCheckAndNothingIsSaved() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", receptionist);
        String token = token(receptionist);
        String before = trace(receptionist, vet);

        assertBusinessRule(put(token, WEEK, ten()), "BR-TK-17");
        assertBusinessRule(put(token, WEEK, "{}"), "BR-TK-17");
        assertBusinessRule(put(token, WEEK, "{'items':'x'}"), "BR-TK-17");
        assertBusinessRule(put(token, WEEK.plusDays(1), ten()), "BR-TK-17");
        assertBusinessRule(put(token, "abc", ten()), "BR-TK-17");
        assertThat(trace(receptionist, vet)).isEqualTo(before);
    }

    // ---------------------------------------------------------------- phân quyền (403)

    @Test
    void onlyReceptionistVetAndCaretakerMayPutAndEveryoneElseGets403WithoutATrace() {
        long admin = data.staff("ADMIN", null);
        long superManager = data.staff("SUPER_MANAGER", null);
        long customer = customerAccount();
        List<String> denied = new ArrayList<>();
        for (long account : new long[] {manager, superManager, admin, customer}) {
            denied.add(token(account));
        }
        String before = trace(receptionist, vet, caretaker, manager);
        long audit = totalAuditCount();
        long outbox = totalOutboxCount();

        for (String token : denied) {
            ResponseEntity<Map<String, Object>> response = put(token, WEEK, ten());

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            assertThat(errorCode(response)).isEqualTo("ACCESS_DENIED");
        }

        assertThat(trace(receptionist, vet, caretaker, manager)).isEqualTo(before);
        assertThat(assignmentCountOfStaff(manager)).isZero();
        assertThat(totalAuditCount()).isEqualTo(audit);
        assertThat(totalOutboxCount()).isEqualTo(outbox);
        for (long staff : new long[] {receptionist, vet, caretaker}) {
            assertThat(put(token(staff), WEEK, ten()).getStatusCode().value()).as("staff %d", staff).isEqualTo(200);
            assertThat(scheduledRowsOf(staff)).hasSize(10);
        }
    }

    @Test
    void formShapeIsCheckedBeforeTheRoleAndTheRoleBeforeTheBusinessRule() {
        String managerToken = token(manager);
        String staffToken = token(receptionist);

        assertThat(errorCode(put(managerToken, "abc", ten()))).isEqualTo("MALFORMED_REQUEST");
        assertThat(errorCode(put(managerToken, WEEK, "{}"))).as("@Valid chạy trước @PreAuthorize")
                .isEqualTo("VALIDATION_FAILED");
        assertThat(errorCode(put(managerToken, WEEK, "{'items':'x'}"))).isEqualTo("MALFORMED_REQUEST");
        assertThat(put(managerToken, WEEK, ten()).getStatusCode().value()).isEqualTo(403);
        assertThat(put(managerToken, WEEK.plusDays(1), ten()).getStatusCode().value())
                .as("vai trò chặn trước guard BR-NS-11").isEqualTo(403);
        assertBusinessRule(put(staffToken, WEEK.plusDays(1), ten()), "BR-NS-11");
    }

    @Test
    void aRoleChangeTakesEffectOnTheNextRequestWithTheSameTokenInBothDirections() {
        String staffToken = token(receptionist);
        assertThat(put(staffToken, WEEK, ten()).getStatusCode().value()).isEqualTo(200);
        jdbc.update("UPDATE accounts SET role = 'BRANCH_MANAGER' WHERE id = ?", receptionist);
        String before = trace(receptionist);
        assertThat(put(staffToken, WEEK, itemsBody(item(WEEK, morning))).getStatusCode().value()).isEqualTo(403);
        assertThat(trace(receptionist)).as("tuần đã đăng ký không bị đụng").isEqualTo(before);

        String managerToken = token(manager);
        assertThat(put(managerToken, WEEK, ten()).getStatusCode().value()).isEqualTo(403);
        jdbc.update("UPDATE accounts SET role = 'VET' WHERE id = ?", manager);
        assertThat(put(managerToken, WEEK, ten()).getStatusCode().value()).isEqualTo(200);
        assertThat(scheduledRowsOf(manager)).hasSize(10);
    }

    // ---------------------------------------------------------------- dữ liệu của người khác

    @Test
    void aTokenOnlyEverWritesItsOwnersRowsEvenWhenTheRequestAsksForSomeoneElseOrForAnotherSource() {
        long theirs = data.assignment(branchA, vet, morning, WEEK, "SCHEDULED", "SELF", null);
        long theirManaged = data.assignment(branchA, vet, afternoon, WEEK.plusDays(1), "SCHEDULED", "MANAGER", null);
        Map<Long, String> vetBefore = xminsOf(vet);
        String body = "{'staffId':" + vet + ",'branchId':" + branchB + ",'source':'MANAGER','status':'CANCELLED',"
                + "'items':[" + String.join(",", tenItems()) + "]}";

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.PUT, REGISTRATIONS + WEEK + "?staffId=" + vet
                + "&accountId=" + vet, token(receptionist), body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(rowsOf(receptionist)).hasSize(10).allSatisfy(row -> {
            assertThat(row).containsEntry("source", "SELF").containsEntry("status", "SCHEDULED");
            assertThat(((Number) row.get("branch_id")).longValue()).isEqualTo(branchA);
        });
        assertThat(xminsOf(vet)).as("người khác không bị đụng").isEqualTo(vetBefore);
        assertThat(assignmentRow(theirs)).containsEntry("status", "SCHEDULED");
        assertThat(assignmentRow(theirManaged)).containsEntry("status", "SCHEDULED");
        assertThat(assignmentsOf(response)).extracting(row -> idOf(row, "staffId")).containsOnly(receptionist);
    }

    @Test
    void aStaffMemberOfAnotherBranchCannotUseThisBranchsShiftsAndLearnsNothingAboutThem() {
        String before = trace(outsider, receptionist);

        ResponseEntity<Map<String, Object>> response = put(token(outsider), WEEK, ten());

        assertBusinessRule(response, "BR-NS-11");
        assertThat(message(response)).contains("Mục 1: ca #" + morning + " không tồn tại hoặc không thuộc chi nhánh của bạn")
                .doesNotContain(definitionName(morning));
        assertThat(trace(outsider, receptionist)).isEqualTo(before);
    }

    // ---------------------------------------------------------------- tiện ích

    private List<String> tenItems() {
        List<String> items = new ArrayList<>();
        for (int day = 0; day < 5; day++) {
            items.add(item(WEEK.plusDays(day), morning));
            items.add(item(WEEK.plusDays(day), afternoon));
        }
        return items;
    }

    private String ten() {
        return itemsBody(tenItems().toArray(String[]::new));
    }

    private String definitionName(long definitionId) {
        return jdbc.queryForObject("SELECT name FROM shift_definitions WHERE id = ?", String.class, definitionId);
    }
}
