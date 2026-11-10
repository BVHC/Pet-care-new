package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.module.AiReadFixtures;
import com.petcare.module.AiReadItSupport;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.sales.api.VisitOrderApi;

/**
 * I-8 (07 §11, AI-0) và {@code VisitOrderApi.findServiceIdsByVisitIds} (đường lấy dịch vụ của lượt cho I-6) trên Postgres
 * thật. Ngày dùng ngày tuyệt đối để kết quả không đổi theo ngày chạy; đếm "toàn chuỗi" nên chỉ khẳng định trên sản phẩm do
 * chính test tạo.
 */
class OrderAiReadIT extends AiReadItSupport {

    @Autowired private OrderQueryApi orders;
    @Autowired private VisitOrderApi visitOrders;

    @Test
    void lastPurchaseIsTheVietnamDayOfTheNewestPaidOrderOfThatCustomerPerProduct() {
        long customer = AiReadFixtures.next();
        long stranger = AiReadFixtures.next();
        long a = AiReadFixtures.next();
        long b = AiReadFixtures.next();
        long c = AiReadFixtures.next();
        Instant older = noonVn(LocalDate.of(2026, 9, 1));
        Instant lateUtcEvening = LocalDate.of(2026, 10, 31).atTime(17, 30).toInstant(ZoneOffset.UTC);
        long first = fx.order(customer, null, "PAID", older);
        fx.goodsLine(first, a, 1);
        long second = fx.order(customer, null, "PAID", lateUtcEvening);
        fx.goodsLine(second, a, 1);
        fx.goodsLine(second, b, 2);
        // paid_at đặt cả ở đơn chưa thu / đã hủy: chỉ bộ lọc trạng thái PAID mới loại được chúng
        fx.goodsLine(fx.order(customer, null, "OPEN", lateUtcEvening), c, 1);
        fx.goodsLine(fx.order(stranger, null, "PAID", older), c, 1);
        fx.goodsLine(fx.order(customer, null, "CANCELLED", lateUtcEvening), c, 1);

        assertThat(orders.findLastPurchaseDates(customer, List.of(a, b, c)))
                .as("ngày theo giờ Việt Nam; đơn chưa thu, đã hủy, của khách khác không tính")
                .containsOnly(entry(a, LocalDate.of(2026, 11, 1)), entry(b, LocalDate.of(2026, 11, 1)));
        assertThat(orders.findLastPurchaseDates(customer, List.of(a))).containsOnlyKeys(a);
        assertThat(orders.findLastPurchaseDates(customer, List.of())).isEmpty();
    }

    @Test
    void soldUnitsSumPaidOrdersFromTheFirstMomentOfSinceInVietnamTimeAcrossTheChain() {
        long customer = AiReadFixtures.next();
        long stranger = AiReadFixtures.next();
        long x = AiReadFixtures.next();
        long y = AiReadFixtures.next();
        long z = AiReadFixtures.next();
        LocalDate since = LocalDate.of(2026, 10, 1);
        Instant sinceStart = startOfDayVn(since);
        long paid = fx.order(customer, null, "PAID", noonVn(LocalDate.of(2026, 10, 15)));
        fx.goodsLine(paid, x, 2);
        fx.serviceLine(paid, AiReadFixtures.next());
        fx.goodsLine(fx.order(stranger, null, "PAID", noonVn(LocalDate.of(2026, 10, 20))), x, 3);
        fx.goodsLine(fx.order(customer, null, "PAID", noonVn(LocalDate.of(2026, 9, 20))), y, 4);
        // paid_at đặt cả ở đơn chưa thu / đã hủy: chỉ bộ lọc trạng thái PAID mới loại được chúng
        fx.goodsLine(fx.order(customer, null, "OPEN", noonVn(LocalDate.of(2026, 10, 16))), x, 7);
        fx.goodsLine(fx.order(customer, null, "CANCELLED", noonVn(LocalDate.of(2026, 10, 17))), x, 9);
        fx.goodsLine(fx.order(customer, null, "PAID", sinceStart), z, 1);
        fx.goodsLine(fx.order(customer, null, "PAID", sinceStart.minusMillis(1)), z, 10);

        assertThat(orders.sumSoldUnitsByProduct(since))
                .containsEntry(x, 5L)
                .doesNotContainKey(y)
                .containsEntry(z, 1L);
    }

    @Test
    void serviceIdsByVisitCoverEveryVisitInOneCallAndSkipVisitsWithoutServiceLines() {
        long customer = AiReadFixtures.next();
        long visit1 = AiReadFixtures.next();
        long visit2 = AiReadFixtures.next();
        long visitWithoutOrder = AiReadFixtures.next();
        long visitWithGoodsOnly = AiReadFixtures.next();
        long svcA = AiReadFixtures.next();
        long svcB = AiReadFixtures.next();
        long order1 = fx.order(customer, visit1, "OPEN", null);
        fx.serviceLine(order1, svcA);
        fx.goodsLine(order1, AiReadFixtures.next(), 1);
        fx.serviceLine(order1, svcB);
        long order2 = fx.order(customer, visit2, "OPEN", null);
        fx.serviceLine(order2, svcA);
        fx.serviceLine(order2, svcA);
        fx.goodsLine(fx.order(customer, visitWithGoodsOnly, "OPEN", null), AiReadFixtures.next(), 1);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Map<Long, List<Long>> byVisit = tx.execute(s -> visitOrders.findServiceIdsByVisitIds(
                List.of(visit1, visit2, visitWithoutOrder, visitWithGoodsOnly)));
        Map<Long, List<Long>> none = tx.execute(s -> visitOrders.findServiceIdsByVisitIds(Set.of()));

        assertThat(byVisit).containsOnly(entry(visit1, List.of(svcA, svcB)), entry(visit2, List.of(svcA, svcA)));
        assertThat(none).isEmpty();
    }
}
