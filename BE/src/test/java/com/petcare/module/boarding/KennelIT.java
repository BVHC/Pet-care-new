package com.petcare.module.boarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;

/** Chuồng (boarding-v1 #1–6, UC54; docs/adr/0065) qua HTTP thật. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BoardingClockConfig.class})
class KennelIT extends BoardingItSupport {

    private long branch;
    private long type;
    private String manager;

    @BeforeEach
    void setUp() {
        branch = newBranch();
        type = newKennelType(branch, "DOG", "30", 150_000);
        manager = token(newStaff("BRANCH_MANAGER", branch));
    }

    private ResponseEntity<Map<String, Object>> create(String token, long kennelType, String code) {
        return call(HttpMethod.POST, "/api/kennels", token,
                Map.of("kennelTypeId", kennelType, "code", code, "note", "Gần cửa sổ"));
    }

    @Test
    void managerCreatesListsUpdatesDeletesKennel() {
        Map<String, Object> created = data(create(manager, type, " C-01 "), HttpStatus.CREATED);
        long id = number(created.get("kennelId"));
        assertThat(created).containsEntry("code", "C-01").containsEntry("status", "AVAILABLE")
                .containsEntry("note", "Gần cửa sổ");
        assertThat(number(created.get("branchId"))).isEqualTo(branch);
        assertThat(created.get("kennelTypeName")).isNotNull();
        long other = newKennelType(branch, "CAT", "8", 100_000);
        create(manager, other, "C-02");

        assertThat(list(call(HttpMethod.GET, "/api/kennels", manager, null), HttpStatus.OK)).hasSize(2);
        assertThat(list(call(HttpMethod.GET, "/api/kennels?kennelTypeId=" + type, manager, null), HttpStatus.OK))
                .extracting(k -> k.get("code")).containsExactly("C-01");
        assertThat(list(call(HttpMethod.GET, "/api/kennels?status=MAINTENANCE", manager, null), HttpStatus.OK))
                .isEmpty();

        Map<String, Object> updated = data(call(HttpMethod.PATCH, "/api/kennels/" + id, manager,
                Map.of("code", "C-09", "note", "Mới")), HttpStatus.OK);
        assertThat(updated).containsEntry("code", "C-09").containsEntry("note", "Mới");

        assertThat(call(HttpMethod.DELETE, "/api/kennels/" + id, manager, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kennels WHERE id = ?", Long.class, id)).isZero();
    }

    @Test
    void duplicateCodeIsBrLt01() {
        create(manager, type, "C-01");
        long second = number(data(create(manager, type, "C-02"), HttpStatus.CREATED).get("kennelId"));

        assertRule(create(manager, type, "C-01"), "BR-LT-01");
        assertRule(call(HttpMethod.PATCH, "/api/kennels/" + second, manager, Map.of("code", "C-01")), "BR-LT-01");
    }

    @Test
    void concurrentDuplicateCodeCreatesOneAndRejectsTheOtherWithBrLt01() {
        List<Supplier<ResponseEntity<Map<String, Object>>>> calls = List.of(
                () -> create(manager, type, "C-77"), () -> create(manager, type, "C-77"));

        List<ResponseEntity<Map<String, Object>>> results = racedUnderBranchLock(branch, calls);

        assertThat(results).extracting(r -> r.getStatusCode().value()).containsExactlyInAnyOrder(201, 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kennels WHERE branch_id = ? AND code = 'C-77'",
                Long.class, branch)).isEqualTo(1L);
    }

    @Test
    void disabledOrUnknownKennelTypeIsRejected() {
        long disabled = newKennelType(branch, "DOG", "30", 100_000);
        jdbc.update("UPDATE branch_services SET is_enabled = false WHERE branch_id = ? AND service_id = ?", branch,
                disabled);

        assertRule(create(manager, disabled, "C-03"), "BR-LT-01");
        assertThat(create(manager, 9_999_999L, "C-04").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void startMaintenanceListsShortageBookingsButCancelsNothing() {
        long staff = newStaff("RECEPTIONIST", branch);
        long customer = newCounterCustomer(null);
        long first = number(data(create(manager, type, "C-01"), HttpStatus.CREATED).get("kennelId"));
        create(manager, type, "C-02");
        long b1 = newBooking(customer, newPet(customer, "DOG"), branch, type, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-14"), "BOOKED", staff);
        long b2 = newBooking(customer, newPet(customer, "DOG"), branch, type, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-13"), "BOOKED", staff);

        Map<String, Object> result = data(call(HttpMethod.POST, "/api/kennels/" + first + "/start-maintenance",
                manager, null), HttpStatus.OK);

        assertThat(map(result.get("kennel"))).containsEntry("status", "MAINTENANCE");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shortage = (List<Map<String, Object>>) result.get("shortageBookings");
        assertThat(shortage).extracting(s -> number(s.get("bookingId"))).containsExactly(b1, b2);
        assertThat(statusOf(b1)).isEqualTo("BOOKED");
        assertThat(statusOf(b2)).isEqualTo("BOOKED");
    }

    @Test
    void endMaintenanceOnAvailableKennelIs409() {
        long id = number(data(create(manager, type, "C-01"), HttpStatus.CREATED).get("kennelId"));

        assertError(call(HttpMethod.POST, "/api/kennels/" + id + "/end-maintenance", manager, null),
                HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        data(call(HttpMethod.POST, "/api/kennels/" + id + "/start-maintenance", manager, null), HttpStatus.OK);
        data(call(HttpMethod.POST, "/api/kennels/" + id + "/end-maintenance", manager, null), HttpStatus.OK);
    }

    @Test
    void occupiedKennelCannotBeDeletedOrPutInMaintenance() {
        long id = newKennel(branch, type, "C-OC", "OCCUPIED");

        assertError(call(HttpMethod.DELETE, "/api/kennels/" + id, manager, null), HttpStatus.CONFLICT,
                "INVALID_STATE_TRANSITION");
        assertError(call(HttpMethod.POST, "/api/kennels/" + id + "/start-maintenance", manager, null),
                HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
    }

    @Test
    void kennelWithBookingHistoryCannotBeDeleted() {
        long staff = newStaff("RECEPTIONIST", branch);
        long customer = newCounterCustomer(null);
        long id = newKennel(branch, type, "C-H1", "AVAILABLE");
        newBooking(customer, newPet(customer, "DOG"), branch, type, LocalDate.parse("2009-06-01"),
                LocalDate.parse("2009-06-03"), "CHECKED_OUT", id, staff);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.DELETE, "/api/kennels/" + id, manager, null);

        assertError(response, HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        assertThat((String) response.getBody().get("message")).contains("lịch sử lưu trú");
    }

    @Test
    void scopeAndRoleAreEnforced() {
        long id = newKennel(branch, type, "C-01", "AVAILABLE");
        long otherBranch = newBranch();
        String otherManager = token(newStaff("BRANCH_MANAGER", otherBranch));
        String receptionist = token(newStaff("RECEPTIONIST", branch));
        String caretaker = token(newStaff("CARETAKER", branch));

        assertThat(call(HttpMethod.PATCH, "/api/kennels/" + id, otherManager, Map.of("code", "X")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(create(receptionist, type, "C-05").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/kennels", caretaker, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/kennels", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.DELETE, "/api/kennels/9999999", manager, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void floatKennelTypeIdIsMalformed() {
        ResponseEntity<Map<String, Object>> response = callRaw(HttpMethod.POST, "/api/kennels", manager,
                "{\"kennelTypeId\": 1.5, \"code\": \"X\"}");

        assertError(response, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
    }
}
