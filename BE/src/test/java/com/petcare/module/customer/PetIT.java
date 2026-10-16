package com.petcare.module.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;

/**
 * Thú cưng và cân nặng — {@code /api/me/pets}, {@code /api/customers/{id}/pets}, {@code GET /api/customers/{id}},
 * {@code /api/pets/{id}} (GET, PATCH), {@code /api/pets/{id}/weights} (UC24, UC25; customer-v1 #10, #12–16, #20, #21;
 * BR-KH-02…05, BR-TK-19; docs/adr/0041) qua HTTP trên Postgres 17 thật. Các dòng "giao dịch" của module khác chèn bằng
 * {@link PetFixtures} (bảng thật, đọc qua các query API đã cài).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PetIT extends CustomerMeItSupport {

    private static final Map<String, Object> DOG = Map.of("name", "Mực", "species", "DOG", "sex", "MALE");

    private PetFixtures fx;
    private String tag;
    private long account;
    private long customer;
    private String customerToken;
    private String receptionist;

    @BeforeEach
    void setUp() {
        fx = new PetFixtures(jdbc, transactionManager);
        tag = UUID.randomUUID().toString().substring(0, 8);
        account = customerAccount(newEmail());
        customer = fx.track(onlineProfile(account, "Khách " + tag, null, null, null, false));
        customerToken = open(account);
        receptionist = open(staffOf("RECEPTIONIST"));
    }

    @AfterEach
    void tearDown() {
        fx.cleanup();
    }

    private ResponseEntity<Map<String, Object>> asCustomer(HttpMethod method, String path, Object body) {
        return call(method, path, customerToken, body);
    }

    private ResponseEntity<Map<String, Object>> asReceptionist(HttpMethod method, String path, Object body) {
        return call(method, path, receptionist, body);
    }

    // ---------------------------------------------------------------- phân quyền

    @Test
    void roleMatrix() {
        long pet = fx.pet(customer, "Mực", "DOG");
        String path = "/api/pets/" + pet;
        for (String role : List.of("ADMIN", "SUPER_MANAGER", "BRANCH_MANAGER")) {
            String token = open(staffOf(role));
            assertError(call(HttpMethod.GET, path, token, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(call(HttpMethod.GET, "/api/customers/" + customer, token, null), HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED");
            assertError(call(HttpMethod.GET, path + "/weights", token, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(call(HttpMethod.PATCH, path, token, Map.of()), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        for (String role : List.of("VET", "CARETAKER")) {
            String token = open(staffOf(role));
            assertThat(call(HttpMethod.GET, path, token, null).getStatusCode()).as(role).isEqualTo(HttpStatus.OK);
            assertThat(call(HttpMethod.GET, path + "/weights", token, null).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(call(HttpMethod.GET, "/api/customers/" + customer, token, null).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertError(call(HttpMethod.PATCH, path, token, Map.of()), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            assertError(call(HttpMethod.POST, "/api/customers/" + customer + "/pets", token, DOG),
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        assertError(call(HttpMethod.GET, "/api/me/pets", receptionist, null), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.POST, "/api/me/pets", receptionist, DOG), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.POST, path + "/weights", receptionist, Map.of("weightKg", 5)),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(call(HttpMethod.GET, path, null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertError(call(HttpMethod.GET, "/api/me/pets", null, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void customerWhoMustChangePasswordIsBlocked() {
        jdbc.update("UPDATE accounts SET must_change_password = true WHERE id = ?", account);

        assertRule(asCustomer(HttpMethod.GET, "/api/me/pets", null), "BR-TK-17");
    }

    // ---------------------------------------------------------------- khách thêm, xem

    @Test
    void customerCreatesAPetAndListsItWithTheContractShape() {
        ResponseEntity<Map<String, Object>> response = asCustomer(HttpMethod.POST, "/api/me/pets", Map.of(
                "name", "  Mực " + tag + " ", "species", "DOG", "sex", "FEMALE", "breed", "Poodle",
                "birthDate", "2024-03-15", "birthDateEstimated", true, "color", "Nâu", "isNeutered", false,
                "photoUrl", "https://cdn.petcare.vn/p.jpg"));

        Map<String, Object> data = data(response, HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("message", "Đã thêm thú cưng");
        assertThat(data).containsOnlyKeys("pet", "duplicateNameWarning").containsEntry("duplicateNameWarning", false);
        Map<String, Object> pet = map(data.get("pet"));
        assertThat(pet).containsOnlyKeys("petId", "customerId", "name", "species", "sex", "breed", "birthDate",
                        "birthDateEstimated", "color", "isNeutered", "photoUrl", "deceasedOn", "speciesLocked",
                        "currentWeightKg", "deletable")
                .containsEntry("customerId", Math.toIntExact(customer)).containsEntry("name", "Mực " + tag)
                .containsEntry("species", "DOG").containsEntry("sex", "FEMALE").containsEntry("breed", "Poodle")
                .containsEntry("birthDate", "2024-03-15").containsEntry("birthDateEstimated", true)
                .containsEntry("color", "Nâu").containsEntry("isNeutered", false)
                .containsEntry("deceasedOn", null).containsEntry("speciesLocked", false)
                .containsEntry("currentWeightKg", null).containsEntry("deletable", true);
        assertThat(number(pet.get("petId"))).isPositive();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mine = (List<Map<String, Object>>) asCustomer(HttpMethod.GET, "/api/me/pets", null)
                .getBody().get("data");
        assertThat(mine).extracting(p -> p.get("name")).containsExactly("Mực " + tag);
    }

    @Test
    void optionalFieldsDefaultAndBlankOnesAreStoredAsNull() {
        Map<String, Object> pet = map(data(asCustomer(HttpMethod.POST, "/api/me/pets", Map.of("name", "Mèo",
                "species", "CAT", "sex", "UNKNOWN", "breed", "  ", "color", "")), HttpStatus.CREATED).get("pet"));

        assertThat(pet).containsEntry("breed", null).containsEntry("color", null).containsEntry("isNeutered", null)
                .containsEntry("birthDateEstimated", false);
    }

    @Test
    void sameNameAndSpeciesWarnsButDifferentSpeciesDoesNot() {
        fx.pet(customer, "Mực", "DOG");

        assertThat(data(asCustomer(HttpMethod.POST, "/api/me/pets", Map.of("name", "mực", "species", "DOG",
                "sex", "MALE")), HttpStatus.CREATED)).containsEntry("duplicateNameWarning", true);
        assertThat(data(asCustomer(HttpMethod.POST, "/api/me/pets", Map.of("name", "Mực", "species", "CAT",
                "sex", "MALE")), HttpStatus.CREATED)).containsEntry("duplicateNameWarning", false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pets WHERE customer_id = ?", Long.class, customer))
                .isEqualTo(3L);
    }

    @Test
    void createRejectsBadInputAndWritesNothing() {
        for (Map<String, Object> body : List.of(
                Map.<String, Object>of("species", "DOG", "sex", "MALE"),
                Map.<String, Object>of("name", "A", "sex", "MALE"),
                Map.<String, Object>of("name", "A", "species", "DOG"),
                Map.<String, Object>of("name", " ", "species", "DOG", "sex", "MALE"),
                Map.<String, Object>of("name", "x".repeat(101), "species", "DOG", "sex", "MALE"),
                Map.<String, Object>of("name", "A", "species", "DOG", "sex", "MALE", "photoUrl", "http://x.vn/a.jpg"))) {
            assertError(asCustomer(HttpMethod.POST, "/api/me/pets", body), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        }
        assertError(asCustomer(HttpMethod.POST, "/api/me/pets", Map.of("name", "A", "species", "BIRD", "sex",
                "MALE")), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(asCustomer(HttpMethod.POST, "/api/me/pets", Map.of("name", "A", "species", "DOG", "sex", "MALE",
                "birthDate", "15/03/2024")), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pets WHERE customer_id = ?", Long.class, customer))
                .isZero();
    }

    @Test
    void birthDateInTheFutureIsBrKh02ButTodayIsFine() {
        String tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(1).toString();
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toString();

        assertRule(asCustomer(HttpMethod.POST, "/api/me/pets", withBirthDate(tomorrow)), "BR-KH-02");
        data(asCustomer(HttpMethod.POST, "/api/me/pets", withBirthDate(today)), HttpStatus.CREATED);
    }

    private static Map<String, Object> withBirthDate(String date) {
        return Map.of("name", "Mới sinh", "species", "DOG", "sex", "MALE", "birthDate", date);
    }

    // ---------------------------------------------------------------- BR-TK-19

    @Test
    void customerWithAPendingLinkDecisionCannotManagePets() {
        jdbc.update("UPDATE customers SET link_decision_pending = true WHERE id = ?", customer);
        long pet = fx.pet(customer, "Mực", "DOG");

        assertRule(asCustomer(HttpMethod.GET, "/api/me/pets", null), "BR-TK-19");
        assertRule(asCustomer(HttpMethod.POST, "/api/me/pets", DOG), "BR-TK-19");
        assertRule(asCustomer(HttpMethod.GET, "/api/pets/" + pet, null), "BR-TK-19");
        assertRule(asCustomer(HttpMethod.PATCH, "/api/pets/" + pet, Map.of("name", "Khác")), "BR-TK-19");
        assertRule(asCustomer(HttpMethod.GET, "/api/pets/" + pet + "/weights", null), "BR-TK-19");
        assertRule(asCustomer(HttpMethod.POST, "/api/pets/" + pet + "/weights", Map.of("weightKg", 5)), "BR-TK-19");

        assertThat(data(asReceptionist(HttpMethod.POST, "/api/customers/" + customer + "/pets", DOG),
                HttpStatus.CREATED)).containsKey("pet");
        assertThat(asReceptionist(HttpMethod.GET, "/api/pets/" + pet, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT name FROM pets WHERE id = ?", String.class, pet)).isEqualTo("Mực");
    }

    // ---------------------------------------------------------------- xem thú, chủ sở hữu

    @Test
    void anotherCustomersPetAndAMissingPetLookTheSame() {
        long otherAccount = customerAccount(newEmail());
        long other = fx.track(onlineProfile(otherAccount, "Người khác " + tag, null, null, null, false));
        long theirPet = fx.pet(other, "Của họ", "DOG");

        ResponseEntity<Map<String, Object>> foreign = asCustomer(HttpMethod.GET, "/api/pets/" + theirPet, null);
        ResponseEntity<Map<String, Object>> missing = asCustomer(HttpMethod.GET, "/api/pets/999999999", null);

        assertError(foreign, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(missing, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat((String) foreign.getBody().get("message")).isEqualTo("Không tìm thấy thú cưng #" + theirPet);
        assertThat((String) missing.getBody().get("message")).isEqualTo("Không tìm thấy thú cưng #999999999");
        assertError(asCustomer(HttpMethod.PATCH, "/api/pets/" + theirPet, Map.of("name", "Chiếm")),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertError(asCustomer(HttpMethod.GET, "/api/pets/" + theirPet + "/weights", null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
        assertError(asCustomer(HttpMethod.POST, "/api/pets/" + theirPet + "/weights", Map.of("weightKg", 5)),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT name FROM pets WHERE id = ?", String.class, theirPet))
                .isEqualTo("Của họ");
    }

    @Test
    void aFormerOwnerLosesAccessAtOnce() {
        long pet = fx.pet(customer, "Mực", "DOG");
        assertThat(asCustomer(HttpMethod.GET, "/api/pets/" + pet, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        long newOwner = fx.track(counterProfile(null, "Chủ mới " + tag, "0944000111", null));

        jdbc.update("UPDATE pets SET customer_id = ? WHERE id = ?", newOwner, pet);

        assertError(asCustomer(HttpMethod.GET, "/api/pets/" + pet, null), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(asCustomer(HttpMethod.GET, "/api/me/pets", null).getBody().get("data")).isEqualTo(List.of());
        assertThat(asReceptionist(HttpMethod.GET, "/api/pets/" + pet, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void staffOfAnyBranchSeeAnyPetBecauseTheChainShareTheRecords() {
        long pet = fx.pet(customer, "Mực", "DOG");
        long otherBranch = jdbc.queryForObject("""
                INSERT INTO branches (name, address, phone, latitude, longitude, status)
                VALUES (?, 'Địa chỉ', '0280000000', 10.76, 106.66, 'ACTIVE') RETURNING id
                """, Long.class, "PetIT chi nhánh khác " + UUID.randomUUID());
        long vet = staffOf("VET");
        jdbc.update("UPDATE staff_profiles SET branch_id = ? WHERE account_id = ?", otherBranch, vet);

        assertThat(call(HttpMethod.GET, "/api/pets/" + pet, open(vet), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- lễ tân thêm, chi tiết khách

    @Test
    void receptionistAddsAPetForTheCustomer() {
        ResponseEntity<Map<String, Object>> response = asReceptionist(HttpMethod.POST,
                "/api/customers/" + customer + "/pets", DOG);

        Map<String, Object> pet = map(data(response, HttpStatus.CREATED).get("pet"));
        assertThat(pet).containsEntry("customerId", Math.toIntExact(customer)).containsEntry("name", "Mực");
        assertError(asReceptionist(HttpMethod.POST, "/api/customers/999999999/pets", DOG), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
        assertError(asReceptionist(HttpMethod.POST, "/api/customers/" + customer + "/pets", Map.of("name", "A")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void customerDetailListsEveryPetIncludingDeceasedInIdOrder() {
        long first = fx.pet(customer, "Một", "DOG");
        long second = fx.pet(customer, "Hai", "CAT");
        jdbc.update("UPDATE pets SET deceased_on = current_date WHERE id = ?", first);

        Map<String, Object> data = data(asReceptionist(HttpMethod.GET, "/api/customers/" + customer, null),
                HttpStatus.OK);

        assertThat(data).containsOnlyKeys("customer", "pets");
        assertThat(map(data.get("customer"))).containsEntry("customerId", Math.toIntExact(customer))
                .containsEntry("createdChannel", "ONLINE").containsEntry("hasAccount", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pets = (List<Map<String, Object>>) data.get("pets");
        assertThat(pets).extracting(p -> number(p.get("petId"))).containsExactly(first, second);
        assertThat(pets.get(0)).containsEntry("deletable", false).containsKey("deceasedOn");
        assertError(asReceptionist(HttpMethod.GET, "/api/customers/999999999", null), HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND");
    }

    // ---------------------------------------------------------------- cờ suy ra

    @Test
    void speciesLockedAndDeletableFollowTheOtherModulesData() {
        long fresh = fx.pet(customer, "Mới", "DOG");
        long withVisit = fx.pet(customer, "Có Visit", "DOG");
        long withMedical = fx.pet(customer, "Có bệnh án", "DOG");
        long withAppointment = fx.pet(customer, "Có lịch", "DOG");
        long withBooking = fx.pet(customer, "Có đặt chỗ", "DOG");
        fx.visit(customer, withVisit, "COMPLETED");
        fx.medicalRecord(fx.visit(customer, withMedical, "COMPLETED"));
        fx.appointment(customer, withAppointment, "CANCELLED");
        fx.booking(customer, withBooking, "BOOKED", 10);

        assertThat(flags(fresh)).containsExactly(false, true);
        assertThat(flags(withVisit)).as("Visit chưa có bệnh án: chưa khóa loài nhưng không xóa được")
                .containsExactly(false, false);
        assertThat(flags(withMedical)).containsExactly(true, false);
        assertThat(flags(withAppointment)).containsExactly(false, false);
        assertThat(flags(withBooking)).containsExactly(false, false);
    }

    private List<Boolean> flags(long petId) {
        Map<String, Object> pet = data(asReceptionist(HttpMethod.GET, "/api/pets/" + petId, null), HttpStatus.OK);
        return List.of((Boolean) pet.get("speciesLocked"), (Boolean) pet.get("deletable"));
    }

    // ---------------------------------------------------------------- sửa

    @Test
    void ownerAndReceptionistEditAndNullKeepsTheRest() {
        long pet = fx.pet(customer, "Mực", "DOG");
        jdbc.update("UPDATE pets SET breed = 'Poodle', color = 'Nâu', photo_url = 'https://x.vn/a.jpg' WHERE id = ?",
                pet);

        Map<String, Object> byOwner = data(asCustomer(HttpMethod.PATCH, "/api/pets/" + pet,
                Map.of("name", "  Mực con ", "isNeutered", true)), HttpStatus.OK);
        assertThat(byOwner).containsOnlyKeys("pet", "duplicateNameWarning");
        assertThat(map(byOwner.get("pet"))).containsEntry("name", "Mực con").containsEntry("isNeutered", true)
                .containsEntry("breed", "Poodle").containsEntry("color", "Nâu")
                .containsEntry("photoUrl", "https://x.vn/a.jpg");

        Map<String, Object> byStaff = data(asReceptionist(HttpMethod.PATCH, "/api/pets/" + pet,
                Map.of("breed", "", "color", "", "photoUrl", "")), HttpStatus.OK);
        assertThat(map(byStaff.get("pet"))).containsEntry("breed", null).containsEntry("color", null)
                .containsEntry("photoUrl", null).containsEntry("name", "Mực con");
    }

    @Test
    void speciesCanBeChangedOnlyUntilThereIsMedicalHistory() {
        long pet = fx.pet(customer, "Mực", "DOG");

        assertThat(map(data(asCustomer(HttpMethod.PATCH, "/api/pets/" + pet, Map.of("species", "CAT")),
                HttpStatus.OK).get("pet"))).containsEntry("species", "CAT");

        fx.vaccination(pet, fx.visit(customer, pet, "COMPLETED"));
        assertRule(asCustomer(HttpMethod.PATCH, "/api/pets/" + pet, Map.of("species", "DOG")), "BR-KH-03");
        assertThat(jdbc.queryForObject("SELECT species FROM pets WHERE id = ?", String.class, pet)).isEqualTo("CAT");
        assertThat(map(data(asCustomer(HttpMethod.PATCH, "/api/pets/" + pet, Map.of("species", "CAT", "name",
                "Mèo")), HttpStatus.OK).get("pet"))).containsEntry("speciesLocked", true)
                .containsEntry("name", "Mèo");
    }

    @Test
    void deceasedPetCannotBeEdited() {
        long pet = fx.pet(customer, "Mực", "DOG");
        jdbc.update("UPDATE pets SET deceased_on = current_date WHERE id = ?", pet);

        assertRule(asCustomer(HttpMethod.PATCH, "/api/pets/" + pet, Map.of("name", "Khác")), "BR-KH-05");
        assertRule(asReceptionist(HttpMethod.PATCH, "/api/pets/" + pet, Map.of("name", "Khác")), "BR-KH-05");
        assertThat(jdbc.queryForObject("SELECT name FROM pets WHERE id = ?", String.class, pet)).isEqualTo("Mực");
    }

    @Test
    void updateRejectsBadInputAndKeepsTheRow() {
        long pet = fx.pet(customer, "Mực", "DOG");
        String tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(1).toString();

        assertError(asCustomer(HttpMethod.PATCH, "/api/pets/" + pet, Map.of("name", "  ")), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(asCustomer(HttpMethod.PATCH, "/api/pets/" + pet, Map.of("species", "BIRD")),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertRule(asCustomer(HttpMethod.PATCH, "/api/pets/" + pet, Map.of("birthDate", tomorrow)), "BR-KH-02");
        assertThat(jdbc.queryForMap("SELECT name, species, birth_date FROM pets WHERE id = ?", pet))
                .containsEntry("name", "Mực").containsEntry("species", "DOG").containsEntry("birth_date", null);
    }

    @Test
    void renamingToAnExistingNameWarns() {
        fx.pet(customer, "Bông", "DOG");
        long pet = fx.pet(customer, "Mực", "DOG");

        assertThat(data(asCustomer(HttpMethod.PATCH, "/api/pets/" + pet, Map.of("name", "bông")), HttpStatus.OK))
                .containsEntry("duplicateNameWarning", true);
        assertThat(data(asCustomer(HttpMethod.PATCH, "/api/pets/" + pet, Map.of("breed", "Poodle")), HttpStatus.OK))
                .containsEntry("duplicateNameWarning", false);
    }

    // ---------------------------------------------------------------- cân nặng

    @Test
    void ownerWeightIsRecordedAndShowsAsTheCurrentWeight() {
        long pet = fx.pet(customer, "Mực", "DOG");

        ResponseEntity<Map<String, Object>> response = asCustomer(HttpMethod.POST, "/api/pets/" + pet + "/weights",
                Map.of("weightKg", 8.5));

        Map<String, Object> record = data(response, HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("message", "Đã ghi cân nặng");
        assertThat(record).containsOnlyKeys("weightRecordId", "weightKg", "source", "visitId", "boardingBookingId",
                        "measuredAt")
                .containsEntry("source", "OWNER").containsEntry("visitId", null)
                .containsEntry("boardingBookingId", null);
        assertThat(((Number) record.get("weightKg")).doubleValue()).isEqualTo(8.5);
        assertThat(jdbc.queryForMap("SELECT source, recorded_by FROM weight_records WHERE id = ?",
                number(record.get("weightRecordId")))).containsEntry("source", "OWNER")
                .containsEntry("recorded_by", account);
        assertThat(((Number) map(data(asCustomer(HttpMethod.GET, "/api/pets/" + pet, null), HttpStatus.OK))
                .get("currentWeightKg")).doubleValue()).isEqualTo(8.5);
    }

    @Test
    void weightHistoryIsNewestFirstAcrossSources() {
        long pet = fx.pet(customer, "Mực", "DOG");
        fx.weight(pet, "7.00", "VET", "2026-09-01T03:00:00Z");
        fx.weight(pet, "8.00", "OWNER", "2026-10-01T03:00:00Z");
        fx.weight(pet, "7.50", "INTAKE", "2026-09-15T03:00:00Z");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) asReceptionist(HttpMethod.GET,
                "/api/pets/" + pet + "/weights", null).getBody().get("data");

        assertThat(list).extracting(w -> w.get("source")).containsExactly("OWNER", "INTAKE", "VET");
        assertThat(list).extracting(w -> ((Number) w.get("weightKg")).doubleValue()).containsExactly(8.0, 7.5, 7.0);
        assertThat(asCustomer(HttpMethod.GET, "/api/pets/" + pet + "/weights", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void backDatedWeightDoesNotReplaceANewerCurrentWeight() {
        long pet = fx.pet(customer, "Mực", "DOG");
        data(asCustomer(HttpMethod.POST, "/api/pets/" + pet + "/weights",
                Map.of("weightKg", 9, "measuredAt", "2026-10-10T03:00:00Z")), HttpStatus.CREATED);
        data(asCustomer(HttpMethod.POST, "/api/pets/" + pet + "/weights",
                Map.of("weightKg", 6, "measuredAt", "2026-01-10T03:00:00Z")), HttpStatus.CREATED);

        assertThat(((Number) data(asCustomer(HttpMethod.GET, "/api/pets/" + pet, null), HttpStatus.OK)
                .get("currentWeightKg")).doubleValue()).isEqualTo(9.0);
    }

    @Test
    void invalidWeightsAreRejected() {
        long pet = fx.pet(customer, "Mực", "DOG");
        String path = "/api/pets/" + pet + "/weights";
        String future = java.time.Instant.now().plusSeconds(3_600).toString();

        assertRule(asCustomer(HttpMethod.POST, path, Map.of("weightKg", 0)), "BR-KH-04");
        assertRule(asCustomer(HttpMethod.POST, path, Map.of("weightKg", -2.5)), "BR-KH-04");
        assertRule(asCustomer(HttpMethod.POST, path, Map.of("weightKg", 5, "measuredAt", future)), "BR-KH-04");
        assertError(asCustomer(HttpMethod.POST, path, Map.of()), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(asCustomer(HttpMethod.POST, path, Map.of("weightKg", 10000)), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(asCustomer(HttpMethod.POST, path, Map.of("weightKg", 1.234)), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        assertError(callRaw(HttpMethod.POST, path, customerToken, "{\"weightKg\":\"abc\"}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weight_records WHERE pet_id = ?", Long.class, pet))
                .isZero();
    }

    @Test
    void weightOfADeceasedPetIsRefused() {
        long pet = fx.pet(customer, "Mực", "DOG");
        jdbc.update("UPDATE pets SET deceased_on = current_date WHERE id = ?", pet);

        assertRule(asCustomer(HttpMethod.POST, "/api/pets/" + pet + "/weights", Map.of("weightKg", 5)), "BR-KH-05");
    }

    // ---------------------------------------------------------------- đồng thời

    @Test
    void creatingAPetWaitsForTheCustomerRowLockThenSucceeds() throws Exception {
        List<Callable<ResponseEntity<Map<String, Object>>>> requests = List.of(
                () -> asCustomer(HttpMethod.POST, "/api/me/pets", DOG),
                () -> asReceptionist(HttpMethod.POST, "/api/customers/" + customer + "/pets", DOG));

        List<ResponseEntity<Map<String, Object>>> responses = whileCustomerLocked(customer, () -> {
        }, requests);

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pets WHERE customer_id = ?", Long.class, customer))
                .isEqualTo(2L);
    }

    @Test
    void concurrentOwnerWeightsAreBothRecorded() {
        long pet = fx.pet(customer, "Mực", "DOG");
        String path = "/api/pets/" + pet + "/weights";

        var first = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> asCustomer(HttpMethod.POST, path, Map.of("weightKg", 5)));
        var second = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> asCustomer(HttpMethod.POST, path, Map.of("weightKg", 6)));

        assertThat(first.join().getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.join().getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weight_records WHERE pet_id = ?", Long.class, pet))
                .isEqualTo(2L);
    }
}
