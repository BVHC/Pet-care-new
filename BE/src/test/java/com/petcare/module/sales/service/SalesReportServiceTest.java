package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.api.SalesReportApi.RevenueRow;
import com.petcare.module.sales.api.SalesReportApi.ServiceCountRow;
import com.petcare.module.sales.repository.SalesReportRepository;
import com.petcare.module.sales.repository.SalesReportRepository.RevenueAgg;
import com.petcare.module.sales.repository.SalesReportRepository.ServiceAgg;

class SalesReportServiceTest {

    private final SalesReportRepository reports = mock(SalesReportRepository.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final SalesReportService service = new SalesReportService(reports, catalog);

    @Test
    void periodIsConvertedToVietnamMidnightBounds() {
        service.revenue(7L, LocalDate.parse("2005-03-01"), LocalDate.parse("2005-03-31"));

        verify(reports).revenue(7L, Instant.parse("2005-02-28T17:00:00Z"), Instant.parse("2005-03-31T17:00:00Z"));
    }

    @Test
    void nullBranchIsPassedThroughAsChain() {
        service.lostRevenue(null, LocalDate.parse("2005-03-01"), LocalDate.parse("2005-03-01"));

        verify(reports).lostRevenue(null, Instant.parse("2005-02-28T17:00:00Z"), Instant.parse("2005-03-01T17:00:00Z"));
    }

    @Test
    void revenueRowsAreOrderedByBranchThenSourceDeclarationOrder() {
        when(reports.revenue(null, Instant.parse("2005-02-28T17:00:00Z"), Instant.parse("2005-03-31T17:00:00Z")))
                .thenReturn(List.of(new RevenueAgg(1, "RETAIL", 1, 30), new RevenueAgg(1, "VISIT", 2, 150)));

        assertThat(service.revenue(null, LocalDate.parse("2005-03-01"), LocalDate.parse("2005-03-31")))
                .containsExactly(new RevenueRow(1L, OrderSource.VISIT, 2, 150L),
                        new RevenueRow(1L, OrderSource.RETAIL, 1, 30L));
    }

    @Test
    void serviceGroupComesFromCatalog() {
        LocalDate day = LocalDate.parse("2005-03-10");
        when(reports.serviceCounts(5L, Instant.parse("2005-03-09T17:00:00Z"), Instant.parse("2005-03-10T17:00:00Z")))
                .thenReturn(List.of(new ServiceAgg(5, 9, 3)));
        when(catalog.findServices(List.of(9L))).thenReturn(List.of(
                new ServiceInfo(9L, "Cắt tỉa", ServiceGroup.GROOMING, null, 100, true)));

        assertThat(service.completedServiceCounts(5L, day, day))
                .containsExactly(new ServiceCountRow(5L, ServiceGroup.GROOMING, 9L, 3));
    }

    @Test
    void unknownServiceIsIllegalState() {
        LocalDate day = LocalDate.parse("2005-03-10");
        when(reports.serviceCounts(5L, Instant.parse("2005-03-09T17:00:00Z"), Instant.parse("2005-03-10T17:00:00Z")))
                .thenReturn(List.of(new ServiceAgg(5, 9, 3)));
        when(catalog.findServices(List.of(9L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.completedServiceCounts(5L, day, day))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("#9");
    }
}
