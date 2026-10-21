package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.dto.OrderSummaryResponse;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.repository.OrderListRepository;
import com.petcare.module.sales.repository.OrderListRepository.Filter;
import com.petcare.module.sales.repository.OrderListRepository.Row;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;

@ExtendWith(MockitoExtension.class)
class OrderListServiceTest {

    private static final long BRANCH = 2L;
    /** 2026-10-19 08:30 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2026-10-19T01:30:00Z");

    @Mock
    OrderListRepository queries;
    @Mock
    CustomerQueryApi customers;
    @Mock
    SystemConfigApi configs;
    @Mock
    BranchScope branchScope;

    OrderListService service;

    @BeforeEach
    void setUp() {
        service = new OrderListService(queries, customers,
                new PendingOverdueCutoff(configs, Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh"))), branchScope);
        lenient().when(branchScope.resolve(null)).thenReturn(BRANCH);
    }

    private Filter capturedFilter() {
        ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
        verify(queries).count(filter.capture());
        return filter.getValue();
    }

    private static Row row(long id, long customerId) {
        return new Row(id, "DH-2610-0000" + id, OrderSource.RETAIL, OrderStatus.PENDING, customerId, BRANCH, 1000 * id,
                NOW, NOW, null);
    }

    @Test
    void noFilterLooksOnlyAtOwnBranch() {
        service.listOrders(null, null, null, null, null, null, null, 0, 20);

        assertThat(capturedFilter()).isEqualTo(new Filter(BRANCH, null, null, null, null, null, null, null, null));
        verifyNoInteractions(configs);
        verify(queries, never()).findPage(any(), anyLong(), anyInt());
    }

    @Test
    void dateIsTheVietnameseBusinessDay() {
        service.listOrders(OrderStatus.PAID, OrderSource.VISIT, 5L, 61L, 9L, LocalDate.of(2026, 10, 19), false, 0, 20);

        assertThat(capturedFilter()).isEqualTo(new Filter(BRANCH, OrderStatus.PAID, OrderSource.VISIT, 5L, 61L, 9L,
                Instant.parse("2026-10-18T17:00:00Z"), Instant.parse("2026-10-19T17:00:00Z"), null));
        verifyNoInteractions(configs);
    }

    @Test
    void pendingOverdueWithOneDayMeansClosedBeforeTodayMidnight() {
        when(configs.getInt(ConfigKey.ORDER_PENDING_ALERT_DAYS)).thenReturn(1);

        service.listOrders(null, null, null, null, null, null, true, 0, 20);

        assertThat(capturedFilter().pendingBefore()).isEqualTo(Instant.parse("2026-10-18T17:00:00Z"));
    }

    @Test
    void pendingOverdueFollowsTheConfiguredDays() {
        when(configs.getInt(ConfigKey.ORDER_PENDING_ALERT_DAYS)).thenReturn(3);

        service.listOrders(null, null, null, null, null, null, true, 0, 20);

        assertThat(capturedFilter().pendingBefore()).isEqualTo(Instant.parse("2026-10-16T17:00:00Z"));
    }

    @Test
    void pageAndSizeAreClamped() {
        when(queries.count(any())).thenReturn(500L);
        when(queries.findPage(any(), anyLong(), anyInt())).thenReturn(List.of());

        service.listOrders(null, null, null, null, null, null, null, -3, 1000);
        verify(queries).findPage(any(), org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.eq(100));

        service.listOrders(null, null, null, null, null, null, null, 2, 0);
        verify(queries).findPage(any(), org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.eq(1));
    }

    @Test
    void customerNamesAreJoinedInOneBatch() {
        when(queries.count(any())).thenReturn(3L);
        when(queries.findPage(any(), anyLong(), anyInt())).thenReturn(List.of(row(1, 5), row(2, 6), row(3, 5)));
        when(customers.findContacts(List.of(5L, 6L))).thenReturn(List.of(
                new CustomerContact(6L, "Bình", null, null, null, false),
                new CustomerContact(5L, "An", null, null, null, false)));

        PageResponse<OrderSummaryResponse> page = service.listOrders(null, null, null, null, null, null, null, 0, 20);

        assertThat(page.content()).extracting(OrderSummaryResponse::orderId, OrderSummaryResponse::customerName)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, "An"),
                        org.assertj.core.groups.Tuple.tuple(2L, "Bình"),
                        org.assertj.core.groups.Tuple.tuple(3L, "An"));
        assertThat(page.totalElements()).isEqualTo(3);
        verify(customers).findContacts(List.of(5L, 6L));
    }

    @Test
    void orderOfAMissingCustomerIsABug() {
        when(queries.count(any())).thenReturn(1L);
        when(queries.findPage(any(), anyLong(), anyInt())).thenReturn(List.of(row(1, 5)));
        when(customers.findContacts(List.of(5L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.listOrders(null, null, null, null, null, null, null, 0, 20))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void scopeDenialHappensBeforeAnyRead() {
        when(branchScope.resolve(null)).thenThrow(new AccessDeniedScopeException("branch", "owner"));

        assertThatThrownBy(() -> service.listOrders(null, null, null, null, null, null, null, 0, 20))
                .isInstanceOf(AccessDeniedScopeException.class);
        verifyNoInteractions(queries, customers);
    }

    @Test
    void readOnlyRepeatableRead() throws Exception {
        Transactional tx = OrderListService.class.getMethod("listOrders", OrderStatus.class, OrderSource.class,
                Long.class, Long.class, Long.class, LocalDate.class, Boolean.class, int.class, int.class)
                .getAnnotation(Transactional.class);
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }
}
