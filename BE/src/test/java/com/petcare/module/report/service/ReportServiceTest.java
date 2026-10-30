package com.petcare.module.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.petcare.module.appointment.api.AppointmentReportApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.content.api.FeedbackReportApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.inventory.api.StockReportApi;
import com.petcare.module.report.dto.ExpiringLotRow;
import com.petcare.module.report.dto.FeedbackReport;
import com.petcare.module.report.dto.LostRevenueReport;
import com.petcare.module.report.dto.NoShowReport;
import com.petcare.module.report.dto.RevaccinationReport;
import com.petcare.module.report.dto.RevenueReport;
import com.petcare.module.report.dto.StockAlertReport;
import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.api.SalesReportApi;
import com.petcare.module.visit.api.VaccinationReportApi;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;

/** Ghép báo cáo UC89 (BR-BC-01…04; docs/adr/0087): phạm vi trước kỳ, gắn tên, tỷ lệ, tổng. */
class ReportServiceTest {

    private static final LocalDate FROM = LocalDate.parse("2026-10-01");
    private static final LocalDate TO = LocalDate.parse("2026-10-31");

    private final SalesReportApi sales = mock(SalesReportApi.class);
    private final AppointmentReportApi appointments = mock(AppointmentReportApi.class);
    private final VaccinationReportApi vaccinations = mock(VaccinationReportApi.class);
    private final StockReportApi stock = mock(StockReportApi.class);
    private final FeedbackReportApi feedback = mock(FeedbackReportApi.class);
    private final BranchScope scope = mock(BranchScope.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    /** 2026-10-30 10:00 giờ Việt Nam. */
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-30T03:00:00Z"), ZoneOffset.UTC);
    private ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService(sales, appointments, vaccinations, stock, feedback, scope, configs, branches,
                catalog, clock);
        when(configs.getInt(ConfigKey.REPORT_MAX_PERIOD_MONTHS)).thenReturn(12);
        when(configs.getInt(ConfigKey.STOCK_EXPIRY_WARNING_DAYS)).thenReturn(30);
        when(scope.resolve(null)).thenReturn(null);
        branch(1L, "CN Quận 1");
        branch(2L, "CN Quận 2");
    }

    private void branch(long id, String name) {
        when(branches.findBranch(id)).thenReturn(Optional.of(
                new BranchSummary(id, name, "Địa chỉ", "0280000000", BranchStatus.ACTIVE, false)));
    }

    @Test
    void scopeIsResolvedBeforeThePeriodIsValidated() {
        when(scope.resolve(2L)).thenThrow(new AccessDeniedScopeException("branch:2", "branch:1"));

        assertThatThrownBy(() -> service.revenue(2L, TO, FROM)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(sales);
    }

    @Test
    void invalidPeriodCallsNoApi() {
        assertThatThrownBy(() -> service.revenue(null, TO, FROM)).isInstanceOf(BusinessRuleViolationException.class);

        InOrder order = inOrder(scope);
        order.verify(scope).resolve(null);
        verifyNoInteractions(sales, appointments, vaccinations, feedback);
    }

    @Test
    void resolvedBranchIsPassedToTheApi() {
        when(scope.resolve(null)).thenReturn(5L);

        service.revenue(null, FROM, TO);

        verify(sales).revenue(5L, FROM, TO);
    }

    @Test
    void revenueTotalAndBranchNames() {
        when(sales.revenue(null, FROM, TO)).thenReturn(List.of(
                new SalesReportApi.RevenueRow(1L, OrderSource.VISIT, 2, 100L), new SalesReportApi.RevenueRow(2L, OrderSource.RETAIL, 1, 50L)));

        RevenueReport report = service.revenue(null, FROM, TO);

        assertThat(report.totalAmount()).isEqualTo(150L);
        assertThat(report.from()).isEqualTo(FROM);
        assertThat(report.rows()).extracting("branchName").containsExactly("CN Quận 1", "CN Quận 2");
        verify(branches).findBranch(1L);
    }

    @Test
    void lostRevenueCountAndTotal() {
        Instant at = Instant.parse("2026-10-10T03:00:00Z");
        when(sales.lostRevenue(null, FROM, TO)).thenReturn(List.of(
                new SalesReportApi.LostRevenueRow(10L, "DH-1", 1L, OrderSource.VISIT, 40L, "Khách bỏ về", at),
                new SalesReportApi.LostRevenueRow(11L, "DH-2", 2L, OrderSource.RETAIL, 60L, "Không trả", at)));

        LostRevenueReport report = service.lostRevenue(null, FROM, TO);

        assertThat(report.count()).isEqualTo(2);
        assertThat(report.totalAmount()).isEqualTo(100L);
        assertThat(report.rows().get(0).reason()).isEqualTo("Khách bỏ về");
    }

    @Test
    void completedServicesAreNamedFromTheCatalog() {
        when(sales.completedServiceCounts(null, FROM, TO)).thenReturn(List.of(
                new SalesReportApi.ServiceCountRow(1L, ServiceGroup.MEDICAL, 9L, 3)));
        when(catalog.findServices(List.of(9L))).thenReturn(List.of(
                new ServiceInfo(9L, "Khám tổng quát", ServiceGroup.MEDICAL, null, 100, true)));

        assertThat(service.completedServices(null, FROM, TO).rows())
                .singleElement().satisfies(row -> assertThat(row.serviceName()).isEqualTo("Khám tổng quát"));
    }

    @Test
    void ratesRoundHalfUpToFourPlacesAndAreZeroWhenNothingIsDue() {
        when(appointments.noShowAndLateCancel(null, FROM, TO)).thenReturn(List.of(
                new AppointmentReportApi.NoShowRow(1L, 3, 1, 2), new AppointmentReportApi.NoShowRow(2L, 0, 0, 0)));

        NoShowReport report = service.noShow(null, FROM, TO);

        assertThat(report.rows().get(0).noShowRate()).isEqualByComparingTo("0.3333");
        assertThat(report.rows().get(0).lateCancelRate()).isEqualByComparingTo("0.6667");
        assertThat(report.rows().get(0).noShowRate().scale()).isEqualTo(4);
        assertThat(report.rows().get(1).noShowRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.rows().get(1).lateCancelRate().scale()).isEqualTo(4);
    }

    @Test
    void revaccinationRowsNameTheVaccineTypeAndComputeTheRate() {
        when(vaccinations.revaccinationReturn(null, FROM, TO)).thenReturn(List.of(new VaccinationReportApi.ReturnRateRow(1L, 7L, 4, 3)));
        when(catalog.findVaccineTypeNames(List.of(7L))).thenReturn(java.util.Map.of(7L, "Dại"));

        RevaccinationReport report = service.revaccinationReturn(null, FROM, TO);

        assertThat(report.rows()).singleElement().satisfies(row -> {
            assertThat(row.vaccineTypeName()).isEqualTo("Dại");
            assertThat(row.rate()).isEqualByComparingTo("0.7500");
        });
    }

    @Test
    void stockAlertsUseTheConfiguredDaysWhenAbsentAndMarkExpiredLots() {
        LocalDate today = LocalDate.parse("2026-10-30");
        when(stock.lowStock(null)).thenReturn(List.of(new StockReportApi.LowStockRow(1L, 5L, 2, 10)));
        when(stock.expiringLots(null, 30)).thenReturn(List.of(
                new StockReportApi.ExpiringLotRow(1L, 5L, 100L, "A", today.minusDays(1), 3),
                new StockReportApi.ExpiringLotRow(1L, 5L, 101L, null, today, 1)));
        when(catalog.findProducts(List.of(5L))).thenReturn(List.of(new ProductInfo(5L, "Thức ăn", ProductType.GOODS,
                false, true, null, 100, true)));

        StockAlertReport report = service.stockAlerts(null, null);

        verify(stock).expiringLots(null, 30);
        assertThat(report.lowStock()).singleElement().satisfies(row -> {
            assertThat(row.productName()).isEqualTo("Thức ăn");
            assertThat(row.availableQuantity()).isEqualTo(2);
            assertThat(row.minQuantity()).isEqualTo(10);
        });
        assertThat(report.expiringLots()).extracting(ExpiringLotRow::expired).containsExactly(true, false);
    }

    @Test
    void explicitWithinDaysOverridesTheConfiguration() {
        service.stockAlerts(null, 7);

        verify(stock).expiringLots(null, 7);
    }

    @Test
    void feedbackUnbranchedRowHasNullName() {
        when(feedback.feedbackStats(null, FROM, TO)).thenReturn(List.of(
                new FeedbackReportApi.FeedbackStatRow(1L, 2, new BigDecimal("4.50")), new FeedbackReportApi.FeedbackStatRow(null, 1, null)));

        FeedbackReport report = service.feedback(null, FROM, TO);

        assertThat(report.rows().get(0).branchName()).isEqualTo("CN Quận 1");
        assertThat(report.rows().get(1).branchName()).isNull();
        assertThat(report.rows().get(1).averageRating()).isNull();
    }
}
