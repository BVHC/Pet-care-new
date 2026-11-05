package com.petcare.module.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.support.MutableClock;

import jakarta.persistence.EntityManagerFactory;

/**
 * Số câu SQL của Hibernate khi xem trước phiếu thu (sales-v1 #13, docs/adr/0103): không đổi theo số Order của phiếu
 * (dòng của các Order nạp theo lô, {@code @BatchSize} trên {@code SalesOrder.lines}). Mutation "bỏ {@code @BatchSize}" →
 * thêm một câu cho mỗi Order → đỏ. Context riêng bật Hibernate Statistics.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CashierClockConfig.class})
class PaymentPreviewCountIT extends CashierItSupport {

    @Autowired
    private MutableClock clock;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;
    private long branch;
    private long receptionist;
    private long customer;
    private long goods;
    private String token;

    @BeforeEach
    void setUp() {
        clock.set(CashierClockConfig.START);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled()).as("generate_statistics").isTrue();
        branch = newBranch();
        receptionist = receptionist(branch);
        long manager = staff("BRANCH_MANAGER", branch);
        token = token(receptionist);
        customer = newCustomer();
        goods = newPricedProduct("GOODS", false, 100_000);
        defaultLot(branch, goods, 50, manager);
    }

    private long order() {
        return pendingOrderSql(branch, customer, receptionist, LineSpec.goods(goods, "Hạt", 1, 100_000),
                LineSpec.goods(goods, "Hạt thêm", 2, 100_000));
    }

    private long statementsOfPreview(List<Long> orderIds) {
        statistics.clear();
        data(call(HttpMethod.POST, "/api/payments/preview", token, Map.of("orderIds", orderIds)), HttpStatus.OK);
        return statistics.getPrepareStatementCount();
    }

    @Test
    void previewCostsTheSameStatementsForOneAndForThreeOrders() {
        long first = order();
        statementsOfPreview(List.of(first));
        long one = statementsOfPreview(List.of(first));

        long three = statementsOfPreview(List.of(first, order(), order()));

        assertThat(three).as("một Order: %d câu, ba Order: %d câu", one, three).isEqualTo(one);
    }
}
