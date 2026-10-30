package com.petcare.module.boarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * T45 — đủ 5 chuyển của bảng "7. Chuồng" (docs/03-state-machines.md) qua endpoint thật, <b>không stand-in nào</b> (kể
 * cả mũi tiêm bắt buộc: loài chó không có loại bắt buộc, được kiểm ở {@code @BeforeEach}), cùng cặp sai có đường HTTP
 * → 409 và không ghi gì. Chuồng #3 qua hand-over (cần Order lưu trú {@code PAID}) được kiểm ở
 * {@code BoardingBookingFsmIT}; ở đây #3 đi bằng {@code end-deceased}. Era <b>năm 2035</b> (không job nào quét tới).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, KennelFsmIT.TestBeans.class})
class KennelFsmIT extends BoardingItSupport {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate DAY = LocalDate.of(2035, 7, 8);

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(at(10));
        }
    }

    private long branch;
    private long type;
    private long customer;
    private long pet;
    private String manager;
    private String receptionist;

    @BeforeEach
    void setUp() {
        clock.set(at(10));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM vaccination_protocols WHERE species = 'DOG' AND required_for_boarding AND is_active""",
                Long.class)).as("IT khác để sót phác đồ bắt buộc khi lưu trú cho chó").isZero();
        branch = newBranch();
        type = newKennelType(branch, "DOG", "30", 150_000);
        manager = token(newStaff("BRANCH_MANAGER", branch));
        receptionist = token(newStaff("RECEPTIONIST", branch));
        customer = newCounterCustomer(null);
        pet = newPet(customer, "DOG");
        weigh(pet, "10.00");
    }

    // ================================================================ chuyển hợp lệ

    /** #1 — tạo chuồng: {@code AVAILABLE}, sức chứa các đêm tăng 1. */
    @Test
    void createdKennelIsAvailableAndAddsCapacity() {
        newKennel(branch, type, "C-00", "AVAILABLE");
        assertThat(capacity()).isEqualTo(1);

        long id = createKennel("C-01");

        assertThat(kennelStatus(id)).isEqualTo("AVAILABLE");
        assertThat(capacity()).isEqualTo(2);
    }

    /** #2 — nhận thú: chuồng {@code OCCUPIED}. */
    @Test
    void checkInOccupiesTheKennel() {
        long kennel = createKennel("C-01");

        checkedIn(pet, kennel);

        assertThat(kennelStatus(kennel)).isEqualTo("OCCUPIED");
        assertThat(capacity()).isEqualTo(1); // chuồng đang có thú vẫn tính vào sức chứa
    }

    /** #3 — kết thúc lưu trú (thú mất): chuồng {@code AVAILABLE}. Hand-over: {@code BoardingBookingFsmIT}. */
    @Test
    void endDeceasedFreesTheKennel() {
        long kennel = createKennel("C-01");
        long booking = checkedIn(pet, kennel);

        data(call(HttpMethod.POST, "/api/boarding-bookings/" + booking + "/end-deceased", receptionist,
                Map.of("note", "Thú lên cơn co giật, cấp cứu không qua khỏi")), HttpStatus.OK);

        assertThat(statusOf(booking)).isEqualTo("CHECKED_OUT");
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
    }

    /** #4 — chuyển bảo trì: sức chứa giảm 1; thiếu chỗ cho đặt chỗ đã có thì cảnh báo kèm danh sách, không tự hủy. */
    @Test
    @SuppressWarnings("unchecked")
    void maintenanceRemovesCapacityAndWarnsAboutShortage() {
        long kennel = createKennel("C-01");
        long booking = book(pet, DAY.plusDays(1), DAY.plusDays(2));

        Map<String, Object> result = data(call(HttpMethod.POST, "/api/kennels/" + kennel + "/start-maintenance",
                manager, null), HttpStatus.OK);

        assertThat(map(result.get("kennel"))).containsEntry("status", "MAINTENANCE");
        assertThat((List<Map<String, Object>>) result.get("shortageBookings")).extracting(s -> number(s.get("bookingId")))
                .containsExactly(booking);
        assertThat(statusOf(booking)).isEqualTo("BOOKED");
        assertThat(capacity()).isZero();
    }

    /** #5 — hết bảo trì: {@code AVAILABLE}, sức chứa tăng lại. */
    @Test
    void endingMaintenanceRestoresCapacity() {
        long kennel = createKennel("C-01");
        data(call(HttpMethod.POST, "/api/kennels/" + kennel + "/start-maintenance", manager, null), HttpStatus.OK);

        data(call(HttpMethod.POST, "/api/kennels/" + kennel + "/end-maintenance", manager, null), HttpStatus.OK);

        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
        assertThat(capacity()).isOne();
    }

    // ================================================================ không có lối vòng qua FSM

    /** {@code PATCH} chỉ sửa mã / ghi chú: gửi kèm {@code status} không đổi được trạng thái (hoặc bị từ chối). */
    @Test
    void patchCannotChangeKennelStatus() {
        long kennel = createKennel("C-01");
        Map<String, Object> body = new HashMap<>();
        body.put("code", "C-09");
        body.put("note", "đổi");
        body.put("status", "MAINTENANCE");

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.PATCH, "/api/kennels/" + kennel, manager, body);

        assertThat(response.getStatusCode().is2xxSuccessful() || response.getStatusCode() == HttpStatus.BAD_REQUEST)
                .as("%s", response.getBody()).isTrue();
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");
    }

    @Test
    void deletingAnOccupiedKennelIs409AndKeepsTheRow() {
        long kennel = createKennel("C-01");
        checkedIn(pet, kennel);

        ResponseEntity<Map<String, Object>> response = call(HttpMethod.DELETE, "/api/kennels/" + kennel, manager, null);

        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(kennelStatus(kennel)).isEqualTo("OCCUPIED");
    }

    // ================================================================ cặp sai → 409, không ghi gì

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"AVAILABLE", "OCCUPIED", "MAINTENANCE"})
    void disallowedOperationsAre409AndWriteNothing(String source) {
        long kennel = reach(source);
        Map<String, Object> before = footprint(kennel);

        List<String> tried = switch (source) {
            case "AVAILABLE" -> List.of("end-maintenance");
            case "OCCUPIED" -> List.of("start-maintenance", "end-maintenance");
            default -> List.of("start-maintenance");
        };
        for (String operation : tried) {
            ResponseEntity<Map<String, Object>> response = call(HttpMethod.POST,
                    "/api/kennels/" + kennel + "/" + operation, manager, null);
            assertThat(response.getStatusCode()).as("%s → %s", operation, response.getBody())
                    .isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).containsEntry("errorCode", "INVALID_STATE_TRANSITION")
                    .containsEntry("statusCode", 409);
        }

        assertThat(footprint(kennel)).isEqualTo(before);
    }

    // ================================================================ helpers

    private long reach(String source) {
        long kennel = createKennel("C-01");
        switch (source) {
            case "AVAILABLE" -> {
            }
            case "OCCUPIED" -> checkedIn(pet, kennel);
            case "MAINTENANCE" -> data(call(HttpMethod.POST, "/api/kennels/" + kennel + "/start-maintenance", manager,
                    null), HttpStatus.OK);
            default -> throw new IllegalArgumentException(source);
        }
        assertThat(kennelStatus(kennel)).isEqualTo(source);
        return kennel;
    }

    private long createKennel(String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("kennelTypeId", type);
        body.put("code", code);
        return number(data(call(HttpMethod.POST, "/api/kennels", manager, body), HttpStatus.CREATED).get("kennelId"));
    }

    private long book(long petId, LocalDate in, LocalDate out) {
        Map<String, Object> body = new HashMap<>();
        body.put("petId", petId);
        body.put("branchId", branch);
        body.put("kennelTypeId", type);
        body.put("checkInDate", in.toString());
        body.put("checkOutDate", out.toString());
        return number(map(data(call(HttpMethod.POST, "/api/boarding-bookings", receptionist, body),
                HttpStatus.CREATED).get("booking")).get("bookingId"));
    }

    private long checkedIn(long petId, long kennel) {
        long booking = book(petId, DAY, DAY.plusDays(2));
        Map<String, Object> body = new HashMap<>();
        body.put("kennelId", kennel);
        body.put("weightKg", "10.00");
        body.put("healthCondition", "Tỉnh táo");
        body.put("belongings", "Chăn");
        body.put("dietInstructions", "Hạt ướt");
        body.put("emergencyPhone", "0901234567");
        data(call(HttpMethod.POST, "/api/boarding-bookings/" + booking + "/check-in", receptionist, body),
                HttpStatus.OK);
        return booking;
    }

    /** Sức chứa đêm đầu tiên của loại chuồng theo API tính khả dụng. */
    @SuppressWarnings("unchecked")
    private long capacity() {
        Map<String, Object> data = data(call(HttpMethod.GET, "/api/branches/" + branch
                + "/boarding-availability?kennelTypeId=" + type + "&checkInDate=" + DAY.plusDays(1)
                + "&checkOutDate=" + DAY.plusDays(2), receptionist, null), HttpStatus.OK);
        return number(((List<Map<String, Object>>) data.get("nights")).get(0).get("capacity"));
    }

    private String kennelStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM kennels WHERE id = ?", String.class, id);
    }

    /** Dấu "không ghi gì": phiên bản dòng chuồng, số đặt chỗ và số nhật ký của chi nhánh. */
    private Map<String, Object> footprint(long kennel) {
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("kennel", jdbc.queryForObject("SELECT xmin::text FROM kennels WHERE id = ?", String.class, kennel));
        rows.put("bookings", jdbc.queryForObject("SELECT count(*) FROM boarding_bookings WHERE branch_id = ?",
                Long.class, branch));
        rows.put("bookingVersions", jdbc.queryForList(
                "SELECT id, status, xmin::text AS version FROM boarding_bookings WHERE branch_id = ? ORDER BY id",
                branch));
        return rows;
    }

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(VN).toInstant();
    }
}
