package com.petcare.module.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.customer.repository.AddressRepository;
import com.petcare.module.identity.service.SystemConfigService;

/**
 * Sổ địa chỉ {@code /api/me/addresses} (UC06, customer-v1 #3–7; BR-TK-18; docs/adr/0028) qua HTTP trên Postgres 17
 * thật. Kiểm: tập khóa của hợp đồng, thứ tự (mặc định trước), địa chỉ đầu luôn mặc định, đúng 1 mặc định sau mọi thao
 * tác, giới hạn [CFG], không xóa mặc định khi còn địa chỉ khác, địa chỉ người khác 404 cùng message, lỗi không đổi dữ
 * liệu, bước sau lỗi thì rollback cả bước gỡ cờ trước, chạy song song dưới khóa {@code customers} (không vượt giới hạn,
 * không 409), chạy chéo với UC07, câu SQL dùng {@code ix_addresses_customer_id}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AddressBookIT extends CustomerMeItSupport {

    private static final String PATH = "/api/me/addresses";

    @Autowired
    private SystemConfigService configs;

    // ---------------------------------------------------------------- thêm, xem

    @Test
    void firstAddressIsDefaultEvenIfFalseAndMatchesContract() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, PATH, open(account), Map.of(
                "receiverName", " An ", "receiverPhone", "0901234567", "addressLine", " 12 Lê Lợi ", "ward", "  ",
                "province", " Hà Nội ", "isDefault", false));

        Map<String, Object> address = data(response, HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("message", "Đã thêm địa chỉ");
        assertThat(address).containsOnlyKeys("addressId", "receiverName", "receiverPhone", "addressLine", "ward",
                        "province", "isDefault")
                .containsEntry("receiverName", "An").containsEntry("receiverPhone", "0901234567")
                .containsEntry("addressLine", "12 Lê Lợi").containsEntry("ward", null)
                .containsEntry("province", "Hà Nội").containsEntry("isDefault", true);
        assertThat(jdbc.queryForMap("SELECT * FROM addresses WHERE id = ?", number(address.get("addressId"))))
                .containsEntry("customer_id", customer).containsEntry("receiver_name", "An")
                .containsEntry("ward", null).containsEntry("is_default", true);
    }

    @Test
    void listIsDefaultFirstThenByIdAndOnlyMine() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);
        long first = address(customer, "Một", false);
        long second = address(customer, "Hai", true);
        long third = address(customer, "Ba", false);
        long other = onlineProfile(customerAccount(newEmail()), "Người khác", null, null, null, false);
        address(other, "Của người khác", true);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, PATH, open(account), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) response.getBody().get("data");
        assertThat(list).extracting(item -> number(item.get("addressId"))).containsExactly(second, first, third);
    }

    @Test
    void emptyBookIsEmptyArray() {
        long account = customerAccount(newEmail());
        onlineProfile(account, "Khách", null, null, null, false);

        assertThat(call(HttpMethod.GET, PATH, open(account), null).getBody().get("data")).isEqualTo(List.of());
    }

    @Test
    void addWithIsDefaultMovesFlag() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);
        long old = address(customer, "Cũ", true);

        Map<String, Object> added = data(call(HttpMethod.POST, PATH, open(account), body(true)), HttpStatus.CREATED);

        assertThat(added).containsEntry("isDefault", true);
        assertThat(defaults(customer)).containsExactly(number(added.get("addressId")));
        assertThat(isDefault(old)).isFalse();
    }

    @Test
    void addNotDefaultKeepsCurrentDefault() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);
        long old = address(customer, "Cũ", true);

        assertThat(data(call(HttpMethod.POST, PATH, open(account), body(null)), HttpStatus.CREATED))
                .containsEntry("isDefault", false);
        assertThat(defaults(customer)).containsExactly(old);
    }

    @Test
    void addAtLimitIs400BrTk18AndNothingChanges() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);
        for (int i = 0; i < 5; i++) {
            address(customer, "Địa chỉ " + i, i == 0);
        }

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, PATH, open(account), body(true));

        assertRule(response, "BR-TK-18");
        assertThat(response.getBody()).containsEntry("message", "Sổ địa chỉ đã đủ 5 địa chỉ (BR-TK-18)");
        assertThat(count(customer)).isEqualTo(5);
        assertThat(defaults(customer)).hasSize(1);
    }

    @Test
    void limitFollowsConfigAndLowerLimitOnlyBlocksAdding() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);
        address(customer, "Một", true);
        long second = address(customer, "Hai", false);
        long third = address(customer, "Ba", false);
        String token = open(account);
        try {
            jdbc.update("UPDATE system_configs SET value = '2' WHERE key = 'address.max_per_customer'");
            configs.reload();

            assertThat((String) call(HttpMethod.POST, PATH, token, body(null)).getBody().get("message"))
                    .isEqualTo("Sổ địa chỉ đã đủ 2 địa chỉ (BR-TK-18)");
            assertThat(data(call(HttpMethod.POST, PATH + "/" + second + "/set-default", token, null), HttpStatus.OK))
                    .containsEntry("isDefault", true);
            assertThat(call(HttpMethod.DELETE, PATH + "/" + third, token, null).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
        } finally {
            jdbc.update("UPDATE system_configs SET value = '5' WHERE key = 'address.max_per_customer'");
            configs.reload();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"receiverPhone\": \"0901234567\", \"addressLine\": \"Số 1\", \"province\": \"Hà Nội\"}",
            "{\"receiverName\": \"  \", \"receiverPhone\": \"0901234567\", \"addressLine\": \"Số 1\", \"province\": \"HN\"}",
            "{\"receiverName\": \"An\", \"addressLine\": \"Số 1\", \"province\": \"Hà Nội\"}",
            "{\"receiverName\": \"An\", \"receiverPhone\": \"\", \"addressLine\": \"Số 1\", \"province\": \"HN\"}",
            "{\"receiverName\": \"An\", \"receiverPhone\": \"09012345678\", \"addressLine\": \"Số 1\", \"province\": \"HN\"}",
            "{\"receiverName\": \"An\", \"receiverPhone\": \"0901234567\", \"province\": \"Hà Nội\"}",
            "{\"receiverName\": \"An\", \"receiverPhone\": \"0901234567\", \"addressLine\": \"Số 1\"}",
            "{}"})
    void invalidAddBodyIs400AndNothingChanges(String body) {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);

        assertError(callRaw(HttpMethod.POST, PATH, open(account), body), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(count(customer)).isZero();
    }

    @Test
    void tooLongAndMalformedAre400() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);
        String token = open(account);

        assertError(call(HttpMethod.POST, PATH, token, Map.of("receiverName", "a".repeat(101), "receiverPhone",
                "0901234567", "addressLine", "Số 1", "province", "HN")), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.POST, PATH, token, Map.of("receiverName", "An", "receiverPhone", "0901234567",
                "addressLine", "a".repeat(301), "province", "HN")), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(callRaw(HttpMethod.POST, PATH, token, "{\"isDefault\": \"abc\"}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertError(callRaw(HttpMethod.POST, PATH, token, ""), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertThat(count(customer)).isZero();
    }

    // ---------------------------------------------------------------- sửa

    @Test
    void updateStripsKeepsNullsClearsWardAndIgnoresIsDefault() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);
        long id = address(customer, "Cũ", false);
        address(customer, "Mặc định", true);

        Map<String, Object> updated = data(call(HttpMethod.PATCH, PATH + "/" + id, open(account),
                Map.of("receiverName", " Bình ", "ward", "", "isDefault", true)), HttpStatus.OK);

        assertThat(updated).containsEntry("receiverName", "Bình").containsEntry("ward", null)
                .containsEntry("receiverPhone", "0901234567").containsEntry("isDefault", false);
        assertThat(isDefault(id)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"receiverName\": \"  \"}", "{\"addressLine\": \"\"}", "{\"province\": \" \"}",
            "{\"receiverPhone\": \"123\"}"})
    void updateBlankRequiredFieldIs400AndNothingChanges(String body) {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);
        long id = address(customer, "Cũ", true);
        Map<String, Object> before = addressRow(id);

        assertError(callRaw(HttpMethod.PATCH, PATH + "/" + id, open(account), body), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertThat(addressRow(id)).isEqualTo(before);
    }

    // ---------------------------------------------------------------- xóa

    @Test
    void deleteNonDefaultIs204WithoutBody() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);
        address(customer, "Mặc định", true);
        long id = address(customer, "Phụ", false);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.DELETE, PATH + "/" + id, open(account), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        assertThat(count(customer)).isEqualTo(1);
    }

    @Test
    void deleteDefaultWithOthersIs400BrTk18AndNothingChanges() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);
        long id = address(customer, "Mặc định", true);
        address(customer, "Phụ", false);

        assertRule(call(HttpMethod.DELETE, PATH + "/" + id, open(account), null), "BR-TK-18");
        assertThat(count(customer)).isEqualTo(2);
        assertThat(defaults(customer)).containsExactly(id);
    }

    @Test
    void deleteOnlyDefaultEmptiesBook() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);
        long id = address(customer, "Mặc định", true);

        assertThat(call(HttpMethod.DELETE, PATH + "/" + id, open(account), null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(count(customer)).isZero();
    }

    // ---------------------------------------------------------------- đặt mặc định

    @Test
    void setDefaultSwapsWithoutConflict() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);
        long old = address(customer, "Cũ", true);
        long id = address(customer, "Mới", false);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, PATH + "/" + id + "/set-default",
                open(account), null);

        assertThat(data(response, HttpStatus.OK)).containsEntry("isDefault", true);
        assertThat(response.getBody()).containsEntry("message", "Đã đặt địa chỉ mặc định");
        assertThat(defaults(customer)).containsExactly(id);
        assertThat(isDefault(old)).isFalse();
    }

    @Test
    void setDefaultOnDefaultIsNoOpAndTimestampsUnchanged() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);
        long id = address(customer, "Mặc định", true);
        Map<String, Object> before = addressRow(id);

        assertThat(data(call(HttpMethod.POST, PATH + "/" + id + "/set-default", open(account), null), HttpStatus.OK))
                .containsEntry("isDefault", true);
        assertThat(addressRow(id)).isEqualTo(before);
    }

    // ---------------------------------------------------------------- 404, 400 path, quyền

    @Test
    void othersAddressIs404SameMessageAsMissingAndNothingChanges() {
        long account = customerAccount(newEmail());
        onlineProfile(account, "Khách", null, null, null, false);
        long other = onlineProfile(customerAccount(newEmail()), "Người khác", null, null, null, false);
        long foreign = address(other, "Của người khác", true);
        Map<String, Object> before = addressRow(foreign);
        long missing = foreign + 1_000_000;
        String token = open(account);

        for (long id : List.of(foreign, missing)) {
            ResponseEntity<Map<String, Object>> patch = call(HttpMethod.PATCH, PATH + "/" + id, token,
                    Map.of("receiverName", "Chiếm"));
            assertError(patch, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
            assertThat(patch.getBody()).containsEntry("message", "Không tìm thấy Địa chỉ #" + id);
            assertError(call(HttpMethod.DELETE, PATH + "/" + id, token, null), HttpStatus.NOT_FOUND,
                    "RESOURCE_NOT_FOUND");
            assertError(call(HttpMethod.POST, PATH + "/" + id + "/set-default", token, null), HttpStatus.NOT_FOUND,
                    "RESOURCE_NOT_FOUND");
        }
        assertThat(addressRow(foreign)).isEqualTo(before);
    }

    @Test
    void addressIdNotNumberIs400() {
        long account = customerAccount(newEmail());
        onlineProfile(account, "Khách", null, null, null, false);

        assertError(call(HttpMethod.DELETE, PATH + "/abc", open(account), null), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
    }

    @Test
    void staffIs403AndWithoutTokenIs401() {
        String staff = open(staffAccount());

        assertError(call(HttpMethod.GET, PATH, staff, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.POST, PATH, staff, body(null)), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.DELETE, PATH + "/1", staff, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.GET, PATH, null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void pendingLinkFlagDoesNotBlockAddressBook() {
        long account = customerAccount(newEmail());
        onlineProfile(account, "Khách", null, null, null, true);

        data(call(HttpMethod.POST, PATH, open(account), body(null)), HttpStatus.CREATED);
    }

    // ---------------------------------------------------------------- rollback (docs/adr/0028)

    /** INSERT địa chỉ mặc định mới lỗi sau khi đã gỡ cờ cũ: rollback cả bước gỡ cờ, sổ không mất mặc định. */
    @Test
    void failedInsertKeepsOldDefault() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);
        long old = address(customer, "Cũ", true);
        String token = open(account);
        Map<String, Object> before = addressRow(old);

        withTrigger("addresses", "BEFORE INSERT", "NEW.customer_id = " + customer,
                () -> assertError(call(HttpMethod.POST, PATH, token, body(true)), HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL_ERROR"));

        assertThat(count(customer)).isEqualTo(1);
        assertThat(addressRow(old)).isEqualTo(before);
    }

    /** Ghi cờ mới lỗi sau khi đã gỡ cờ cũ: rollback cả bước gỡ cờ. */
    @Test
    void failedSetDefaultKeepsOldDefault() {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);
        long old = address(customer, "Cũ", true);
        long id = address(customer, "Mới", false);
        String token = open(account);

        withTrigger("addresses", "BEFORE UPDATE", "NEW.id = " + id + " AND NEW.is_default",
                () -> assertError(call(HttpMethod.POST, PATH + "/" + id + "/set-default", token, null),
                        HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR"));

        assertThat(defaults(customer)).containsExactly(old);
    }

    // ---------------------------------------------------------------- đồng thời (khóa dòng customers)

    @Test
    void concurrentAddsNeverExceedMax() throws Exception {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);
        for (int i = 0; i < 4; i++) {
            address(customer, "Địa chỉ " + i, i == 0);
        }
        String token = open(account);

        List<ResponseEntity<Map<String, Object>>> responses = whileCustomerLocked(customer, () -> {
        }, List.of(() -> call(HttpMethod.POST, PATH, token, body(true)),
                () -> call(HttpMethod.POST, PATH, token, body(true))));

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.BAD_REQUEST);
        responses.stream().filter(r -> r.getStatusCode() == HttpStatus.BAD_REQUEST)
                .forEach(r -> assertRule(r, "BR-TK-18"));
        assertThat(count(customer)).isEqualTo(5);
        assertThat(defaults(customer)).hasSize(1);
    }

    @Test
    void concurrentSetDefaultLeavesExactlyOne() throws Exception {
        long account = customerAccount(newEmail());
        long customer = onlineProfile(account, "Khách", null, null, null, false);
        address(customer, "Cũ", true);
        long a = address(customer, "A", false);
        long b = address(customer, "B", false);
        String token = open(account);

        List<ResponseEntity<Map<String, Object>>> responses = whileCustomerLocked(customer, () -> {
        }, List.of(() -> call(HttpMethod.POST, PATH + "/" + a + "/set-default", token, null),
                () -> call(HttpMethod.POST, PATH + "/" + b + "/set-default", token, null)));

        assertThat(responses).extracting(ResponseEntity::getStatusCode).containsOnly(HttpStatus.OK);
        assertThat(defaults(customer)).hasSize(1).containsAnyOf(a, b);
    }

    /**
     * UC07 chạy chéo (ADR-0027): request chờ khóa hồ sơ online O; trong lúc đó luồng liên kết xóa sổ địa chỉ + O và gán
     * tài khoản vào hồ sơ tại quầy C. Request đọc lại theo snapshot mới và ghi vào C, không 500.
     */
    @Test
    void addAfterConcurrentLinkLandsOnCounterProfile() throws Exception {
        long account = customerAccount(newEmail());
        long online = onlineProfile(account, "Khách", "0901234567", null, null, true);
        address(online, "Địa chỉ online", true);
        long counter = counterProfile(null, "Hồ sơ quầy", "0901234567", "quay@petcare.test");
        String token = open(account);

        List<ResponseEntity<Map<String, Object>>> responses = whileCustomerLocked(online, () -> {
            jdbc.update("DELETE FROM addresses WHERE customer_id = ?", online);
            jdbc.update("DELETE FROM customers WHERE id = ?", online);
            jdbc.update("UPDATE customers SET account_id = ? WHERE id = ?", account, counter);
        }, List.of(() -> call(HttpMethod.POST, PATH, token, body(false))));

        Map<String, Object> added = data(responses.get(0), HttpStatus.CREATED);
        assertThat(added).as("sổ của C đang rỗng nên là mặc định").containsEntry("isDefault", true);
        assertThat(jdbc.queryForObject("SELECT customer_id FROM addresses WHERE id = ?", Long.class,
                number(added.get("addressId")))).isEqualTo(counter);
    }

    /** UC07 xóa hồ sơ online cùng sổ địa chỉ của nó (ADR-0027 mục 11): địa chỉ khách vừa thêm không chặn việc xóa. */
    @Test
    void linkAfterAddressDeletesItWithProfile() {
        long account = customerAccount(newEmail());
        long online = onlineProfile(account, "Khách", null, null, null, false);
        data(call(HttpMethod.POST, PATH, open(account), body(null)), HttpStatus.CREATED);

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    jdbc.update("DELETE FROM addresses WHERE customer_id = ?", online);
                    jdbc.update("DELETE FROM customers WHERE id = ?", online);
                });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE id = ?", Long.class, online)).isZero();
    }

    // ---------------------------------------------------------------- index (V10)

    @Test
    void queriesUseCustomerIndex() throws NoSuchMethodException {
        jdbc.update("""
                INSERT INTO customers (full_name, phone, created_channel)
                SELECT 'AddressBookIT index ' || g, '0900000000', 'COUNTER' FROM generate_series(1, 1000) g
                """);
        try {
            jdbc.update("""
                    INSERT INTO addresses (customer_id, receiver_name, receiver_phone, address_line, province,
                                           is_default)
                    SELECT c.id, 'R', '0900000000', 'L', 'P', g = 1
                    FROM customers c CROSS JOIN generate_series(1, 2) g
                    WHERE c.full_name LIKE 'AddressBookIT index %'
                    """);
            jdbc.execute("ANALYZE addresses");
            long sample = jdbc.queryForObject(
                    "SELECT min(id) FROM customers WHERE full_name LIKE 'AddressBookIT index %'", Long.class);

            for (String method : List.of("findOwnedBy", "countOwnedBy")) {
                String sql = AddressRepository.class.getMethod(method, Long.class).getAnnotation(Query.class).value()
                        .replace(":customerId", "?");
                String plan = String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class, sample));
                assertThat(plan).as(method).contains("ix_addresses_customer_id");
            }
        } finally {
            jdbc.update("""
                    DELETE FROM addresses WHERE customer_id IN
                        (SELECT id FROM customers WHERE full_name LIKE 'AddressBookIT index %')""");
            jdbc.update("DELETE FROM customers WHERE full_name LIKE 'AddressBookIT index %'");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> body(Boolean isDefault) {
        Map<String, Object> body = new HashMap<>(Map.of("receiverName", "An", "receiverPhone", "0901234567",
                "addressLine", "12 Lê Lợi", "province", "Hà Nội"));
        if (isDefault != null) {
            body.put("isDefault", isDefault);
        }
        return body;
    }

    private long count(long customerId) {
        return jdbc.queryForObject("SELECT count(*) FROM addresses WHERE customer_id = ?", Long.class, customerId);
    }

    private List<Long> defaults(long customerId) {
        return jdbc.queryForList("SELECT id FROM addresses WHERE customer_id = ? AND is_default", Long.class,
                customerId);
    }

    private boolean isDefault(long addressId) {
        return jdbc.queryForObject("SELECT is_default FROM addresses WHERE id = ?", Boolean.class, addressId);
    }

    private Map<String, Object> addressRow(long addressId) {
        return jdbc.queryForMap("SELECT * FROM addresses WHERE id = ?", addressId);
    }
}
