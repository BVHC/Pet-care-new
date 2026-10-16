package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.module.inventory.InventoryItSupport;

/**
 * Dữ liệu dùng chung cho IT của module sales (T19, docs/adr/0044): khách, thú, dịch vụ, sản phẩm, Visit và Order với
 * đủ khóa ngoại thật. Mỗi lớp IT dùng chi nhánh / khách riêng nên không đụng dữ liệu của IT khác; {@link #cleanSales()}
 * chạy trước {@code cleanInventory()} của lớp cha (JUnit chạy {@code @AfterEach} của lớp con trước) và xóa con trước cha.
 */
public abstract class SalesItSupport extends InventoryItSupport {

    private final List<Long> customers = new ArrayList<>();
    private final List<Long> services = new ArrayList<>();
    private final List<Long> visitIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();

    @AfterEach
    void cleanSales() {
        if (!customers.isEmpty()) {
            String c = ids(customers);
            jdbc.update("DELETE FROM order_lines WHERE order_id IN (SELECT id FROM orders WHERE customer_id IN (" + c
                    + "))");
            jdbc.update("DELETE FROM orders WHERE customer_id IN (" + c + ")");
            jdbc.update("DELETE FROM visits WHERE customer_id IN (" + c + ")");
            jdbc.update("DELETE FROM pets WHERE customer_id IN (" + c + ")");
            jdbc.update("DELETE FROM customers WHERE id IN (" + c + ")");
        }
        if (!productIds.isEmpty()) {
            jdbc.update("DELETE FROM order_lines WHERE product_id IN (" + ids(productIds) + ")");
        }
        if (!services.isEmpty()) {
            jdbc.update("DELETE FROM order_lines WHERE service_id IN (" + ids(services) + ")");
            jdbc.update("DELETE FROM branch_services WHERE service_id IN (" + ids(services) + ")");
            jdbc.update("DELETE FROM services WHERE id IN (" + ids(services) + ")");
        }
        customers.clear();
        services.clear();
        visitIds.clear();
        productIds.clear();
    }

    private static String ids(List<Long> list) {
        return String.join(", ", list.stream().map(String::valueOf).toList());
    }

    protected long newCustomer() {
        long id = jdbc.queryForObject("""
                INSERT INTO customers (full_name, phone, created_channel) VALUES (?, '0911000222', 'COUNTER')
                RETURNING id
                """, Long.class, "Khách sales IT " + UUID.randomUUID().toString().substring(0, 8));
        customers.add(id);
        return id;
    }

    protected long newPet(long customerId) {
        return jdbc.queryForObject("""
                INSERT INTO pets (customer_id, name, species, sex) VALUES (?, 'Mực', 'DOG', 'MALE') RETURNING id
                """, Long.class, customerId);
    }

    /** Dịch vụ riêng, đang kinh doanh. {@code group}: MEDICAL (kèm {@code EXAM}) hoặc GROOMING. */
    protected long newService(String group, long price) {
        long id = jdbc.queryForObject("""
                INSERT INTO services (name, service_group, medical_type, price, is_active)
                VALUES (?, ?, ?, ?, true) RETURNING id
                """, Long.class, "Dịch vụ sales IT " + UUID.randomUUID().toString().substring(0, 8), group,
                group.equals("MEDICAL") ? "EXAM" : null, price);
        services.add(id);
        return id;
    }

    protected void enableService(long branchId, long serviceId) {
        jdbc.update("INSERT INTO branch_services (branch_id, service_id, is_enabled) VALUES (?, ?, true) "
                + "ON CONFLICT DO NOTHING", branchId, serviceId);
    }

    /** Thuốc kê đơn (hàng quản lý hạn dùng) hoặc sản phẩm thường với giá {@code price}. */
    protected long newPricedProduct(String type, boolean prescription, long price) {
        long id = newProduct(type, prescription || type.equals("VACCINE"));
        jdbc.update("UPDATE products SET price = ?, is_prescription = ? WHERE id = ?", price, prescription, id);
        productIds.add(id);
        return id;
    }

    /** Visit thật; {@code assigneeId} null khi {@code WAITING}. */
    protected long newVisit(long branchId, long customerId, long petId, String group, String status, Long assigneeId,
            long checkedInBy) {
        long id = jdbc.queryForObject("""
                INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                                    queue_sort_key, assignee_id, checked_in_by, checked_in_at)
                VALUES (?, ?, ?, ?, ?, ?, 'WALK_IN', now(), ?, ?, now()) RETURNING id
                """, Long.class, "VT-" + UUID.randomUUID().toString().substring(0, 12), branchId, customerId, petId,
                group, status, assigneeId, checkedInBy);
        visitIds.add(id);
        return id;
    }

    protected long orderTotal(long orderId) {
        return jdbc.queryForObject("SELECT total_amount FROM orders WHERE id = ?", Long.class, orderId);
    }

    protected long sumOfLines(long orderId) {
        return jdbc.queryForObject("SELECT coalesce(sum(line_total), 0) FROM order_lines WHERE order_id = ?",
                Long.class, orderId);
    }

    protected String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    // ---------------------------------------------------------------- đồng thời

    /** Hibernate 6 sinh {@code FOR NO KEY UPDATE} cho {@code PESSIMISTIC_WRITE} trên PostgreSQL. */
    private static final String WAITING_ON_ORDER_LOCK = """
            SELECT count(*) FROM pg_stat_activity
            WHERE wait_event_type = 'Lock' AND query ILIKE '%from orders%for no key update%'
            """;

    /** Kết quả một việc chạy song song: giá trị hoặc lỗi ném ra. */
    protected record Outcome(Object value, Throwable error) {
    }

    /**
     * Transaction A khóa dòng {@code orders} ({@code FOR NO KEY UPDATE}); các việc chạy ở thread khác, mỗi việc tự mở
     * transaction của nó và phải cùng đứng chờ khóa (thấy trong {@code pg_stat_activity}); sau đó A chạy
     * {@code beforeCommit} rồi commit. Trả kết quả theo thứ tự truyền vào.
     */
    protected List<Outcome> whileOrderLocked(long orderId, Runnable beforeCommit, List<Callable<Object>> actions)
            throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(actions.size() + 1);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbc.queryForObject("SELECT id FROM orders WHERE id = ? FOR NO KEY UPDATE", Long.class,
                                orderId);
                        held.countDown();
                        await(proceed);
                        beforeCommit.run();
                    }));
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Object> action : actions) {
                futures.add(pool.submit(() -> {
                    try {
                        return new Outcome(action.call(), null);
                    } catch (Throwable ex) {
                        return new Outcome(null, ex);
                    }
                }));
            }
            awaitWaitingOnOrderLock(actions.size());
            proceed.countDown();
            holder.get(30, TimeUnit.SECONDS);
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            proceed.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitWaitingOnOrderLock(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(WAITING_ON_ORDER_LOCK, Long.class) < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("không đủ " + expected + " thao tác chờ khóa orders; pg_stat_activity = "
                        + jdbc.queryForList("""
                                SELECT wait_event_type, state, query FROM pg_stat_activity
                                WHERE datname = current_database() AND pid <> pg_backend_pid()"""));
            }
            Thread.sleep(20);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
