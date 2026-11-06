package com.petcare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Hành vi ràng buộc của schema tầng 3 phần BE-2 (V43–V48, T55c, docs/adr/0117; đặc tả docs/08-tang3-be2-spec.md §A–§F,
 * 05 §13 mục 26) trên Postgres thật. Mọi kỳ vọng là bản sao độc lập chép từ 08, không đọc lại từ file migration.
 * <p>
 * Mỗi ca vi phạm kiểm hai thứ: SQLSTATE (23514 CHECK, 23505 UNIQUE) <b>và tên ràng buộc</b> trong thông điệp, nên một
 * CHECK khác chặn nhầm (hoặc CHECK mong đợi bị gỡ) làm test đỏ. Các giá trị mới của cột có sẵn ({@code orders.source}
 * ONLINE, {@code services.medical_type}, {@code order_lines.line_type}) do V41, V42 của T55b và {@code Tier3Be1SchemaIT}
 * kiểm; ở đây chỉ dùng Order ONLINE làm fixture cho các bảng của BE-2. Mỗi test tự dọn dữ liệu của mình theo thứ tự ngược
 * lại với lúc tạo.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class Tier3Be2SchemaIT {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-11-06T03:00:00Z"));
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** Bảng mà {@link #assertRowViolates} dựng dòng từ {@code onlineRow}, {@code returnRow}, {@code refundRow}... */
    private static final List<String> ROW_TABLES =
            List.of("online_orders", "return_requests", "refunds", "stock_transfers", "stock_counts");

    @Autowired
    private JdbcTemplate jdbc;

    /** (bảng, cột khóa, giá trị) của mọi dòng test đã tạo; dọn ngược thứ tự để FK RESTRICT không chặn. */
    private final Deque<Object[]> undo = new ArrayDeque<>();

    private long staff;
    private long branch;
    private long customer;

    @BeforeEach
    void fixture() {
        staff = account();
        branch = branch("ACTIVE");
        customer = customer();
    }

    @AfterEach
    void cleanUp() {
        while (!undo.isEmpty()) {
            Object[] row = undo.pop();
            jdbc.update("DELETE FROM " + row[0] + " WHERE " + row[1] + " = ?", row[2]);
        }
    }

    // ================================================================ A. Phí và định mức (V43)

    @Test
    void shippingFeeConfigIsSeededAsTheOnlyRow() {
        assertThat(jdbc.queryForList("SELECT id, base_fee, free_threshold, updated_by FROM shipping_fee_configs"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row).containsEntry("id", 1L).containsEntry("base_fee", 0L)
                            .containsEntry("free_threshold", 0L).containsEntry("updated_by", null);
                });

        // BR-SP-08: một bộ chung toàn chuỗi
        assertViolates("23514", "ck_shipping_fee_configs_single_row", () -> jdbc.update(
                "INSERT INTO shipping_fee_configs (id, base_fee, free_threshold) OVERRIDING SYSTEM VALUE VALUES (2, 0, 0)"));
        assertViolates("23514", "ck_shipping_fee_configs_base_fee",
                () -> jdbc.update("UPDATE shipping_fee_configs SET base_fee = -1"));
        assertViolates("23514", "ck_shipping_fee_configs_free_threshold",
                () -> jdbc.update("UPDATE shipping_fee_configs SET free_threshold = -1"));
    }

    @Test
    void homeVisitFeeIsPerBranchAndNonNegative() {
        insertKeyed("branch_home_visit_fees", "branch_id", branch,
                "INSERT INTO branch_home_visit_fees (branch_id, enabled, fee, updated_by) VALUES (?, true, 50000, ?)",
                branch, staff);

        assertViolates("23505", "branch_home_visit_fees_pkey", () -> jdbc.update(
                "INSERT INTO branch_home_visit_fees (branch_id, enabled, fee, updated_by) VALUES (?, false, 0, ?)",
                branch, staff));
        assertViolates("23514", "ck_branch_home_visit_fees_fee", () -> jdbc.update(
                "INSERT INTO branch_home_visit_fees (branch_id, enabled, fee, updated_by) VALUES (?, false, -1, ?)",
                branch("ACTIVE"), staff));
    }

    @Test
    void materialNormsAreUniquePerServiceAndProductWithPositiveQuantity() {
        long service = service("GROOMING", null);
        long product = product("GOODS");
        insert("service_material_norms",
                "INSERT INTO service_material_norms (service_id, product_id, quantity) VALUES (?, ?, 2)", service, product);

        assertViolates("23505", "uq_service_material_norms_service_product", () -> jdbc.update(
                "INSERT INTO service_material_norms (service_id, product_id, quantity) VALUES (?, ?, 1)", service, product));
        assertViolates("23514", "ck_service_material_norms_quantity", () -> jdbc.update(
                "INSERT INTO service_material_norms (service_id, product_id, quantity) VALUES (?, ?, 0)", service("GROOMING", null),
                product));
    }

    // ================================================================ B. Đơn online (V44)

    @Test
    void onlineOrderTimestampsFollowTheStatus() {
        // hợp lệ: mỗi trạng thái với đúng các mốc thời gian đã đi qua (FSM #14)
        for (Map<String, Object> row : new Map[] {
                onlineRow("PENDING_PAYMENT"),
                onlineRow("PAID", "paid_at", NOW),
                onlineRow("PROCESSING", "paid_at", NOW),
                onlineRow("SHIPPED", "paid_at", NOW, "shipped_at", NOW, "carrier", "GHN", "tracking_code", "T1"),
                onlineRow("DELIVERY_FAILED", "paid_at", NOW, "shipped_at", NOW, "carrier", "GHN", "tracking_code", "T2",
                        "redelivery_count", 1),
                onlineRow("DELIVERED", "paid_at", NOW, "shipped_at", NOW, "carrier", "GHN", "tracking_code", "T3",
                        "delivered_at", NOW),
                onlineRow("COMPLETED", "paid_at", NOW, "shipped_at", NOW, "carrier", "GHN", "tracking_code", "T4",
                        "delivered_at", NOW, "completed_at", NOW),
                // hủy lúc chờ thanh toán (#3, #4: hệ thống hủy thì cancelled_by NULL)
                onlineRow("CANCELLED", "cancelled_at", NOW, "cancel_reason", "Quá hạn thanh toán"),
                // hủy sau giao thất bại (#10): đã thanh toán và đã bàn giao
                onlineRow("CANCELLED", "paid_at", NOW, "shipped_at", NOW, "carrier", "GHN", "tracking_code", "T5",
                        "cancelled_at", NOW, "cancel_reason", "Giao thất bại 3 lần", "cancelled_by", staff)}) {
            assertThatCode(() -> insertRow("online_orders", row)).as(row.toString()).doesNotThrowAnyException();
        }

        assertRowViolates("ck_online_orders_paid_at", onlineRow("PAID"));
        assertRowViolates("ck_online_orders_paid_at", onlineRow("PENDING_PAYMENT", "paid_at", NOW));
        assertRowViolates("ck_online_orders_shipped_at", onlineRow("SHIPPED", "paid_at", NOW, "carrier", "GHN", "tracking_code", "X"));
        assertRowViolates("ck_online_orders_shipped_at",
                onlineRow("PAID", "paid_at", NOW, "shipped_at", NOW, "carrier", "GHN", "tracking_code", "X"));
        assertRowViolates("ck_online_orders_carrier", onlineRow("SHIPPED", "paid_at", NOW, "shipped_at", NOW));
        assertRowViolates("ck_online_orders_carrier",
                onlineRow("SHIPPED", "paid_at", NOW, "shipped_at", NOW, "carrier", "GHN"));
        assertRowViolates("ck_online_orders_delivered_at",
                onlineRow("DELIVERED", "paid_at", NOW, "shipped_at", NOW, "carrier", "GHN", "tracking_code", "X"));
        assertRowViolates("ck_online_orders_delivered_at", onlineRow("SHIPPED", "paid_at", NOW, "shipped_at", NOW,
                "carrier", "GHN", "tracking_code", "X", "delivered_at", NOW));
        assertRowViolates("ck_online_orders_completed_at", onlineRow("COMPLETED", "paid_at", NOW, "shipped_at", NOW,
                "carrier", "GHN", "tracking_code", "X", "delivered_at", NOW));
        assertRowViolates("ck_online_orders_cancel_reason", onlineRow("CANCELLED", "cancelled_at", NOW));
        assertRowViolates("ck_online_orders_cancelled_at", onlineRow("CANCELLED", "cancel_reason", "Lý do"));
        assertRowViolates("ck_online_orders_cancelled_by", onlineRow("PAID", "paid_at", NOW, "cancelled_by", staff));
        assertRowViolates("ck_online_orders_redelivery_count", onlineRow("PENDING_PAYMENT", "redelivery_count", -1));
        assertRowViolates("ck_online_orders_shipping_fee", onlineRow("PENDING_PAYMENT", "shipping_fee", -1L));
    }

    @Test
    void onlineOrderCodeAndSalesOrderAreUnique() {
        Map<String, Object> first = onlineRow("PENDING_PAYMENT");
        insertRow("online_orders", first);

        Map<String, Object> sameCode = onlineRow("PENDING_PAYMENT", "code", first.get("code"));
        assertViolates("23505", "uq_online_orders_code", () -> insertRow("online_orders", sameCode));
        Map<String, Object> sameOrder = onlineRow("PENDING_PAYMENT", "order_id", first.get("order_id"));
        assertViolates("23505", "uq_online_orders_order_id", () -> insertRow("online_orders", sameOrder));
    }

    @Test
    void stockReservationIsUniquePerOrderAndProduct() {
        long onlineOrder = insertRow("online_orders", onlineRow("PENDING_PAYMENT"));
        long product = product("GOODS");
        insert("stock_reservations", "INSERT INTO stock_reservations (online_order_id, branch_id, product_id, quantity, status) "
                + "VALUES (?, ?, ?, 2, 'HELD')", onlineOrder, branch, product);

        assertViolates("23505", "uq_stock_reservations_order_product", () -> jdbc.update(
                "INSERT INTO stock_reservations (online_order_id, branch_id, product_id, quantity, status) "
                        + "VALUES (?, ?, ?, 1, 'HELD')", onlineOrder, branch, product));
        assertViolates("23514", "ck_stock_reservations_quantity", () -> jdbc.update(
                "INSERT INTO stock_reservations (online_order_id, branch_id, product_id, quantity, status) "
                        + "VALUES (?, ?, ?, 0, 'HELD')", onlineOrder, branch, product("GOODS")));
        assertViolates("23514", "ck_stock_reservations_status", () -> jdbc.update(
                "INSERT INTO stock_reservations (online_order_id, branch_id, product_id, quantity, status) "
                        + "VALUES (?, ?, ?, 1, 'FOO')", onlineOrder, branch, product("GOODS")));
    }

    @Test
    void cartIsOnePerCustomerWithQuantityBetween1And99() {
        long cart = insert("carts", "INSERT INTO carts (customer_id) VALUES (?)", customer);
        assertViolates("23505", "uq_carts_customer_id", () -> jdbc.update("INSERT INTO carts (customer_id) VALUES (?)", customer));

        long product = product("GOODS");
        insert("cart_items", "INSERT INTO cart_items (cart_id, product_id, quantity) VALUES (?, ?, 99)", cart, product);
        assertViolates("23505", "uq_cart_items_cart_product",
                () -> jdbc.update("INSERT INTO cart_items (cart_id, product_id, quantity) VALUES (?, ?, 1)", cart, product));
        assertViolates("23514", "ck_cart_items_quantity", () -> jdbc.update(
                "INSERT INTO cart_items (cart_id, product_id, quantity) VALUES (?, ?, 100)", cart, product("GOODS")));
        assertViolates("23514", "ck_cart_items_quantity", () -> jdbc.update(
                "INSERT INTO cart_items (cart_id, product_id, quantity) VALUES (?, ?, 0)", cart, product("GOODS")));
    }

    // ================================================================ C. Thanh toán online (V45)

    @Test
    void paymentTransactionAllowsOnePendingPerOrderAndIsIdempotentOnGatewayId() {
        long onlineOrder = insertRow("online_orders", onlineRow("PENDING_PAYMENT"));
        long failed = insertRow("payment_transactions", txnRow(onlineOrder, "FAILED"));
        assertThat(failed).isPositive();
        long pending = insertRow("payment_transactions", txnRow(onlineOrder, "PENDING"));
        assertThat(pending).isPositive();

        // BR-TG-06: tối đa một giao dịch PENDING mỗi đơn
        assertViolates("23505", "uq_payment_transactions_order_pending",
                () -> insertRow("payment_transactions", txnRow(onlineOrder, "PENDING")));
        // BR-TG-07: (cổng, mã giao dịch của cổng) không trùng; nhiều dòng chưa có mã thì được
        long other = insertRow("online_orders", onlineRow("PENDING_PAYMENT"));
        insertRow("payment_transactions", txnRow(other, "SUCCEEDED", "gateway_txn_id", "G-1", "paid_at", NOW));
        long third = insertRow("online_orders", onlineRow("PENDING_PAYMENT"));
        assertViolates("23505", "uq_payment_transactions_gateway_txn",
                () -> insertRow("payment_transactions", txnRow(third, "SUCCEEDED", "gateway_txn_id", "G-1", "paid_at", NOW)));
        assertThatCode(() -> insertRow("payment_transactions",
                txnRow(third, "SUCCEEDED", "gateway", "MOMO", "gateway_txn_id", "G-1", "paid_at", NOW)))
                .doesNotThrowAnyException();
    }

    @Test
    void paymentTransactionPaidAtOnlyWhenSucceeded() {
        long onlineOrder = insertRow("online_orders", onlineRow("PENDING_PAYMENT"));
        assertViolates("23514", "ck_payment_transactions_paid_at",
                () -> insertRow("payment_transactions", txnRow(onlineOrder, "SUCCEEDED")));
        assertViolates("23514", "ck_payment_transactions_paid_at",
                () -> insertRow("payment_transactions", txnRow(onlineOrder, "FAILED", "paid_at", NOW)));
        assertViolates("23514", "ck_payment_transactions_amount",
                () -> insertRow("payment_transactions", txnRow(onlineOrder, "FAILED", "amount", 0L)));
        assertViolates("23514", "ck_payment_transactions_status",
                () -> insertRow("payment_transactions", txnRow(onlineOrder, "FOO")));
    }

    @Test
    void reconciliationIsOnePerDayAndItemsHaveAKind() {
        LocalDate day = LocalDate.of(2000, 1, 1).plusDays(SEQ.incrementAndGet());
        long recon = insert("payment_reconciliations",
                "INSERT INTO payment_reconciliations (recon_date, status) VALUES (?, 'RUNNING')", day);
        assertViolates("23505", "uq_payment_reconciliations_recon_date", () -> jdbc.update(
                "INSERT INTO payment_reconciliations (recon_date, status) VALUES (?, 'RUNNING')", day));
        assertViolates("23514", "ck_payment_reconciliations_status", () -> jdbc.update(
                "INSERT INTO payment_reconciliations (recon_date, status) VALUES (?, 'FOO')", day.plusDays(1)));

        assertThatCode(() -> insert("reconciliation_items",
                "INSERT INTO reconciliation_items (reconciliation_id, kind, gateway_ref, gateway_amount) VALUES (?, 'MISSING_LOCAL', 'G-9', 1000)",
                recon)).doesNotThrowAnyException();
        assertViolates("23514", "ck_reconciliation_items_kind", () -> jdbc.update(
                "INSERT INTO reconciliation_items (reconciliation_id, kind) VALUES (?, 'FOO')", recon));
    }

    // ================================================================ D. Trả hàng và hoàn tiền (V46)

    @Test
    void returnRequestDecisionColumnsFollowTheStatus() {
        long order = order("RETAIL", "OPEN", null);
        for (Map<String, Object> row : new Map[] {
                returnRow(order, "PENDING"),
                returnRow(order, "CANCELLED"),
                returnRow(order, "APPROVED", "decided_by", staff, "decided_at", NOW, "restock", true, "refund_amount", 90000L),
                returnRow(order, "REFUNDED", "decided_by", staff, "decided_at", NOW, "restock", false, "refund_amount", 90000L),
                returnRow(order, "REJECTED", "decided_by", staff, "decided_at", NOW, "decision_note", "Hết hạn đổi trả")}) {
            assertThatCode(() -> insertRow("return_requests", row)).as(row.toString()).doesNotThrowAnyException();
        }

        String decision = "ck_return_requests_decision";
        assertRowViolates(decision, returnRow(order, "PENDING", "decided_by", staff));
        assertRowViolates(decision, returnRow(order, "PENDING", "restock", true));
        assertRowViolates(decision, returnRow(order, "APPROVED", "decided_by", staff, "decided_at", NOW, "restock", true));
        assertRowViolates(decision, returnRow(order, "APPROVED", "decided_by", staff, "decided_at", NOW, "refund_amount", 1L));
        // BR-BH-14: từ chối bắt buộc có lý do và không có tiền hoàn
        assertRowViolates(decision, returnRow(order, "REJECTED", "decided_by", staff, "decided_at", NOW));
        assertRowViolates(decision, returnRow(order, "REJECTED", "decided_by", staff, "decided_at", NOW, "decision_note", "x",
                "refund_amount", 1L));
        assertRowViolates("ck_return_requests_reason_code", returnRow(order, "PENDING", "reason_code", "FOO"));
        assertRowViolates("ck_return_requests_refund_amount", returnRow(order, "APPROVED", "decided_by", staff, "decided_at", NOW,
                "restock", true, "refund_amount", -1L));
    }

    @Test
    void returnRequestLinesAreUniquePerRequestAndLine() {
        long order = order("RETAIL", "OPEN", null);
        long product = product("GOODS");
        long orderLine = line(order, "GOODS", null, product);
        long request = insertRow("return_requests", returnRow(order, "PENDING"));
        insert("return_request_lines", "INSERT INTO return_request_lines (request_id, order_line_id, quantity) VALUES (?, ?, 1)",
                request, orderLine);

        assertViolates("23505", "uq_return_request_lines_request_line", () -> jdbc.update(
                "INSERT INTO return_request_lines (request_id, order_line_id, quantity) VALUES (?, ?, 1)", request, orderLine));
        assertViolates("23514", "ck_return_request_lines_quantity", () -> jdbc.update(
                "INSERT INTO return_request_lines (request_id, order_line_id, quantity) VALUES (?, ?, 0)", request,
                line(order, "GOODS", null, product)));
    }

    @Test
    void refundHasExactlyOneSourceAndCounterRefundNeedsAShift() {
        long order = order("RETAIL", "OPEN", null);
        long request = insertRow("return_requests", returnRow(order, "PENDING"));
        long onlineOrder = insertRow("online_orders", onlineRow("PENDING_PAYMENT"));
        long shift = shift();

        assertThatCode(() -> insertRow("refunds", refundRow("CASH", "REQUESTED", "return_request_id", request)))
                .doesNotThrowAnyException();
        assertThatCode(() -> insertRow("refunds", refundRow("GATEWAY", "REQUESTED", "online_order_id", onlineOrder)))
                .doesNotThrowAnyException();
        // hoàn qua cổng xong không cần ca (BR-TG-13 chỉ áp dụng tại quầy)
        assertThatCode(() -> insertRow("refunds", refundRow("GATEWAY", "SUCCEEDED", "online_order_id", onlineOrder,
                "completed_at", NOW))).doesNotThrowAnyException();
        assertThatCode(() -> insertRow("refunds", refundRow("TRANSFER", "SUCCEEDED", "return_request_id", request,
                "completed_at", NOW, "cashier_shift_id", shift, "confirmed_by", staff))).doesNotThrowAnyException();

        assertRowViolates("ck_refunds_single_source", refundRow("CASH", "REQUESTED"));
        assertRowViolates("ck_refunds_single_source",
                refundRow("CASH", "REQUESTED", "return_request_id", request, "online_order_id", onlineOrder));
        assertRowViolates("ck_refunds_counter_shift",
                refundRow("CASH", "SUCCEEDED", "return_request_id", request, "completed_at", NOW));
        assertRowViolates("ck_refunds_completed_at", refundRow("GATEWAY", "SUCCEEDED", "online_order_id", onlineOrder));
        assertRowViolates("ck_refunds_completed_at",
                refundRow("GATEWAY", "REQUESTED", "online_order_id", onlineOrder, "completed_at", NOW));
        assertRowViolates("ck_refunds_amount", refundRow("CASH", "REQUESTED", "return_request_id", request, "amount", 0L));
        assertRowViolates("ck_refunds_method", refundRow("FOO", "REQUESTED", "return_request_id", request));
        assertRowViolates("ck_refunds_attempts",
                refundRow("GATEWAY", "REQUESTED", "online_order_id", onlineOrder, "attempts", -1));
    }

    // ================================================================ E. Kho mở rộng (V47)

    @Test
    void stockTransferNeedsTwoBranchesAndItsStatusColumns() {
        long dest = branch("ACTIVE");
        for (Map<String, Object> row : new Map[] {
                transferRow(dest, "IN_TRANSIT"),
                transferRow(dest, "RECEIVED", "received_by", staff, "received_at", NOW),
                transferRow(dest, "CANCELLED", "cancelled_by", staff, "cancelled_at", NOW, "cancel_reason", "Sai số lượng")}) {
            assertThatCode(() -> insertRow("stock_transfers", row)).as(row.toString()).doesNotThrowAnyException();
        }

        assertRowViolates("ck_stock_transfers_branches", transferRow(branch, "IN_TRANSIT"));
        assertRowViolates("ck_stock_transfers_received", transferRow(dest, "RECEIVED"));
        assertRowViolates("ck_stock_transfers_received", transferRow(dest, "IN_TRANSIT", "received_by", staff));
        // BR-KO-10: hủy bắt buộc có lý do
        assertRowViolates("ck_stock_transfers_cancelled",
                transferRow(dest, "CANCELLED", "cancelled_by", staff, "cancelled_at", NOW));
        assertRowViolates("ck_stock_transfers_cancelled", transferRow(dest, "IN_TRANSIT", "cancel_reason", "x"));
        assertRowViolates("ck_stock_transfers_status", transferRow(dest, "FOO"));
    }

    @Test
    void stockTransferLineQuantityIsPositive() {
        long transfer = insertRow("stock_transfers", transferRow(branch("ACTIVE"), "IN_TRANSIT"));
        long product = product("GOODS");
        long lot = lot(product);
        assertThatCode(() -> insert("stock_transfer_lines", "INSERT INTO stock_transfer_lines (transfer_id, product_id, source_lot_id, quantity) "
                + "VALUES (?, ?, ?, 5)", transfer, product, lot)).doesNotThrowAnyException();
        assertViolates("23514", "ck_stock_transfer_lines_quantity", () -> jdbc.update(
                "INSERT INTO stock_transfer_lines (transfer_id, product_id, source_lot_id, quantity) VALUES (?, ?, ?, 0)",
                transfer, product, lot));
    }

    @Test
    void stockCountAllowsOneOpenSessionPerBranch() {
        long first = insertRow("stock_counts", countRow(branch, "OPEN"));
        // BR-KO-12: mỗi chi nhánh một phiên OPEN
        assertViolates("23505", "uq_stock_counts_branch_open", () -> insertRow("stock_counts", countRow(branch, "OPEN")));
        assertThatCode(() -> insertRow("stock_counts", countRow(branch("ACTIVE"), "OPEN"))).doesNotThrowAnyException();

        jdbc.update("UPDATE stock_counts SET status = 'CONFIRMED', confirmed_by = ?, confirmed_at = now() WHERE id = ?", staff, first);
        assertThatCode(() -> insertRow("stock_counts", countRow(branch, "OPEN"))).doesNotThrowAnyException();

        assertRowViolates("ck_stock_counts_confirmed", countRow(branch("ACTIVE"), "CONFIRMED"));
        assertRowViolates("ck_stock_counts_cancel_reason", countRow(branch("ACTIVE"), "CANCELLED"));
        assertRowViolates("ck_stock_counts_cancel_reason", countRow(branch("ACTIVE"), "OPEN", "cancel_reason", "x"));
        assertRowViolates("ck_stock_counts_category", countRow(branch("ACTIVE"), "OPEN", "scope", "CATEGORY"));
        assertRowViolates("ck_stock_counts_category",
                countRow(branch("ACTIVE"), "OPEN", "category_id", category()));
        assertRowViolates("ck_stock_counts_scope", countRow(branch("ACTIVE"), "OPEN", "scope", "FOO"));
        assertThatCode(() -> insertRow("stock_counts", countRow(branch("ACTIVE"), "OPEN", "scope", "CATEGORY",
                "category_id", category()))).doesNotThrowAnyException();
    }

    @Test
    void stockCountLineIsUniquePerLotAndNeverNegative() {
        long count = insertRow("stock_counts", countRow(branch, "OPEN"));
        long lot = lot(product("GOODS"));
        insert("stock_count_lines", "INSERT INTO stock_count_lines (count_id, stock_lot_id, counted_qty) VALUES (?, ?, 3)", count, lot);

        assertViolates("23505", "uq_stock_count_lines_count_lot", () -> jdbc.update(
                "INSERT INTO stock_count_lines (count_id, stock_lot_id, counted_qty) VALUES (?, ?, 4)", count, lot));
        assertViolates("23514", "ck_stock_count_lines_counted", () -> jdbc.update(
                "INSERT INTO stock_count_lines (count_id, stock_lot_id, counted_qty) VALUES (?, ?, -1)", count, lot(product("GOODS"))));
    }

    @Test
    void stockMovementsAcceptTransferTypesAndAdjustmentsAcceptTheNewReasons() {
        long lot = lot(product("GOODS"));
        long transfer = insertRow("stock_transfers", transferRow(branch("ACTIVE"), "IN_TRANSIT"));
        for (String type : new String[] {"TRANSFER_OUT", "TRANSFER_IN", "TRANSFER_CANCEL"}) {
            assertThatCode(() -> movement(lot, type, "stock_transfers", transfer)).as(type).doesNotThrowAnyException();
        }
        assertThatCode(() -> movement(lot, "SALE", "orders", 1L)).doesNotThrowAnyException();
        assertViolates("23514", "ck_stock_movements_movement_type", () -> movement(lot, "FOO", "stock_transfers", transfer));
        assertViolates("23514", "ck_stock_movements_source_type", () -> movement(lot, "SALE", "foo", 1L));

        // COUNT_MISMATCH của V1 giữ nguyên bên cạnh COUNT_DIFF (ADR-0117)
        for (String reason : new String[] {"DAMAGED", "EXPIRED", "COUNT_MISMATCH", "COUNT_DIFF", "RETURN_DISCARD"}) {
            assertThatCode(() -> adjustment(reason)).as(reason).doesNotThrowAnyException();
        }
        assertViolates("23514", "ck_stock_adjustments_reason", () -> adjustment("FOO"));
        // luật cũ: OTHER phải có ghi chú
        assertViolates("23514", "ck_stock_adjustments_other_note", () -> adjustment("OTHER"));
    }

    // ================================================================ F. Chi nhánh, khách, bài viết (V48)

    @Test
    void branchSuspendAndCloseColumnsFollowTheStatus() {
        assertThatCode(() -> branchRow("ACTIVE")).doesNotThrowAnyException();
        assertThatCode(() -> branchRow("SUSPENDED", "suspended_at", NOW, "suspend_reason", "Sửa chữa")).doesNotThrowAnyException();
        assertThatCode(() -> branchRow("CLOSED", "suspended_at", NOW, "suspend_reason", "Sửa chữa", "closed_at", NOW,
                "close_reason", "Hết hạn thuê")).doesNotThrowAnyException();
        // mở lại: ACTIVE vẫn giữ suspended_at làm lịch sử, CHECK cho phép (TBD-4 của ADR-0117)
        assertThatCode(() -> branchRow("ACTIVE", "suspended_at", NOW, "suspend_reason", "Sửa chữa")).doesNotThrowAnyException();

        assertViolates("23514", "ck_branches_suspended", () -> branchRow("SUSPENDED", "suspended_at", NOW));
        assertViolates("23514", "ck_branches_suspended", () -> branchRow("SUSPENDED", "suspend_reason", "Sửa chữa"));
        assertViolates("23514", "ck_branches_close_reason", () -> branchRow("CLOSED", "closed_at", NOW));
        assertViolates("23514", "ck_branches_closed_at", () -> branchRow("CLOSED", "close_reason", "Hết hạn thuê"));
        assertViolates("23514", "ck_branches_closed_at", () -> branchRow("ACTIVE", "closed_at", NOW, "close_reason", "x"));
        assertViolates("23514", "ck_branches_draft_not_suspended", () -> branchRow("DRAFT", "suspended_at", NOW));
        assertViolates("23514", "ck_branches_status", () -> branchRow("FOO"));
    }

    @Test
    void customerMergeIsRecordedAndASelfMergeIsRejected() {
        long target = customer();
        try {
            assertViolates("23514", "ck_customers_merged_into_other",
                    () -> jdbc.update("UPDATE customers SET merged_into_id = id WHERE id = ?", customer));
            assertThat(jdbc.update("UPDATE customers SET merged_into_id = ? WHERE id = ?", target, customer)).isOne();

            long merge = insert("customer_merges",
                    "INSERT INTO customer_merges (source_customer_id, target_customer_id, executed_by) VALUES (?, ?, ?)",
                    customer, target, staff);
            assertThat(jdbc.queryForObject("SELECT moved::text FROM customer_merges WHERE id = ?", String.class, merge))
                    .isEqualTo("{}");
            assertViolates("23514", "ck_customer_merges_distinct", () -> jdbc.update(
                    "INSERT INTO customer_merges (source_customer_id, target_customer_id, executed_by) VALUES (?, ?, ?)",
                    target, target, staff));
            // bảng là LOG: chỉ có created_at, không có updated_at
            assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                    + "WHERE table_name = 'customer_merges' AND column_name IN ('created_at', 'updated_at')", String.class))
                    .containsExactly("created_at");
        } finally {
            // FK RESTRICT: hồ sơ nguồn đang trỏ tới target nên phải bỏ liên kết trước khi bước dọn xóa target
            jdbc.update("UPDATE customers SET merged_into_id = NULL WHERE id = ?", customer);
        }
    }

    @Test
    void articleReviewColumnsFollowTheStatus() {
        long category = insert("article_categories", "INSERT INTO article_categories (name, slug) VALUES ('Danh mục T55c "
                + SEQ.incrementAndGet() + "', 'danh-muc-t55c-" + SEQ.get() + "')");
        assertThatCode(() -> article(category, "DRAFT")).doesNotThrowAnyException();
        assertThatCode(() -> article(category, "PENDING_REVIEW", "submitted_at", NOW, "author_account_id", staff))
                .doesNotThrowAnyException();
        assertThatCode(() -> article(category, "REJECTED", "review_note", "Thiếu nguồn", "reviewed_by", staff, "reviewed_at", NOW))
                .doesNotThrowAnyException();

        assertViolates("23514", "ck_articles_pending_submitted", () -> article(category, "PENDING_REVIEW"));
        assertViolates("23514", "ck_articles_rejected_note", () -> article(category, "REJECTED"));
        assertViolates("23514", "ck_articles_status", () -> article(category, "FOO"));
    }

    @Test
    void articleCommentHiddenNeedsWhoWhyAndWhen() {
        long category = insert("article_categories", "INSERT INTO article_categories (name, slug) VALUES ('Danh mục T55c "
                + SEQ.incrementAndGet() + "', 'danh-muc-t55c-" + SEQ.get() + "')");
        long article = article(category, "PUBLISHED");
        assertThatCode(() -> comment(article, "ACTIVE", "Bài hay")).doesNotThrowAnyException();
        assertThatCode(() -> comment(article, "DELETED", "Đã xóa")).doesNotThrowAnyException();
        assertThatCode(() -> comment(article, "HIDDEN", "Vi phạm", "hidden_by", staff, "hide_reason", "Spam", "hidden_at", NOW))
                .doesNotThrowAnyException();

        assertViolates("23514", "ck_article_comments_hidden", () -> comment(article, "HIDDEN", "Vi phạm"));
        assertViolates("23514", "ck_article_comments_hidden",
                () -> comment(article, "HIDDEN", "Vi phạm", "hidden_by", staff, "hidden_at", NOW));
        assertViolates("23514", "ck_article_comments_content", () -> comment(article, "ACTIVE", ""));
        assertViolates("23514", "ck_article_comments_status", () -> comment(article, "FOO", "x"));
    }

    // ================================================================ chỉ mục của các job (ST10, ST11, ST12, ST21)

    @Test
    void partialIndexesKeepTheStatusLiteralTheJobsFilterOn() {
        assertIndex("ix_online_orders_pending_payment_due", "(status, payment_due_at)", "'PENDING_PAYMENT'");
        assertIndex("ix_online_orders_delivered_at", "(delivered_at)", "'DELIVERED'");
        assertIndex("ix_payment_transactions_pending_expires", "(status, expires_at)", "'PENDING'");
        assertIndex("ix_refunds_gateway_requested", "(status, next_attempt_at)", "'GATEWAY'", "'REQUESTED'");
        assertIndex("ix_stock_reservations_held", "(branch_id, product_id)", "'HELD'");
        assertIndex("uq_stock_counts_branch_open", "(branch_id)", "'OPEN'");
        assertIndex("uq_payment_transactions_order_pending", "(online_order_id)", "'PENDING'");
    }

    private void assertIndex(String name, String... fragments) {
        String definition = jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                String.class, name);
        assertThat(definition).as(name).contains(fragments);
    }

    // ================================================================ dựng dòng

    private Map<String, Object> onlineRow(String status, Object... overrides) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", "DH-IT-" + SEQ.incrementAndGet());
        row.put("order_id", order("ONLINE", "PENDING", null));
        row.put("customer_id", customer);
        row.put("branch_id", branch);
        row.put("status", status);
        row.put("receiver_name", "Người nhận");
        row.put("receiver_phone", "0912345678");
        row.put("ship_address", "1 Đường A, Quận 1");
        row.put("shipping_fee", 20000L);
        row.put("payment_due_at", NOW);
        return with(row, overrides);
    }

    private Map<String, Object> txnRow(long onlineOrder, String status, Object... overrides) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", "TXN-IT-" + SEQ.incrementAndGet());
        row.put("online_order_id", onlineOrder);
        row.put("gateway", "VNPAY");
        row.put("amount", 120000L);
        row.put("status", status);
        row.put("expires_at", NOW);
        return with(row, overrides);
    }

    private Map<String, Object> returnRow(long order, String status, Object... overrides) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", "TH-IT-" + SEQ.incrementAndGet());
        row.put("order_id", order);
        row.put("branch_id", branch);
        row.put("status", status);
        row.put("reason_code", "DEFECTIVE");
        row.put("created_by", staff);
        return with(row, overrides);
    }

    private Map<String, Object> refundRow(String method, String status, Object... overrides) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", "HT-IT-" + SEQ.incrementAndGet());
        row.put("branch_id", branch);
        row.put("method", method);
        row.put("amount", 90000L);
        row.put("status", status);
        return with(row, overrides);
    }

    private Map<String, Object> transferRow(long dest, String status, Object... overrides) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", "CK-IT-" + SEQ.incrementAndGet());
        row.put("source_branch_id", branch);
        row.put("dest_branch_id", dest);
        row.put("status", status);
        row.put("created_by", staff);
        return with(row, overrides);
    }

    private Map<String, Object> countRow(long inBranch, String status, Object... overrides) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", "KK-IT-" + SEQ.incrementAndGet());
        row.put("branch_id", inBranch);
        row.put("status", status);
        row.put("scope", "ALL");
        row.put("created_by", staff);
        return with(row, overrides);
    }

    private static Map<String, Object> with(Map<String, Object> row, Object[] overrides) {
        for (int i = 0; i < overrides.length; i += 2) {
            row.put((String) overrides[i], overrides[i + 1]);
        }
        return row;
    }

    private long branchRow(String status, Object... overrides) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "T55c chi nhánh " + SEQ.incrementAndGet());
        row.put("address", "Địa chỉ");
        row.put("phone", "0280000000");
        row.put("latitude", 10.762622);
        row.put("longitude", 106.660172);
        row.put("status", status);
        return insertRow("branches", with(row, overrides));
    }

    private long article(long category, String status, Object... overrides) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("category_id", category);
        row.put("title", "Bài T55c " + SEQ.incrementAndGet());
        row.put("slug", "bai-t55c-" + SEQ.get());
        row.put("content", "<p>Nội dung</p>");
        row.put("author_display_name", "Bác sĩ IT");
        row.put("status", status);
        row.put("created_by", staff);
        return insertRow("articles", with(row, overrides));
    }

    private long comment(long article, String status, String content, Object... overrides) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("article_id", article);
        row.put("customer_id", customer);
        row.put("content", content);
        row.put("status", status);
        return insertRow("article_comments", with(row, overrides));
    }

    // ================================================================ fixture

    private long account() {
        return insert("accounts", "INSERT INTO accounts (email, phone, password_hash, role, status) VALUES (?, '0900000000', "
                + "'hash', 'RECEPTIONIST', 'ACTIVE')", "it-t55c-" + SEQ.incrementAndGet() + "@petcare.test");
    }

    private long branch(String status) {
        return branchRow(status);
    }

    private long customer() {
        return insert("customers", "INSERT INTO customers (full_name, phone, created_channel) VALUES ('Khách T55c', '0911111111', 'COUNTER')");
    }

    private long category() {
        return insert("product_categories", "INSERT INTO product_categories (name) VALUES (?)", "Nhóm T55c " + SEQ.incrementAndGet());
    }

    private long product(String type) {
        return insert("products", "INSERT INTO products (category_id, sku, name, product_type, unit, price) VALUES (?, ?, ?, ?, 'cái', 50000)",
                category(), "T55C-" + SEQ.incrementAndGet(), "Sản phẩm T55c " + SEQ.get(), type);
    }

    private long service(String group, String medicalType) {
        return insert("services", "INSERT INTO services (name, service_group, medical_type, price) VALUES (?, ?, ?, 100000)",
                "Dịch vụ T55c " + SEQ.incrementAndGet(), group, medicalType);
    }

    private long order(String source, String status, Long paymentId) {
        return insert("orders", "INSERT INTO orders (code, source, customer_id, branch_id, payment_id, status, created_by) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", "IT55c-" + SEQ.incrementAndGet(), source, customer, branch, paymentId, status, staff);
    }

    private long line(long order, String lineType, Long serviceId, Long productId) {
        return insert("order_lines", "INSERT INTO order_lines (order_id, line_type, service_id, product_id, description, quantity, "
                + "unit_price, line_total, added_by, owner_account_id) VALUES (?, ?, ?, ?, 'Dòng IT', 1, 50000, 50000, ?, ?)",
                order, lineType, serviceId, productId, staff, staff);
    }

    private long shift() {
        return insert("cashier_shifts", "INSERT INTO cashier_shifts (branch_id, cashier_id, business_date, status, opened_at) "
                + "VALUES (?, ?, ?, 'OPEN', now())", branch, account(), LocalDate.of(2026, 11, 6));
    }

    private long payment() {
        long shift = shift();
        return insert("payments", "INSERT INTO payments (code, branch_id, customer_id, cashier_shift_id, method, amount, received_by, "
                + "paid_at) VALUES (?, ?, ?, ?, 'CASH', 0, ?, now())", "PT-IT-" + SEQ.incrementAndGet(), branch, customer, shift, staff);
    }

    private long lot(long product) {
        long item = insert("inventory_items", "INSERT INTO inventory_items (branch_id, product_id) VALUES (?, ?)", branch, product);
        return insert("stock_lots", "INSERT INTO stock_lots (inventory_item_id, quantity, is_default) VALUES (?, 10, true)", item);
    }

    private long movement(long lot, String type, String sourceType, long sourceId) {
        return insert("stock_movements", "INSERT INTO stock_movements (stock_lot_id, movement_type, quantity_delta, balance_after, "
                + "source_type, source_id, created_by) VALUES (?, ?, -1, 9, ?, ?, ?)", lot, type, sourceType, sourceId, staff);
    }

    private long adjustment(String reason) {
        return insert("stock_adjustments", "INSERT INTO stock_adjustments (code, branch_id, reason, created_by) VALUES (?, ?, ?, ?)",
                "DC-IT-" + SEQ.incrementAndGet(), branch, reason, staff);
    }

    // ================================================================ trợ giúp

    private long insert(String table, String sql, Object... args) {
        long id = jdbc.queryForObject(sql + " RETURNING id", Long.class, args);
        undo.push(new Object[] {table, "id", id});
        return id;
    }

    private void insertKeyed(String table, String keyColumn, long key, String sql, Object... args) {
        jdbc.update(sql, args);
        undo.push(new Object[] {table, keyColumn, key});
    }

    private long insertRow(String table, Map<String, Object> row) {
        String columns = String.join(", ", row.keySet());
        String marks = String.join(", ", Collections.nCopies(row.size(), "?"));
        return insert(table, "INSERT INTO " + table + " (" + columns + ") VALUES (" + marks + ")", row.values().toArray());
    }

    private void assertRowViolates(String constraint, Map<String, Object> row) {
        String table = ROW_TABLES.stream().filter(name -> constraint.startsWith("ck_" + name + "_")).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(constraint));
        assertViolates("23514", constraint, () -> insertRow(table, row));
    }

    private void assertViolates(String sqlState, String constraint, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(DataAccessException.class).satisfies(ex -> {
            assertThat(sqlState(ex)).as("SQLSTATE").isEqualTo(sqlState);
            assertThat(NestedExceptionUtils.getMostSpecificCause(ex).getMessage()).contains("\"" + constraint + "\"");
        });
    }

    private static String sqlState(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
