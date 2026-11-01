package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * T45 — E2E bán lẻ tại quầy, toàn bộ qua HTTP, không stand-in: nhà cung cấp → phiếu nhập hai lô (cộng tồn) → Order bán
 * lẻ → thêm dòng (cảnh báo thiếu tồn, chặn thuốc kê đơn) → chốt → mở ca → xem trước → thu tiền (FEFO bỏ qua lô đã hết
 * hạn) → Order đã thu không sửa được → chốt ca → đối soát → phiếu nhập đã bán bớt không hủy được → báo cáo doanh thu.
 * Era <b>năm 2036</b> (không job nào quét tới); chi nhánh mở 08:00–18:00.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RetailSaleFlowIT.TestBeans.class})
class RetailSaleFlowIT extends CashierItSupport {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2036, 9, 9);
    private static final long PRICE = 50_000;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(at(9));
        }
    }

    @Autowired
    private MutableClock clock;

    private long branch;
    private long receptionist;
    private long manager;
    private long customer;
    private long goods;
    private long drug;
    private String receptionistToken;
    private String managerToken;

    @BeforeEach
    void setUp() {
        clock.set(at(9));
        branch = newBranch();
        openingHours(branch, LocalDate.of(2036, 1, 1), "08:00", "18:00", null, null);
        receptionist = receptionist(branch);
        manager = staff("BRANCH_MANAGER", branch);
        receptionistToken = token(receptionist);
        managerToken = token(manager);
        customer = newCustomer();
        goods = newProduct("GOODS", true);
        jdbc.update("UPDATE products SET price = ? WHERE id = ?", PRICE, goods);
        drug = newPricedProduct("DRUG", true, 80_000);
    }

    @Test
    @SuppressWarnings("unchecked")
    void retailSaleFromReceiptToReconciledShift() {
        // 1. lô đã hết hạn (dữ liệu nền) + phiếu nhập hai lô còn hạn → xác nhận
        long expired = lot(branch, goods, "EXP", TODAY.minusDays(8).toString(), 5, manager);
        long supplier = newSupplier(true);
        Map<String, Object> receipt = new HashMap<>();
        receipt.put("supplierId", supplier);
        receipt.put("receiptDate", TODAY.toString());
        receipt.put("lines", List.of(receiptLine("A", TODAY.plusDays(30), 3), receiptLine("B", TODAY.plusDays(90), 10)));
        long receiptId = number(data(call(HttpMethod.POST, "/api/stock-receipts", managerToken, receipt),
                HttpStatus.CREATED).get("receiptId"));
        data(call(HttpMethod.POST, "/api/stock-receipts/" + receiptId + "/confirm", managerToken, null), HttpStatus.OK);
        long lotA = lotOf("A");
        long lotB = lotOf("B");
        assertThat(lotQuantity(lotA)).isEqualTo(3);
        assertThat(lotQuantity(lotB)).isEqualTo(10);

        // 2. Order bán lẻ: thêm 5 (đủ tồn khả dụng 13, lô hết hạn không tính) và chặn thuốc kê đơn
        long order = number(data(call(HttpMethod.POST, "/api/orders", receptionistToken, Map.of("customerId", customer)),
                HttpStatus.CREATED).get("orderId"));
        Map<String, Object> added = data(addGoods(order, goods, 5), HttpStatus.CREATED);
        assertThat(added.get("stockWarning")).isNull();
        assertRule(addGoods(order, drug, 1), "BR-SP-01");
        assertThat(orderTotal(order)).isEqualTo(5 * PRICE).isEqualTo(sumOfLines(order));

        // 3. chốt; Order khác thêm 20 → cảnh báo thiếu tồn nhưng vẫn thêm được (BR-BH-04 chỉ chặn lúc thu)
        data(call(HttpMethod.POST, "/api/orders/" + order + "/close", receptionistToken, null), HttpStatus.OK);
        long second = number(data(call(HttpMethod.POST, "/api/orders", receptionistToken,
                Map.of("customerId", customer)), HttpStatus.CREATED).get("orderId"));
        assertThat(data(addGoods(second, goods, 20), HttpStatus.CREATED).get("stockWarning")).isNotNull();

        // 4. mở ca → xem trước không thiếu → thu tiền
        long shift = number(data(call(HttpMethod.POST, "/api/cashier-shifts", receptionistToken,
                Map.of("afterHours", false)), HttpStatus.CREATED).get("shiftId"));
        Map<String, Object> preview = data(call(HttpMethod.POST, "/api/payments/preview", receptionistToken,
                Map.of("orderIds", List.of(order))), HttpStatus.OK);
        assertThat((List<Object>) preview.get("shortages")).isEmpty();
        long amount = orderTotal(order);
        data(call(HttpMethod.POST, "/api/payments", receptionistToken,
                Map.of("orderIds", List.of(order), "method", "CASH", "amount", amount)), HttpStatus.CREATED);

        // 5. PAID, trừ FEFO lô hạn gần nhất, lô hết hạn giữ nguyên, giao dịch vào ca
        assertThat(orderStatus(order)).isEqualTo("PAID");
        assertThat(lotQuantity(lotA)).isZero();
        assertThat(lotQuantity(lotB)).isEqualTo(8);
        assertThat(lotQuantity(expired)).isEqualTo(5);
        assertThat(movementCount(expired)).isOne(); // chỉ movement nhập giả lập, không xuất
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE cashier_shift_id = ?", Long.class, shift))
                .isOne();
        assertThat(orderTotal(order)).isEqualTo(sumOfLines(order));

        // 6. Order đã thu không sửa dòng được
        ResponseEntity<Map<String, Object>> late = addGoods(order, goods, 1);
        assertThat(late.getStatusCode()).as("%s", late.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sumOfLines(order)).isEqualTo(5 * PRICE);

        // 7. chốt ca → đối soát
        data(call(HttpMethod.POST, "/api/cashier-shifts/" + shift + "/close", receptionistToken,
                Map.of("countedCash", amount)), HttpStatus.OK);
        data(call(HttpMethod.POST, "/api/cashier-shifts/" + shift + "/reconcile", managerToken,
                Map.of("note", "Đủ tiền")), HttpStatus.OK);
        assertThat(shiftRow(shift)).containsEntry("status", "RECONCILED").containsEntry("difference", 0L);

        // 8. lô A đã bán hết → hủy phiếu nhập bị chặn (BR-KO-04), không ghi gì
        assertRule(call(HttpMethod.POST, "/api/stock-receipts/" + receiptId + "/cancel", managerToken,
                Map.of("reason", "Nhập nhầm")), "BR-KO-04");
        assertThat(jdbc.queryForObject("SELECT status FROM stock_receipts WHERE id = ?", String.class, receiptId))
                .isEqualTo("CONFIRMED");
        assertThat(lotQuantity(lotB)).isEqualTo(8);

        // 9. báo cáo doanh thu của ngày có khoản thu này
        ResponseEntity<Map<String, Object>> revenue = call(HttpMethod.GET, "/api/reports/revenue?branchId=" + branch
                + "&from=" + TODAY + "&to=" + TODAY, managerToken, null);
        assertThat(revenue.getStatusCode()).as("%s", revenue.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(revenue.getBody().get("data"))).contains(String.valueOf(amount));
    }

    // ================================================================ helpers

    private Map<String, Object> receiptLine(String lotNumber, LocalDate expiry, int quantity) {
        Map<String, Object> line = new HashMap<>();
        line.put("productId", goods);
        line.put("quantity", quantity);
        line.put("unitCost", 20_000);
        line.put("lotNumber", lotNumber);
        line.put("expiryDate", expiry.toString());
        return line;
    }

    private long lotOf(String lotNumber) {
        return jdbc.queryForObject("""
                SELECT l.id FROM stock_lots l JOIN inventory_items i ON i.id = l.inventory_item_id
                WHERE i.branch_id = ? AND l.lot_number = ?""", Long.class, branch, lotNumber);
    }

    private ResponseEntity<Map<String, Object>> addGoods(long order, long product, int quantity) {
        return call(HttpMethod.POST, "/api/orders/" + order + "/lines", receptionistToken,
                Map.of("lineType", "GOODS", "productId", product, "quantity", quantity));
    }

    private static Instant at(int hour) {
        return TODAY.atTime(hour, 0).atZone(VN).toInstant();
    }
}
