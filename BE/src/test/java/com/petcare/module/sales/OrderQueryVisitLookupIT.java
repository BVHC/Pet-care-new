package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.appointment.AppointmentItSupport;
import com.petcare.module.sales.api.OrderQueryApi;

/**
 * {@code OrderQueryApi.findOrderIdsByVisitIds} trên Postgres 17 thật (thêm bởi BE-1, docs/adr/0060): đúng chiều
 * {@code visitId → orderId}, mọi trạng thái Order, id lạ bị bỏ qua, Order bán lẻ cùng khách không lẫn vào.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OrderQueryVisitLookupIT {

    @Autowired
    private OrderQueryApi orders;
    @Autowired
    private JdbcTemplate jdbc;

    private AppointmentItSupport data;
    private long branch;
    private long staff;
    private long customer;
    private long pet;

    @BeforeEach
    void setUp() {
        data = new AppointmentItSupport(jdbc);
        branch = data.branch("ACTIVE");
        staff = data.staff("RECEPTIONIST", branch);
        customer = data.counterCustomer();
        pet = data.pet(customer);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM orders WHERE branch_id = ?", branch);
        jdbc.update("DELETE FROM visits WHERE branch_id = ?", branch);
        data.cleanUp();
    }

    @Test
    void mapsEachVisitToItsVisitOrderInAnyStatus() {
        long open = visit();
        long cancelled = visit();
        long openOrder = order(open, "OPEN");
        long cancelledOrder = order(cancelled, "CANCELLED");
        long retail = jdbc.queryForObject("""
                INSERT INTO orders (code, source, customer_id, branch_id, status, created_by)
                VALUES (?, 'RETAIL', ?, ?, 'OPEN', ?) RETURNING id""", Long.class, code("R"), customer, branch, staff);

        assertThat(openOrder).as("id Order khác id lượt, nếu không đảo map sẽ không lộ").isNotEqualTo(open);
        assertThat(cancelledOrder).isNotEqualTo(cancelled);

        Map<Long, Long> found = orders.findOrderIdsByVisitIds(List.of(open, cancelled, Long.MAX_VALUE));

        assertThat(found).isEqualTo(Map.of(open, openOrder, cancelled, cancelledOrder));
        assertThat(found.values()).doesNotContain(retail);
        assertThat(orders.findOrderIdsByVisitIds(List.of())).isEmpty();
    }

    private long visit() {
        return jdbc.queryForObject("""
                INSERT INTO visits (code, branch_id, customer_id, pet_id, service_group, status, priority_class,
                        queue_sort_key, checked_in_by, checked_in_at)
                VALUES (?, ?, ?, ?, 'MEDICAL', 'WAITING', 'WALK_IN', now(), ?, now()) RETURNING id""", Long.class,
                code("V"), branch, customer, pet, staff);
    }

    /**
     * Order VISIT của lượt, bảo đảm {@code orders.id ≠ visits.id}: IT khác thường chèn lượt + Order liền nhau nên hai
     * sequence dễ trùng số, khi đó đảo khóa / giá trị của map không lộ (mutation M15, docs/adr/0060). Trùng thì xóa và
     * chèn lại — sequence đã tiến.
     */
    private long order(long visitId, String status) {
        while (true) {
            long id = jdbc.queryForObject("""
                    INSERT INTO orders (code, source, customer_id, branch_id, visit_id, status, created_by)
                    VALUES (?, 'VISIT', ?, ?, ?, ?, ?) RETURNING id""", Long.class, code("O"), customer, branch,
                    visitId, status, staff);
            if (id != visitId) {
                return id;
            }
            jdbc.update("DELETE FROM orders WHERE id = ?", id);
        }
    }

    private static String code(String prefix) {
        return "OQV" + prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }
}
