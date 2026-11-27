package com.petcare.module.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.identity.service.SystemConfigService;
import com.petcare.module.shop.job.OnlineOrderAutoConfirmJob;
import com.petcare.module.shop.service.OnlineOrderAutoConfirmService;
import com.petcare.module.shop.service.OnlineOrderAutoConfirmService.DueOrder;
import com.petcare.support.MutableClock;

/**
 * ST12 (docs/adr/0146, Đơn hàng online#12, BR-BH-12) trên Postgres 17 thật: đơn {@code DELIVERED} quá [CFG]
 * {@code shop.auto_confirm_days} ngày sang {@code COMPLETED} (trả hàng đã bỏ, docs/adr/0156); số ngày đọc lúc chạy; đơn đang bị khóa bị bỏ qua (SKIP
 * LOCKED); một đơn lỗi không chặn cả lô. Era <b>năm 1980</b> (job quét không có cận dưới).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, OnlineOrderAutoConfirmIT.TestBeans.class})
class OnlineOrderAutoConfirmIT extends OnlineOrderJobItSupport {

    private static final Instant START = Instant.parse("1980-06-15T03:00:00Z");
    private static final Instant RUN = START.plus(30, ChronoUnit.DAYS);

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }
    }

    @Autowired
    private OnlineOrderAutoConfirmJob job;
    @Autowired
    private OnlineOrderAutoConfirmService service;
    @Autowired
    private SystemConfigService configService;

    @Override
    protected Instant start() {
        return START;
    }

    /** Đơn đặt rồi ép sang {@code DELIVERED} với {@code delivered_at} cho trước (thỏa mọi CHECK của online_orders). */
    private Placed delivered(Shopper s, Instant deliveredAt) {
        Placed order = place(s, 1);
        Timestamp paid = Timestamp.from(START);
        jdbc.update("""
                UPDATE online_orders SET status = 'DELIVERED', paid_at = ?, shipped_at = ?, carrier = 'GHN',
                                         tracking_code = 'GHN1', delivered_at = ? WHERE id = ?""", paid, paid,
                Timestamp.from(deliveredAt), order.id());
        return order;
    }

    private String completedAt(long id) {
        Timestamp at = jdbc.queryForObject("SELECT completed_at FROM online_orders WHERE id = ?", Timestamp.class, id);
        return at == null ? null : at.toInstant().toString();
    }

    // ================================================================ đường chính

    @Test
    void aDueOrderBecomesCompletedAtRunTime() {
        Placed due = delivered(a, RUN.minus(8, ChronoUnit.DAYS));
        Placed notYet = delivered(b, RUN.minus(3, ChronoUnit.DAYS));
        clock.set(RUN);
        long noticesBefore = notices(a.accountId(), "ONLINE_ORDER");

        job.run();

        assertThat(onlineStatus(due.id())).isEqualTo("COMPLETED");
        assertThat(completedAt(due.id())).isEqualTo(RUN.toString());
        assertThat(onlineStatus(notYet.id())).as("chưa quá 7 ngày").isEqualTo("DELIVERED");
        assertThat(completedAt(notYet.id())).isNull();
        assertThat(salesStatus(due.orderId())).as("Order không đổi").isEqualTo("PENDING");
        assertThat(notices(a.accountId(), "ONLINE_ORDER")).as("ST12 không thông báo").isEqualTo(noticesBefore);
        job.run();
        assertThat(completedAt(due.id())).as("chạy lại không ghi lại").isEqualTo(RUN.toString());
    }

    @Test
    void theBoundaryIsExactlyAutoConfirmDaysAfterDelivery() {
        Placed exact = delivered(a, RUN.minus(7, ChronoUnit.DAYS));
        Placed oneMicroLater = delivered(b, RUN.minus(7, ChronoUnit.DAYS).plusNanos(1_000));
        clock.set(RUN);

        job.run();

        assertThat(onlineStatus(exact.id())).isEqualTo("COMPLETED");
        assertThat(onlineStatus(oneMicroLater.id())).isEqualTo("DELIVERED");
    }

    @Test
    void ordersInOtherStatesAreUntouchedEvenWhenOld() {
        Placed shipped = place(a, 1);
        Timestamp old = Timestamp.from(RUN.minus(20, ChronoUnit.DAYS));
        jdbc.update("""
                UPDATE online_orders SET status = 'SHIPPED', paid_at = ?, shipped_at = ?, carrier = 'GHN',
                                         tracking_code = 'GHN1' WHERE id = ?""", old, old, shipped.id());
        clock.set(RUN);

        job.run();

        assertThat(onlineStatus(shipped.id())).isEqualTo("SHIPPED");
    }

    @Test
    void theAutoConfirmDaysIsReadAtRunTime() {
        Placed order = delivered(a, RUN.minus(4, ChronoUnit.DAYS));
        clock.set(RUN);
        job.run();
        assertThat(onlineStatus(order.id())).as("7 ngày: chưa tới hạn").isEqualTo("DELIVERED");
        jdbc.update("UPDATE system_configs SET value = '3' WHERE key = 'shop.auto_confirm_days'");
        configService.reload();
        try {
            job.run();

            assertThat(onlineStatus(order.id())).as("3 ngày: tới hạn từ lần kiểm sau").isEqualTo("COMPLETED");
        } finally {
            jdbc.update("UPDATE system_configs SET value = '7' WHERE key = 'shop.auto_confirm_days'");
            configService.reload();
        }
    }

    @Test
    void theKeysetScanCoversMoreOrdersThanOneBatch() {
        Instant old = RUN.minus(10, ChronoUnit.DAYS);
        Placed first = delivered(a, old);
        Placed second = delivered(b, old);
        Placed third = delivered(a, old);
        clock.set(RUN);

        List<DueOrder> page = service.findDue(RUN, null, 0, 2);
        assertThat(page).hasSize(2);
        assertThat(service.findDue(RUN, page.get(1).deliveredAt(), page.get(1).id(), 2)).hasSize(1);
        job.run();

        assertThat(List.of(first, second, third)).allSatisfy(o -> assertThat(onlineStatus(o.id())).isEqualTo("COMPLETED"));
    }

    // ================================================================ cuộc đua và lỗi

    @Test
    void anOrderLockedByAnotherTransactionIsSkippedThenConfirmedOnTheNextRun() throws Exception {
        Placed order = delivered(a, RUN.minus(10, ChronoUnit.DAYS));
        clock.set(RUN);

        whileHeld(order.id(), () -> {
        }, job::run);

        assertThat(onlineStatus(order.id())).as("đang bị khóa: lượt này bỏ qua").isEqualTo("DELIVERED");
        job.run();
        assertThat(onlineStatus(order.id())).isEqualTo("COMPLETED");
    }

    @Test
    void anOrderTheCustomerConfirmedWhileLockedStaysCompletedAndIsNotWrittenAgain() throws Exception {
        Placed order = delivered(a, RUN.minus(10, ChronoUnit.DAYS));
        clock.set(RUN);
        Instant customerConfirmedAt = RUN.minusSeconds(60);

        whileHeld(order.id(), () -> jdbc.update(
                "UPDATE online_orders SET status = 'COMPLETED', completed_at = ? WHERE id = ?",
                Timestamp.from(customerConfirmedAt), order.id()), job::run);
        job.run();

        assertThat(completedAt(order.id())).isEqualTo(customerConfirmedAt.toString());
    }

    @Test
    void aFailingOrderRollsBackAloneAndDoesNotStopTheBatchThenRetriesNextRun() {
        Instant old = RUN.minus(10, ChronoUnit.DAYS);
        Placed broken = delivered(a, old);
        Placed fine = delivered(b, old);
        failUpdatesOf(broken.id());
        clock.set(RUN);

        job.run();

        assertThat(onlineStatus(fine.id())).isEqualTo("COMPLETED");
        assertThat(onlineStatus(broken.id())).isEqualTo("DELIVERED");

        dropFailTrigger();
        job.run();

        assertThat(onlineStatus(broken.id())).isEqualTo("COMPLETED");
        assertThat(Map.of("fine", completedAt(fine.id()))).containsEntry("fine", RUN.toString());
    }
}
