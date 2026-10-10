package com.petcare.module.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;

/**
 * {@code GET/PATCH /api/me/customer-profile} (UC06, customer-v1 #1–2; BR-TK-15, BR-KH-01; docs/adr/0028) qua HTTP trên
 * Postgres 17 thật. Kiểm: đúng tập khóa của hợp đồng, mỗi cột một giá trị riêng; email là email tài khoản; null = giữ,
 * rỗng = xóa; mọi lỗi đủ 6 trường envelope và <b>không đổi dữ liệu</b>; hồ sơ tại quầy không xóa được SĐT (400, không
 * 409); cờ chờ liên kết không đổi và không chặn; nhân viên 403; khóa {@code customers} trước khi đọc (không ghi đè thay
 * đổi đồng thời).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CustomerProfileIT extends CustomerMeItSupport {

    private static final String PATH = "/api/me/customer-profile";

    // ---------------------------------------------------------------- GET

    @Test
    void getMatchesContractAndEmailComesFromAccountEvenIfProfileEmailNull() {
        String email = newEmail();
        long account = customerAccount(email);
        long customer = onlineProfile(account, "Nguyễn An", "0901234567", null, "https://img.test/a.png", false);

        Map<String, Object> profile = data(call(HttpMethod.GET, PATH, open(account), null), HttpStatus.OK);

        assertThat(profile).containsOnlyKeys("customerId", "fullName", "phone", "email", "avatarUrl",
                        "createdChannel", "hasAccount", "linkDecisionPending")
                .containsEntry("fullName", "Nguyễn An").containsEntry("phone", "0901234567")
                .containsEntry("email", email).containsEntry("avatarUrl", "https://img.test/a.png")
                .containsEntry("createdChannel", "ONLINE").containsEntry("hasAccount", true)
                .containsEntry("linkDecisionPending", false);
        assertThat(number(profile.get("customerId"))).isEqualTo(customer);
    }

    @Test
    void getShowsNullFieldsAndLinkedCounterProfile() {
        String email = newEmail();
        long account = customerAccount(email);
        counterProfile(account, "Hồ sơ quầy", "0912345678", "ho-so-cu@petcare.test");

        Map<String, Object> profile = data(call(HttpMethod.GET, PATH, open(account), null), HttpStatus.OK);

        assertThat(profile).containsEntry("createdChannel", "COUNTER").containsEntry("hasAccount", true)
                .containsEntry("email", email).containsEntry("avatarUrl", null);
    }

    // ---------------------------------------------------------------- PATCH thành công

    @Test
    void patchUpdatesStripsAndPersists() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Tên cũ", null, null, null, false);
        String token = open(account);

        Map<String, Object> profile = data(call(HttpMethod.PATCH, PATH, token, Map.of("fullName", "  Nguyễn An  ",
                "phone", "0901234567", "avatarUrl", " https://img.test/a.png ")), HttpStatus.OK);

        assertThat(profile).containsEntry("fullName", "Nguyễn An").containsEntry("phone", "0901234567")
                .containsEntry("avatarUrl", "https://img.test/a.png");
        assertThat(row(customer)).containsEntry("full_name", "Nguyễn An").containsEntry("phone", "0901234567")
                .containsEntry("avatar_url", "https://img.test/a.png");
        assertThat(data(call(HttpMethod.GET, PATH, token, null), HttpStatus.OK)).isEqualTo(profile);
    }

    @Test
    void emptyStringClearsPhoneAndAvatarOfOnlineProfile() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Nguyễn An", "0901234567", null, "https://img.test/a.png", false);

        Map<String, Object> profile = data(call(HttpMethod.PATCH, PATH, open(account),
                Map.of("phone", "", "avatarUrl", "   ")), HttpStatus.OK);

        assertThat(profile).containsEntry("phone", null).containsEntry("avatarUrl", null)
                .containsEntry("fullName", "Nguyễn An");
        assertThat(row(customer)).containsEntry("phone", null).containsEntry("avatar_url", null);
    }

    @Test
    void allNullIsNoOpAndTimestampUnchanged() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Nguyễn An", "0901234567", null, null, false);
        Map<String, Object> before = row(customer);
        Map<String, Object> nulls = new HashMap<>();
        nulls.put("fullName", null);
        nulls.put("phone", null);
        nulls.put("avatarUrl", null);

        data(call(HttpMethod.PATCH, PATH, open(account), nulls), HttpStatus.OK);
        data(call(HttpMethod.PATCH, PATH, open(account), Map.of()), HttpStatus.OK);

        assertThat(row(customer)).isEqualTo(before);
    }

    @Test
    void unknownFieldsAreIgnored() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Nguyễn An", null, null, null, true);

        data(call(HttpMethod.PATCH, PATH, open(account), Map.of("fullName", "Tên mới", "customerId", 1,
                "accountId", 1, "createdChannel", "COUNTER", "linkDecisionPending", false)), HttpStatus.OK);

        assertThat(row(customer)).containsEntry("full_name", "Tên mới").containsEntry("created_channel", "ONLINE")
                .containsEntry("link_decision_pending", true);
        assertThat(number(row(customer).get("account_id"))).isEqualTo(account);
    }

    /** BR-TK-19: cờ chỉ đặt lúc xác thực, gỡ qua UC07; đổi SĐT không đụng cờ và hồ sơ không bị ẩn khi còn cờ. */
    @Test
    void phoneChangeKeepsLinkFlagAndPendingFlagDoesNotBlock() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Nguyễn An", "0901234567", null, null, true);
        String token = open(account);

        assertThat(data(call(HttpMethod.PATCH, PATH, token, Map.of("phone", "0909999999")), HttpStatus.OK))
                .containsEntry("linkDecisionPending", true);
        assertThat(row(customer)).containsEntry("link_decision_pending", true);
    }

    /** Hồ sơ tại quầy (BE-2) có thể giữ SĐT 11 số theo kiểu `phone` chung: PATCH không gửi SĐT thì giữ nguyên. */
    @Test
    void patchWithoutPhoneKeepsLegacyElevenDigitPhone() {
        long account = customerAccount(newEmail());
        long customer = counterProfile(account, "Hồ sơ quầy", "09123456789", null);

        assertThat(data(call(HttpMethod.PATCH, PATH, open(account), Map.of("fullName", "Tên mới")), HttpStatus.OK))
                .containsEntry("phone", "09123456789");
        assertThat(row(customer)).containsEntry("phone", "09123456789");
    }

    // ---------------------------------------------------------------- lỗi (không đổi dữ liệu)

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"fullName\": \"\"}",
            "{\"fullName\": \"   \"}",
            "{\"phone\": \"090123456\"}",
            "{\"phone\": \"09012345678\"}",
            "{\"phone\": \"+84901234567\"}",
            "{\"phone\": \"0901 234 567\"}",
            "{\"avatarUrl\": \"http://img.test/a.png\"}",
            "{\"avatarUrl\": \"javascript:alert(1)\"}",
            "{\"avatarUrl\": \"https://\"}",
            "{\"email\": \"x@petcare.test\", \"phone\": \"abc\"}"})
    void invalidShapeIs400AndNothingChanges(String body) {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Nguyễn An", "0901234567", null, "https://img.test/a.png", false);
        Map<String, Object> before = row(customer);

        assertError(callRaw(HttpMethod.PATCH, PATH, open(account), body), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(row(customer)).isEqualTo(before);
    }

    @Test
    void tooLongFieldsAre400() {
        long account = customerAccount(newEmail());
        onlineProfile(account, "Nguyễn An", null, null, null, false);
        String token = open(account);

        assertError(call(HttpMethod.PATCH, PATH, token, Map.of("fullName", "a".repeat(101))), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(call(HttpMethod.PATCH, PATH, token, Map.of("avatarUrl", "https://" + "a".repeat(493))),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(data(call(HttpMethod.PATCH, PATH, token, Map.of("fullName", "a".repeat(100))), HttpStatus.OK))
                .containsEntry("fullName", "a".repeat(100));
    }

    @Test
    void malformedJsonIs400() {
        long account = customerAccount(newEmail());
        onlineProfile(account, "Nguyễn An", null, null, null, false);
        String token = open(account);

        assertError(callRaw(HttpMethod.PATCH, PATH, token, "{\"fullName\": "), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(callRaw(HttpMethod.PATCH, PATH, token, ""), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
    }

    @Test
    void emailInBodyIs400BrTk15AndNothingChanges() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Nguyễn An", "0901234567", null, null, false);
        Map<String, Object> before = row(customer);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.PATCH, PATH, open(account),
                Map.of("fullName", "Tên mới", "email", "moi@petcare.test"));

        assertRule(response, "BR-TK-15");
        assertThat(response.getBody()).containsEntry("message",
                "Không thể tự sửa email; liên hệ lễ tân để được sửa hộ (BR-TK-15)");
        assertThat(row(customer)).isEqualTo(before);
    }

    @Test
    void counterProfileCannotClearPhoneBrKh01NotConflict() {
        long account = customerAccount(newEmail());
        long customer = counterProfile(account, "Hồ sơ quầy", "0912345678", null);
        Map<String, Object> before = row(customer);

        assertRule(call(HttpMethod.PATCH, PATH, open(account), Map.of("fullName", "Tên mới", "phone", "")),
                "BR-KH-01");
        assertThat(row(customer)).isEqualTo(before);
    }

    // ---------------------------------------------------------------- quyền, phiên, method

    @Test
    void staffWithValidBodyIs403() {
        String token = open(staffAccount());

        assertError(call(HttpMethod.GET, PATH, token, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.PATCH, PATH, token, Map.of("fullName", "X")), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED");
    }

    /** Bean Validation chạy trước {@code @PreAuthorize} (như {@code PATCH /me/staff-profile}; docs/adr/0028). */
    @Test
    void staffWithInvalidBodyIs400() {
        assertError(call(HttpMethod.PATCH, PATH, open(staffAccount()), Map.of("phone", "abc")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void withoutTokenIs401() {
        assertError(call(HttpMethod.GET, PATH, null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(call(HttpMethod.PATCH, PATH, null, Map.of("fullName", "X")), HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED");
    }

    @Test
    void otherMethodsAre405() {
        long account = customerAccount(newEmail());
        onlineProfile(account, "Nguyễn An", null, null, null, false);

        assertError(call(HttpMethod.PUT, PATH, open(account), Map.of("fullName", "X")),
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
    }

    @Test
    void customerWithoutProfileRowIs500() {
        String token = open(customerAccount(newEmail()));

        assertError(call(HttpMethod.GET, PATH, token, null), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        assertError(call(HttpMethod.PATCH, PATH, token, Map.of("fullName", "X")), HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR");
    }

    // ---------------------------------------------------------------- khóa dòng customers (docs/adr/0028)

    /** Luồng khác khóa hồ sơ rồi đổi một cột PATCH không gửi: PATCH chờ, đọc dưới khóa nên không ghi đè giá trị mới. */
    @Test
    void patchWaitsForRowLockAndKeepsConcurrentChange() throws Exception {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Nguyễn An", "0901234567", null, null, false);
        String token = open(account);

        List<ResponseEntity<Map<String, Object>>> responses = whileCustomerLocked(customer,
                () -> jdbc.update("UPDATE customers SET link_decision_pending = true, avatar_url = ? WHERE id = ?",
                        "https://img.test/concurrent.png", customer),
                List.of(() -> call(HttpMethod.PATCH, PATH, token, Map.of("fullName", "Tên mới"))));

        assertThat(data(responses.get(0), HttpStatus.OK)).containsEntry("fullName", "Tên mới")
                .containsEntry("linkDecisionPending", true)
                .containsEntry("avatarUrl", "https://img.test/concurrent.png");
        assertThat(row(customer)).containsEntry("full_name", "Tên mới").containsEntry("link_decision_pending", true)
                .containsEntry("avatar_url", "https://img.test/concurrent.png");
    }

    private Map<String, Object> row(long customerId) {
        return jdbc.queryForMap("SELECT * FROM customers WHERE id = ?", customerId);
    }
}
