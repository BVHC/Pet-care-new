package com.petcare.module.sales;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;

/**
 * Dữ liệu dùng chung cho IT thu ngân (T30, docs/adr/0064): giờ mở cửa, ngày nghỉ, cờ cấp cứu, ca thu ngân bằng SQL.
 * {@link #cleanCashier()} chạy trước {@code cleanSales()} / {@code cleanInventory()} của lớp cha (JUnit chạy
 * {@code @AfterEach} của lớp con trước) và xóa phiếu thu cùng Order đã thu trước, vì {@code orders.payment_id} trỏ tới
 * {@code payments} và {@code payments.customer_id} chặn xóa khách.
 */
public abstract class CashierItSupport extends SalesItSupport {

    private final List<Long> cashierBranches = new ArrayList<>();

    @Override
    protected long newBranch() {
        long id = super.newBranch();
        cashierBranches.add(id);
        return id;
    }

    @AfterEach
    void cleanCashier() {
        if (cashierBranches.isEmpty()) {
            return;
        }
        String b = String.join(", ", cashierBranches.stream().map(String::valueOf).toList());
        String paid = "SELECT id FROM orders WHERE payment_id IN (SELECT id FROM payments WHERE branch_id IN (" + b
                + "))";
        jdbc.update("DELETE FROM order_lines WHERE order_id IN (" + paid + ")");
        jdbc.update("DELETE FROM orders WHERE payment_id IN (SELECT id FROM payments WHERE branch_id IN (" + b + "))");
        jdbc.update("DELETE FROM payments WHERE branch_id IN (" + b + ")");
        jdbc.update("DELETE FROM cashier_shifts WHERE branch_id IN (" + b + ")");
        jdbc.update("DELETE FROM holidays WHERE branch_id IN (" + b + ")");
        jdbc.update("DELETE FROM opening_hours WHERE branch_id IN (" + b + ")");
        cashierBranches.clear();
    }

    protected long receptionist(long branchId) {
        return staff("RECEPTIONIST", branchId);
    }

    /** Cùng các khoảng giờ cho cả 7 ngày, hiệu lực từ {@code from}; {@code open2}/{@code close2} null nếu một khoảng. */
    protected void openingHours(long branchId, LocalDate from, String open1, String close1, String open2,
            String close2) {
        for (int day = 1; day <= 7; day++) {
            jdbc.update("""
                    INSERT INTO opening_hours (branch_id, effective_from, day_of_week, open_1, close_1, open_2, close_2)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", branchId, from, day, time(open1), time(close1), time(open2),
                    time(close2));
        }
    }

    private static LocalTime time(String value) {
        return value == null ? null : LocalTime.parse(value);
    }

    protected void holiday(long branchId, LocalDate date, long createdBy) {
        jdbc.update("INSERT INTO holidays (branch_id, holiday_date, reason, created_by) VALUES (?, ?, 'IT', ?)",
                branchId, date, createdBy);
    }

    protected void acceptAfterHours(long branchId) {
        jdbc.update("UPDATE branches SET accepts_after_hours_emergency = true WHERE id = ?", branchId);
    }

    /** Ca {@code OPEN} chèn thẳng bằng SQL. */
    protected long openShiftSql(long branchId, long cashierId, boolean afterHours, LocalDate businessDate,
            Instant openedAt) {
        return jdbc.queryForObject("""
                INSERT INTO cashier_shifts (branch_id, cashier_id, is_after_hours, business_date, status, opened_at)
                VALUES (?, ?, ?, ?, 'OPEN', ?) RETURNING id
                """, Long.class, branchId, cashierId, afterHours, businessDate, java.sql.Timestamp.from(openedAt));
    }

    protected Map<String, Object> shiftRow(long shiftId) {
        return jdbc.queryForMap("SELECT * FROM cashier_shifts WHERE id = ?", shiftId);
    }

    /** Phiếu thu chèn thẳng bằng SQL (mã duy nhất theo UUID). */
    protected long paymentSql(long branchId, long customerId, long shiftId, String method, long amount,
            long receivedBy) {
        return jdbc.queryForObject("""
                INSERT INTO payments (code, branch_id, customer_id, cashier_shift_id, method, amount, received_by,
                                      paid_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now()) RETURNING id
                """, Long.class, "PT-IT-" + java.util.UUID.randomUUID().toString().substring(0, 12), branchId,
                customerId, shiftId, method, amount, receivedBy);
    }

    /** Chờ tới khi có {@code count} câu đang chờ khóa dòng và chứa mọi đoạn trong {@code fragments} (không phân biệt hoa thường). */
    protected void awaitLockWait(int count, String... fragments) throws InterruptedException {
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND pid <> pg_backend_pid()");
        Object[] args = new Object[fragments.length];
        for (int i = 0; i < fragments.length; i++) {
            sql.append(" AND query ILIKE ?");
            args[i] = "%" + fragments[i] + "%";
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject(sql.toString(), Long.class, args);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("không đủ " + count + " câu chờ khóa chứa " + java.util.Arrays.toString(fragments));
    }

    /** Dòng Order dựng bằng SQL: loại, id dịch vụ / sản phẩm, mô tả, số lượng, đơn giá. */
    protected record LineSpec(String type, Long serviceId, Long productId, String description, int quantity,
            long unitPrice) {
        static LineSpec goods(long productId, String name, int quantity, long unitPrice) {
            return new LineSpec("GOODS", null, productId, name, quantity, unitPrice);
        }
    }

    /** Order bán lẻ {@code PENDING} chèn thẳng bằng SQL, lễ tân {@code owner} là người thêm / chủ dòng. */
    protected long pendingOrderSql(long branchId, long customerId, long owner, LineSpec... lines) {
        long total = 0;
        for (LineSpec line : lines) {
            total += line.quantity() * line.unitPrice();
        }
        long id = jdbc.queryForObject("""
                INSERT INTO orders (code, source, customer_id, branch_id, status, total_amount, created_by, pending_at)
                VALUES (?, 'RETAIL', ?, ?, 'PENDING', ?, ?, now()) RETURNING id
                """, Long.class, "DH-IT-" + java.util.UUID.randomUUID().toString().substring(0, 12), customerId,
                branchId, total, owner);
        for (LineSpec line : lines) {
            jdbc.update("""
                    INSERT INTO order_lines (order_id, line_type, service_id, product_id, description, quantity,
                                             unit_price, line_total, added_by, owner_account_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, line.type(), line.serviceId(), line.productId(),
                    line.description(), line.quantity(), line.unitPrice(), line.quantity() * line.unitPrice(), owner,
                    owner);
        }
        return id;
    }

    /** Tổng tồn các lô của sản phẩm tại chi nhánh (kể cả lô hết hạn). */
    protected long stockOf(long branchId, long productId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.quantity), 0) FROM stock_lots l JOIN inventory_items i ON i.id = l.inventory_item_id
                WHERE i.branch_id = ? AND i.product_id = ?""", Long.class, branchId, productId);
    }

    /** Trigger tạm làm mọi INSERT vào {@code table} thỏa {@code condition} (biểu thức trên NEW) thất bại. */
    protected void whileInsertsFail(String table, String condition, Runnable body) {
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String function = "it_cashier_fail_" + suffix;
        String trigger = "trg_it_cashier_fail_" + suffix;
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF %s THEN RAISE EXCEPTION 'IT: lỗi ghi giả lập'; END IF;
                    RETURN NEW;
                END $$""".formatted(function, condition));
        jdbc.execute("CREATE TRIGGER %s BEFORE INSERT ON %s FOR EACH ROW EXECUTE FUNCTION %s()"
                .formatted(trigger, table, function));
        try {
            body.run();
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON " + table);
            jdbc.execute("DROP FUNCTION " + function + "()");
        }
    }
}
