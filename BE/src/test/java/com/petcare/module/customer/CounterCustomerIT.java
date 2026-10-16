package com.petcare.module.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;

/**
 * Hồ sơ khách tại quầy và tra cứu — {@code POST/GET /api/customers}, {@code PATCH /api/customers/{id}} (UC22, UC23;
 * customer-v1 #8, #9, #11; BR-KH-01, 10, BR-TK-16; docs/adr/0041) qua HTTP trên Postgres 17 thật. Khách và thú chèn bằng
 * SQL; mỗi test dùng SĐT / họ tên riêng nên không đụng dữ liệu của IT khác.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CounterCustomerIT extends CustomerMeItSupport {

    private static final String PATH = "/api/customers";
    private static final List<String> STAFF_ROLES = List.of("ADMIN", "SUPER_MANAGER", "BRANCH_MANAGER", "RECEPTIONIST",
            "VET", "CARETAKER");

    private PetFixtures fx;
    private String tag;
    private String phone;
    private String receptionist;
    private long receptionistId;

    @BeforeEach
    void setUp() {
        fx = new PetFixtures(jdbc, transactionManager);
        tag = UUID.randomUUID().toString().substring(0, 8);
        phone = "09" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
        receptionistId = staffOf("RECEPTIONIST");
        receptionist = open(receptionistId);
    }

    @AfterEach
    void tearDown() {
        fx.cleanup();
    }

    private String uniquePhone() {
        return "09" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
    }

    // ---------------------------------------------------------------- phân quyền

    @Test
    void onlyTheReceptionistCreatesAndEdits() {
        long customer = fx.track(counterProfile(null, "Khách " + tag, phone, null));
        Map<String, Object> create = Map.of("fullName", "Khách mới " + tag, "phone", uniquePhone());

        for (String role : STAFF_ROLES) {
            String token = open(staffOf(role));
            boolean allowed = role.equals("RECEPTIONIST");
            ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST, PATH, token, create);
            ResponseEntity<Map<String, Object>> edited = call(HttpMethod.PATCH, PATH + "/" + customer, token,
                    Map.of("fullName", "Sửa bởi " + role));
            if (allowed) {
                fx.track(number(map(data(created, HttpStatus.CREATED).get("customer")).get("customerId")));
                data(edited, HttpStatus.OK);
            } else {
                assertError(created, HttpStatus.FORBIDDEN, "ACCESS_DENIED");
                assertError(edited, HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            }
        }
        String customerToken = open(customerAccount(newEmail()));
        assertError(call(HttpMethod.POST, PATH, customerToken, create), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.POST, PATH, null, create), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(call(HttpMethod.PATCH, PATH + "/" + customer, null, Map.of()), HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED");
    }

    @Test
    void searchIsOpenToFrontDeskVetAndCaretakerOnly() {
        fx.track(counterProfile(null, "Khách " + tag, phone, null));
        String url = PATH + "?phone=" + phone;

        for (String role : STAFF_ROLES) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, url, open(staffOf(role)), null);
            if (List.of("RECEPTIONIST", "VET", "CARETAKER").contains(role)) {
                assertThat(response.getStatusCode()).as(role).isEqualTo(HttpStatus.OK);
            } else {
                assertError(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            }
        }
        assertError(call(HttpMethod.GET, url, open(customerAccount(newEmail())), null), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED");
        assertError(call(HttpMethod.GET, url, null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void staffWhoMustChangePasswordIsBlocked() {
        long id = staffOf("RECEPTIONIST");
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", id);

        assertRule(call(HttpMethod.GET, PATH + "?phone=" + phone, open(id), null), "BR-TK-17");
    }

    // ---------------------------------------------------------------- tạo

    @Test
    void createMatchesTheContractAndStoresACounterProfile() {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, PATH, receptionist, Map.of(
                "fullName", "  Nguyễn Thị " + tag + " ", "phone", phone, "email", "Lan." + tag + "@Gmail.com"));

        Map<String, Object> data = data(response, HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("message", "Đã tạo hồ sơ khách");
        assertThat(data).containsOnlyKeys("customer", "duplicatePhoneProfiles");
        assertThat(data.get("duplicatePhoneProfiles")).isEqualTo(List.of());
        Map<String, Object> customer = map(data.get("customer"));
        long id = fx.track(number(customer.get("customerId")));
        assertThat(customer).containsOnlyKeys("customerId", "fullName", "phone", "email", "avatarUrl", "createdChannel",
                        "hasAccount", "linkDecisionPending")
                .containsEntry("fullName", "Nguyễn Thị " + tag).containsEntry("phone", phone)
                .containsEntry("email", "lan." + tag + "@gmail.com").containsEntry("avatarUrl", null)
                .containsEntry("createdChannel", "COUNTER").containsEntry("hasAccount", false)
                .containsEntry("linkDecisionPending", false);
        assertThat(jdbc.queryForMap("SELECT * FROM customers WHERE id = ?", id))
                .containsEntry("created_channel", "COUNTER").containsEntry("account_id", null)
                .containsEntry("link_decision_pending", false);
        assertThat(number(jdbc.queryForMap("SELECT created_by FROM customers WHERE id = ?", id).get("created_by")))
                .isEqualTo(receptionistId);
    }

    @Test
    void createWithDuplicatePhoneStillSucceedsAndListsTheOthersMasked() {
        long online = fx.track(onlineProfile(customerAccount(newEmail()), "Khách mạng " + tag, phone,
                "nguyen.van@gmail.com", null, false));
        long counter = fx.track(counterProfile(null, "Khách quầy " + tag, phone, null));
        fx.pet(online, "Mực", "DOG");
        long deceased = fx.pet(online, "Cũ", "CAT");
        jdbc.update("UPDATE pets SET deceased_on = current_date WHERE id = ?", deceased);
        fx.track(counterProfile(null, "Khác SĐT " + tag, uniquePhone(), null));

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST, PATH, receptionist,
                Map.of("fullName", "Trùng " + tag, "phone", phone));

        Map<String, Object> data = data(response, HttpStatus.CREATED);
        fx.track(number(map(data.get("customer")).get("customerId")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> duplicates = (List<Map<String, Object>>) data.get("duplicatePhoneProfiles");
        assertThat(duplicates).extracting(d -> number(d.get("customerId"))).containsExactly(online, counter);
        Map<String, Object> first = duplicates.get(0);
        assertThat(first).containsOnlyKeys("customerId", "fullName", "phone", "maskedEmail", "createdChannel",
                        "hasAccount", "pets")
                .containsEntry("maskedEmail", "ng***@gmail.com").containsEntry("createdChannel", "ONLINE")
                .containsEntry("hasAccount", true);
        assertThat(first.toString()).doesNotContain("nguyen.van");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pets = (List<Map<String, Object>>) first.get("pets");
        assertThat(pets).extracting(p -> p.get("name")).containsExactly("Mực", "Cũ");
        assertThat(pets.get(0)).containsOnlyKeys("petId", "name", "species", "deceased").containsEntry("deceased", false);
        assertThat(pets.get(1)).containsEntry("deceased", true);
        assertThat(map(duplicates.get(1)).get("maskedEmail")).isNull();
    }

    @Test
    void duplicatesAreCappedAtTwenty() {
        for (int i = 0; i < 22; i++) {
            fx.track(counterProfile(null, "Cùng số " + tag + i, phone, null));
        }

        Map<String, Object> data = data(call(HttpMethod.POST, PATH, receptionist,
                Map.of("fullName", "Thứ 23 " + tag, "phone", phone)), HttpStatus.CREATED);
        fx.track(number(map(data.get("customer")).get("customerId")));

        assertThat((List<?>) data.get("duplicatePhoneProfiles")).hasSize(20);
    }

    @Test
    void createRejectsInvalidInputAndWritesNothing() {
        long before = jdbc.queryForObject("SELECT count(*) FROM customers", Long.class);
        List<Map<String, Object>> bodies = new ArrayList<>();
        bodies.add(Map.of("phone", phone));
        bodies.add(Map.of("fullName", "Tên " + tag));
        bodies.add(Map.of("fullName", "   ", "phone", phone));
        bodies.add(Map.of("fullName", "Tên", "phone", "12345"));
        bodies.add(Map.of("fullName", "Tên", "phone", "+84901234567"));
        bodies.add(Map.of("fullName", "Tên", "phone", phone, "email", "khong-hop-le"));
        bodies.add(Map.of("fullName", "x".repeat(101), "phone", phone));

        for (Map<String, Object> body : bodies) {
            assertError(call(HttpMethod.POST, PATH, receptionist, body), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        }
        assertError(callRaw(HttpMethod.POST, PATH, receptionist, "{bad"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers", Long.class)).isEqualTo(before);
    }

    // ---------------------------------------------------------------- tra cứu

    @Test
    void searchNeedsAtLeastOneCondition() {
        for (String url : new String[] {PATH, PATH + "?phone=", PATH + "?phone= &name=&email=&petName=",
                PATH + "?page=0&size=5"}) {
            assertError(call(HttpMethod.GET, url, receptionist, null), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        }
    }

    @Test
    void searchByEachCondition() {
        String email = "tim." + tag + "@gmail.com";
        long target = fx.track(counterProfile(null, "Trần Văn " + tag, phone, email));
        long other = fx.track(counterProfile(null, "Người khác " + tag, uniquePhone(), null));
        fx.pet(target, "Bông" + tag, "DOG");
        fx.pet(other, "Mực" + tag, "CAT");

        assertThat(ids(search("phone=" + phone))).containsExactly(target);
        assertThat(ids(search("name=trần văn " + tag.toUpperCase()))).as("không phân biệt hoa thường")
                .containsExactly(target);
        assertThat(ids(search("email=" + email.toUpperCase()))).as("email chuẩn hóa chữ thường").containsExactly(target);
        assertThat(ids(search("petName=bông" + tag))).containsExactly(target);
        assertThat(ids(search("name=" + tag))).containsExactlyInAnyOrder(target, other);
        assertThat(ids(search("name=" + tag + "&petName=Mực"))).containsExactly(other);
        assertThat(ids(search("name=" + tag + "&phone=" + phone))).containsExactly(target);
        assertThat(ids(search("phone=" + phone + "&petName=Mực" + tag))).isEmpty();
    }

    @Test
    void searchRowsMaskEmailAndListPets() {
        long target = fx.track(counterProfile(null, "Lê " + tag, phone, "le.thi@gmail.com"));
        long pet = fx.pet(target, "Mực", "DOG");

        Map<String, Object> item = search("phone=" + phone).get(0);

        assertThat(item).containsOnlyKeys("customerId", "fullName", "phone", "maskedEmail", "createdChannel",
                "hasAccount", "pets").containsEntry("maskedEmail", "le***@gmail.com");
        assertThat(item.toString()).doesNotContain("le.thi");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pets = (List<Map<String, Object>>) item.get("pets");
        assertThat(pets).singleElement().satisfies(p -> {
            assertThat(number(p.get("petId"))).isEqualTo(pet);
            assertThat(p).containsEntry("species", "DOG");
        });
    }

    @Test
    void searchTreatsWildcardsAsLiterals() {
        long literal = fx.track(counterProfile(null, "Giảm 50% " + tag, uniquePhone(), null));
        fx.track(counterProfile(null, "Giảm 500 " + tag, uniquePhone(), null));
        long underscore = fx.track(counterProfile(null, "A_B " + tag, uniquePhone(), null));
        fx.track(counterProfile(null, "AxB " + tag, uniquePhone(), null));

        assertThat(ids(search("name=50% " + tag))).containsExactly(literal);
        assertThat(ids(search("name=A_B " + tag))).containsExactly(underscore);
        assertThat(ids(search("name=%"))).as("chỉ khớp dòng có dấu %").contains(literal);
    }

    @Test
    void searchPagesAndClampsSize() {
        for (int i = 0; i < 3; i++) {
            fx.track(counterProfile(null, "Phân trang " + tag + " " + i, uniquePhone(), null));
        }

        Map<String, Object> first = data(call(HttpMethod.GET, PATH + "?name=" + tag + "&size=2&page=0", receptionist,
                null), HttpStatus.OK);
        Map<String, Object> second = data(call(HttpMethod.GET, PATH + "?name=" + tag + "&size=2&page=1",
                receptionist, null), HttpStatus.OK);
        Map<String, Object> clamped = data(call(HttpMethod.GET, PATH + "?name=" + tag + "&size=1000&page=-4",
                receptionist, null), HttpStatus.OK);

        assertThat(first).containsOnlyKeys("content", "page", "size", "totalElements", "totalPages")
                .containsEntry("totalElements", 3).containsEntry("totalPages", 2).containsEntry("size", 2);
        assertThat((List<?>) first.get("content")).hasSize(2);
        assertThat((List<?>) second.get("content")).hasSize(1);
        assertThat(clamped).containsEntry("size", 100).containsEntry("page", 0);
    }

    @Test
    void searchOrdersByNameThenId() {
        long b = fx.track(counterProfile(null, "B " + tag, uniquePhone(), null));
        long a = fx.track(counterProfile(null, "A " + tag, uniquePhone(), null));

        assertThat(ids(search("name=" + tag))).containsExactly(a, b);
    }

    @Test
    void searchByPhoneUsesTheIndex() {
        String plan = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            return String.join("\n", jdbc.queryForList(
                    "EXPLAIN SELECT id FROM customers c WHERE 1 = 1 AND c.phone = '0901234567' ORDER BY c.full_name, c.id",
                    String.class));
        });
        assertThat(plan).contains("ix_customers_phone");
    }

    // ---------------------------------------------------------------- sửa

    @Test
    void updateChangesOnlySentFieldsAndNullKeepsTheRest() {
        long id = fx.track(counterProfile(null, "Tên cũ " + tag, phone, "cu." + tag + "@x.vn"));

        Map<String, Object> data = data(call(HttpMethod.PATCH, PATH + "/" + id, receptionist,
                Map.of("fullName", "  Tên mới " + tag + " ")), HttpStatus.OK);

        assertThat(data).containsOnlyKeys("customer", "duplicatePhoneProfiles");
        assertThat(map(data.get("customer"))).containsEntry("fullName", "Tên mới " + tag)
                .containsEntry("phone", phone).containsEntry("email", "cu." + tag + "@x.vn");
        assertThat(data.get("duplicatePhoneProfiles")).isEqualTo(List.of());
    }

    @Test
    void updatePhoneToAnExistingNumberWarnsButSaves() {
        long id = fx.track(counterProfile(null, "Đổi số " + tag, uniquePhone(), null));
        long other = fx.track(counterProfile(null, "Đã có số " + tag, phone, null));

        Map<String, Object> data = data(call(HttpMethod.PATCH, PATH + "/" + id, receptionist,
                Map.of("phone", phone)), HttpStatus.OK);

        assertThat(map(data.get("customer"))).containsEntry("phone", phone);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> duplicates = (List<Map<String, Object>>) data.get("duplicatePhoneProfiles");
        assertThat(duplicates).extracting(d -> number(d.get("customerId"))).containsExactly(other);
        assertThat(jdbc.queryForObject("SELECT phone FROM customers WHERE id = ?", String.class, id)).isEqualTo(phone);
    }

    @Test
    void updateWithoutChangingPhoneDoesNotWarn() {
        long id = fx.track(counterProfile(null, "Giữ số " + tag, phone, null));
        fx.track(counterProfile(null, "Cùng số " + tag, phone, null));

        Map<String, Object> data = data(call(HttpMethod.PATCH, PATH + "/" + id, receptionist,
                Map.of("fullName", "Tên khác " + tag, "phone", phone)), HttpStatus.OK);

        assertThat(data.get("duplicatePhoneProfiles")).isEqualTo(List.of());
    }

    @Test
    void counterProfileCannotLoseItsPhoneButOnlineProfileCan() {
        long counter = fx.track(counterProfile(null, "Quầy " + tag, phone, null));
        long online = fx.track(onlineProfile(customerAccount(newEmail()), "Mạng " + tag, phone, null, null, false));

        assertRule(call(HttpMethod.PATCH, PATH + "/" + counter, receptionist, Map.of("phone", "")), "BR-KH-01");
        assertThat(jdbc.queryForObject("SELECT phone FROM customers WHERE id = ?", String.class, counter))
                .isEqualTo(phone);
        assertThat(map(data(call(HttpMethod.PATCH, PATH + "/" + online, receptionist, Map.of("phone", "")),
                HttpStatus.OK).get("customer")).get("phone")).isNull();
    }

    @Test
    void emailOfALinkedProfileIsOnlyChangeableThroughTheAccount() {
        String accountEmail = newEmail();
        long account = customerAccount(accountEmail);
        long id = fx.track(counterProfile(account, "Đã liên kết " + tag, phone, accountEmail));

        assertRule(call(HttpMethod.PATCH, PATH + "/" + id, receptionist, Map.of("email", "khac@x.vn")), "BR-TK-16");
        assertThat(jdbc.queryForObject("SELECT email FROM customers WHERE id = ?", String.class, id))
                .isEqualTo(accountEmail);
        data(call(HttpMethod.PATCH, PATH + "/" + id, receptionist, Map.of("email", accountEmail.toUpperCase())),
                HttpStatus.OK);
    }

    @Test
    void emailOfAnUnlinkedProfileCanBeSetAndCleared() {
        long id = fx.track(counterProfile(null, "Chưa liên kết " + tag, phone, null));

        assertThat(map(data(call(HttpMethod.PATCH, PATH + "/" + id, receptionist, Map.of("email", "Moi@X.vn")),
                HttpStatus.OK).get("customer")).get("email")).isEqualTo("moi@x.vn");
        assertThat(map(data(call(HttpMethod.PATCH, PATH + "/" + id, receptionist, Map.of("email", "")),
                HttpStatus.OK).get("customer")).get("email")).isNull();
    }

    @Test
    void updateRejectsInvalidInputAndMissingCustomer() {
        long id = fx.track(counterProfile(null, "Hợp lệ " + tag, phone, null));

        assertError(call(HttpMethod.PATCH, PATH + "/" + id, receptionist, Map.of("fullName", "  ")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.PATCH, PATH + "/" + id, receptionist, Map.of("phone", "123")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.PATCH, PATH + "/" + id, receptionist, Map.of("email", "x")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(call(HttpMethod.PATCH, PATH + "/999999999", receptionist, Map.of("fullName", "A")),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT full_name FROM customers WHERE id = ?", String.class, id))
                .isEqualTo("Hợp lệ " + tag);
    }

    @Test
    void concurrentEditsOfTheSameProfileAreSerialisedAndNeitherIsLost() throws Exception {
        long id = fx.track(counterProfile(null, "Song song " + tag, phone, null));
        String newPhone = uniquePhone();
        List<Callable<ResponseEntity<Map<String, Object>>>> requests = List.of(
                () -> call(HttpMethod.PATCH, PATH + "/" + id, receptionist, Map.of("fullName", "Tên song song " + tag)),
                () -> call(HttpMethod.PATCH, PATH + "/" + id, receptionist, Map.of("phone", newPhone)));

        List<ResponseEntity<Map<String, Object>>> responses = whileCustomerLocked(id, () -> {
        }, requests);

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK));
        assertThat(jdbc.queryForMap("SELECT full_name, phone FROM customers WHERE id = ?", id))
                .containsEntry("full_name", "Tên song song " + tag).containsEntry("phone", newPhone);
    }

    // ---------------------------------------------------------------- nội bộ

    private List<Map<String, Object>> search(String query) {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, PATH + "?" + query, receptionist, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) data(response, HttpStatus.OK).get("content");
        return content;
    }

    private static List<Long> ids(List<Map<String, Object>> items) {
        return items.stream().map(item -> number(item.get("customerId"))).toList();
    }
}
