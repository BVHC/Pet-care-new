package com.petcare.module.boarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

/**
 * T45 — E2E lưu trú, toàn bộ qua HTTP, không stand-in: BRANCH_MANAGER tạo chuồng → khách online đặt chỗ và gia hạn →
 * lễ tân nhận thú → CARETAKER ghi nhật ký → bắt đầu trả thú (Order {@code BOARDING} {@code PENDING}) → lễ tân thêm một
 * dòng bán lẻ → mở ca, thu tiền (trừ kho) → giao thú (chuồng trống) → khách thấy {@code CHECKED_OUT} và nhật ký.
 * Loài chó không có loại vaccine bắt buộc (kiểm ở {@code @BeforeEach}). Era <b>năm 2037</b> (không job nào quét tới).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, BoardingStayFlowIT.TestBeans.class})
class BoardingStayFlowIT extends BoardingItSupport {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2037, 8, 4);
    private static final long NIGHTLY = 150_000;
    private static final long GOODS_PRICE = 30_000;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(instantAt(TODAY, 10));
        }
    }

    private long branch;
    private long type;
    private long receptionistId;
    private long caretakerId;
    private long managerId;
    private long customerAccount;
    private long pet;
    private long goods;
    private long goodsLot;
    private long category;
    private String receptionist;
    private String caretaker;
    private String manager;
    private String customerToken;

    @BeforeEach
    void setUp() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM vaccination_protocols WHERE species = 'DOG' AND required_for_boarding AND is_active""",
                Long.class)).as("IT khác để sót phác đồ bắt buộc khi lưu trú cho chó").isZero();
        branch = newBranch();
        type = newKennelType(branch, "DOG", "30", NIGHTLY);
        receptionistId = newStaff("RECEPTIONIST", branch);
        caretakerId = newStaff("CARETAKER", branch);
        managerId = newStaff("BRANCH_MANAGER", branch);
        long[] online = newOnlineCustomer();
        customerAccount = online[0];
        pet = newPet(online[1], "DOG");
        weigh(pet, "8.50");
        category = jdbc.queryForObject("INSERT INTO product_categories (name) VALUES (?) RETURNING id", Long.class,
                "StayFlow " + UUID.randomUUID());
        goods = jdbc.queryForObject("""
                INSERT INTO products (category_id, sku, name, product_type, tracks_expiry, unit, price)
                VALUES (?, ?, 'Hạt khô StayFlow', 'GOODS', false, 'gói', ?) RETURNING id""", Long.class, category,
                "SF-" + UUID.randomUUID().toString().substring(0, 8), GOODS_PRICE);
        long item = jdbc.queryForObject("INSERT INTO inventory_items (branch_id, product_id) VALUES (?, ?) RETURNING id",
                Long.class, branch, goods);
        goodsLot = jdbc.queryForObject("""
                INSERT INTO stock_lots (inventory_item_id, quantity, is_default) VALUES (?, 10, true) RETURNING id""",
                Long.class, item);
        jdbc.update("""
                INSERT INTO stock_movements (stock_lot_id, movement_type, quantity_delta, balance_after, source_type,
                                             source_id, created_by)
                VALUES (?, 'RECEIPT', 10, 10, 'stock_receipts', 0, ?)""", goodsLot, managerId);
        at(TODAY, 10);
    }

    @AfterEach
    void tearDownStock() {
        // dòng Order và movement trỏ tới sản phẩm / lô (FK RESTRICT): xóa trước; lớp cha dọn Order, phiếu thu, ca sau
        jdbc.update("DELETE FROM order_lines WHERE product_id = ?", goods);
        jdbc.update("DELETE FROM stock_movements WHERE stock_lot_id = ?", goodsLot);
        jdbc.update("DELETE FROM stock_lots WHERE id = ?", goodsLot);
        jdbc.update("DELETE FROM inventory_items WHERE product_id = ?", goods);
        jdbc.update("DELETE FROM products WHERE id = ?", goods);
        jdbc.update("DELETE FROM product_categories WHERE id = ?", category);
    }

    @Test
    @SuppressWarnings("unchecked")
    void boardingStayFromBookingToHandOver() {
        // 1. quản lý tạo chuồng; khách đặt 06/08 → 09/08 (3 đêm) rồi gia hạn tới 10/08
        long kennel = number(data(call(HttpMethod.POST, "/api/kennels", manager,
                Map.of("kennelTypeId", type, "code", "SF-01")), HttpStatus.CREATED).get("kennelId"));
        Map<String, Object> book = new HashMap<>();
        book.put("petId", pet);
        book.put("branchId", branch);
        book.put("kennelTypeId", type);
        book.put("checkInDate", "2037-08-06");
        book.put("checkOutDate", "2037-08-09");
        long booking = number(map(data(call(HttpMethod.POST, "/api/boarding-bookings", customerToken, book),
                HttpStatus.CREATED).get("booking")).get("bookingId"));
        assertThat(statusOf(booking)).isEqualTo("BOOKED");
        data(call(HttpMethod.POST, "/api/boarding-bookings/" + booking + "/extend", customerToken,
                Map.of("checkOutDate", "2037-08-10")), HttpStatus.OK);

        // 2. 06/08 lễ tân nhận thú vào chuồng
        at(LocalDate.of(2037, 8, 6), 10);
        Map<String, Object> checkIn = new HashMap<>();
        checkIn.put("kennelId", kennel);
        checkIn.put("weightKg", "8.50");
        checkIn.put("healthCondition", "Tỉnh táo");
        checkIn.put("belongings", "Chăn");
        checkIn.put("dietInstructions", "Hạt khô");
        checkIn.put("emergencyPhone", "0901234567");
        data(call(HttpMethod.POST, "/api/boarding-bookings/" + booking + "/check-in", receptionist, checkIn),
                HttpStatus.OK);
        assertThat(statusOf(booking)).isEqualTo("CHECKED_IN");
        assertThat(kennelStatus(kennel)).isEqualTo("OCCUPIED");

        // 3. 07/08 CARETAKER ghi nhật ký; khách xem được
        at(LocalDate.of(2037, 8, 7), 9);
        Map<String, Object> log = new HashMap<>();
        log.put("eating", "Ăn hết");
        log.put("drinking", "Uống đủ");
        log.put("note", "Ổn");
        log.put("photoUrls", List.of());
        log.put("isAbnormal", false);
        data(call(HttpMethod.POST, "/api/boarding-bookings/" + booking + "/care-logs", caretaker, log),
                HttpStatus.CREATED);
        assertThat(list(call(HttpMethod.GET, "/api/boarding-bookings/" + booking + "/care-logs", customerToken, null),
                HttpStatus.OK)).hasSize(1);

        // 4. 10/08 trước 12:00: bắt đầu trả thú → Order BOARDING PENDING, 4 đêm
        at(LocalDate.of(2037, 8, 10), 10);
        Map<String, Object> checkout = data(call(HttpMethod.POST, "/api/boarding-bookings/" + booking
                + "/start-checkout", receptionist, null), HttpStatus.OK);
        long order = number(checkout.get("orderId"));
        assertThat(checkout).containsEntry("nights", 4);
        assertThat(orderTotal(order)).isEqualTo(4 * NIGHTLY).isEqualTo(sumOfLines(order));

        // 5. lễ tân thêm một dòng bán lẻ vào Order chờ thu → tổng khớp tổng dòng
        data(call(HttpMethod.POST, "/api/orders/" + order + "/lines", receptionist,
                Map.of("lineType", "GOODS", "productId", goods, "quantity", 1)), HttpStatus.CREATED);
        long total = 4 * NIGHTLY + GOODS_PRICE;
        assertThat(orderTotal(order)).isEqualTo(total).isEqualTo(sumOfLines(order));

        // 6. mở ca, thu tiền: trừ kho, Order PAID
        data(call(HttpMethod.POST, "/api/cashier-shifts", receptionist, Map.of("afterHours", false)), HttpStatus.CREATED);
        data(call(HttpMethod.POST, "/api/payments", receptionist,
                Map.of("orderIds", List.of(order), "method", "CASH", "amount", total)), HttpStatus.CREATED);
        assertThat(orderStatus(order)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT quantity FROM stock_lots WHERE id = ?", Integer.class, goodsLot))
                .isEqualTo(9);

        // 7. giao thú: CHECKED_OUT, chuồng trống
        data(call(HttpMethod.POST, "/api/boarding-bookings/" + booking + "/hand-over", receptionist, null),
                HttpStatus.OK);
        assertThat(kennelStatus(kennel)).isEqualTo("AVAILABLE");

        // 8. khách thấy kết thúc lưu trú
        Map<String, Object> view = data(call(HttpMethod.GET, "/api/boarding-bookings/" + booking, customerToken, null),
                HttpStatus.OK);
        assertThat(view).containsEntry("status", "CHECKED_OUT");
    }

    // ================================================================ helpers

    private void at(LocalDate day, int hour) {
        clock.set(instantAt(day, hour));
        receptionist = token(receptionistId);
        caretaker = token(caretakerId);
        manager = token(managerId);
        customerToken = token(customerAccount);
    }

    private String kennelStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM kennels WHERE id = ?", String.class, id);
    }

    private String orderStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id);
    }

    private long orderTotal(long id) {
        return jdbc.queryForObject("SELECT total_amount FROM orders WHERE id = ?", Long.class, id);
    }

    private long sumOfLines(long id) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(line_total), 0) FROM order_lines WHERE order_id = ?",
                Long.class, id);
    }

    private static Instant instantAt(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(VN).toInstant();
    }
}
