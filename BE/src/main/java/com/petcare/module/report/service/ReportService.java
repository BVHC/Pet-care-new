package com.petcare.module.report.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.api.AppointmentReportApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.content.api.FeedbackReportApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.inventory.api.StockReportApi;
import com.petcare.module.report.dto.CompletedServicesReport;
import com.petcare.module.report.dto.ExpiringLotRow;
import com.petcare.module.report.dto.FeedbackReport;
import com.petcare.module.report.dto.FeedbackStatRow;
import com.petcare.module.report.dto.LostRevenueReport;
import com.petcare.module.report.dto.LostRevenueRow;
import com.petcare.module.report.dto.LowStockRow;
import com.petcare.module.report.dto.NoShowReport;
import com.petcare.module.report.dto.NoShowRow;
import com.petcare.module.report.dto.RevaccinationReport;
import com.petcare.module.report.dto.RevenueReport;
import com.petcare.module.report.dto.RevenueRow;
import com.petcare.module.report.dto.ReturnRateRow;
import com.petcare.module.report.dto.ServiceCountRow;
import com.petcare.module.report.dto.StockAlertReport;
import com.petcare.module.sales.api.SalesReportApi;
import com.petcare.module.visit.api.VaccinationReportApi;
import com.petcare.platform.security.BranchScope;

import lombok.RequiredArgsConstructor;

/**
 * Xem báo cáo UC89 (BR-BC-01…04; docs/adr/0087, 06 §8 Q3): module {@code report} không có bảng, chỉ kiểm phạm vi và kỳ
 * rồi ghép số liệu do module sở hữu dữ liệu tính qua các {@code *ReportApi}. Thứ tự: phạm vi chi nhánh
 * ({@link BranchScope#resolve}, SUPER_MANAGER null = toàn chuỗi, BRANCH_MANAGER luôn chi nhánh mình) → kỳ (BR-BC-01) →
 * gọi API → gắn tên và tính tỷ lệ. Chỉ đọc nên mỗi use case một {@code readOnly} transaction; không khóa dòng nào.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private final SalesReportApi sales;
    private final AppointmentReportApi appointments;
    private final VaccinationReportApi vaccinations;
    private final StockReportApi stock;
    private final FeedbackReportApi feedback;
    private final BranchScope scope;
    private final SystemConfigApi configs;
    private final BranchQueryApi branches;
    private final CatalogQueryApi catalog;
    private final Clock clock;

    @Transactional(readOnly = true)
    public RevenueReport revenue(Long branchId, LocalDate from, LocalDate to) {
        Long effective = scopedPeriod(branchId, from, to);
        BranchNames names = new BranchNames();
        List<RevenueRow> rows = sales.revenue(effective, from, to).stream()
                .map(r -> new RevenueRow(r.branchId(), names.of(r.branchId()), r.source(), r.orderCount(),
                        r.amount()))
                .toList();
        return new RevenueReport(from, to, rows, rows.stream().mapToLong(RevenueRow::amount).sum());
    }

    @Transactional(readOnly = true)
    public LostRevenueReport lostRevenue(Long branchId, LocalDate from, LocalDate to) {
        Long effective = scopedPeriod(branchId, from, to);
        List<LostRevenueRow> rows = sales.lostRevenue(effective, from, to).stream()
                .map(r -> new LostRevenueRow(r.orderId(), r.orderCode(), r.branchId(), r.source(), r.amount(),
                        r.reason(), r.cancelledAt()))
                .toList();
        return new LostRevenueReport(from, to, rows, rows.size(), rows.stream().mapToLong(LostRevenueRow::amount).sum());
    }

    @Transactional(readOnly = true)
    public CompletedServicesReport completedServices(Long branchId, LocalDate from, LocalDate to) {
        Long effective = scopedPeriod(branchId, from, to);
        List<SalesReportApi.ServiceCountRow> counts = sales.completedServiceCounts(effective, from, to);
        Map<Long, String> serviceNames = lookup(counts.stream().map(SalesReportApi.ServiceCountRow::serviceId),
                catalog::findServices, ServiceInfo::serviceId, ServiceInfo::name);
        List<ServiceCountRow> rows = counts.stream()
                .map(c -> new ServiceCountRow(c.branchId(), c.group(), c.serviceId(),
                        serviceNames.get(c.serviceId()), c.count()))
                .toList();
        return new CompletedServicesReport(from, to, rows);
    }

    @Transactional(readOnly = true)
    public NoShowReport noShow(Long branchId, LocalDate from, LocalDate to) {
        Long effective = scopedPeriod(branchId, from, to);
        List<NoShowRow> rows = appointments.noShowAndLateCancel(effective, from, to).stream()
                .map(r -> new NoShowRow(r.branchId(), r.dueCount(), r.noShowCount(), r.lateCancelCount(),
                        rate(r.noShowCount(), r.dueCount()), rate(r.lateCancelCount(), r.dueCount())))
                .toList();
        return new NoShowReport(from, to, rows);
    }

    @Transactional(readOnly = true)
    public RevaccinationReport revaccinationReturn(Long branchId, LocalDate from, LocalDate to) {
        Long effective = scopedPeriod(branchId, from, to);
        List<VaccinationReportApi.ReturnRateRow> data = vaccinations.revaccinationReturn(effective, from, to);
        List<Long> typeIds = data.stream().map(VaccinationReportApi.ReturnRateRow::vaccineTypeId).distinct().toList();
        Map<Long, String> typeNames = typeIds.isEmpty() ? Map.of() : catalog.findVaccineTypeNames(typeIds);
        List<ReturnRateRow> rows = data.stream()
                .map(r -> new ReturnRateRow(r.branchId(), r.vaccineTypeId(), typeNames.get(r.vaccineTypeId()),
                        r.dueCount(), r.returnedCount(), rate(r.returnedCount(), r.dueCount())))
                .toList();
        return new RevaccinationReport(from, to, rows);
    }

    /** Tại thời điểm xem, không theo kỳ; {@code withinDays} null = [CFG] {@code stock.expiry_warning_days}. */
    @Transactional(readOnly = true)
    public StockAlertReport stockAlerts(Long branchId, Integer withinDays) {
        Long effective = scope.resolve(branchId);
        int days = withinDays != null ? withinDays : configs.getInt(ConfigKey.STOCK_EXPIRY_WARNING_DAYS);
        List<StockReportApi.LowStockRow> low = stock.lowStock(effective);
        List<StockReportApi.ExpiringLotRow> lots = stock.expiringLots(effective, days);
        Map<Long, String> productNames = lookup(
                java.util.stream.Stream.concat(low.stream().map(StockReportApi.LowStockRow::productId),
                        lots.stream().map(StockReportApi.ExpiringLotRow::productId)),
                catalog::findProducts, ProductInfo::productId, ProductInfo::name);
        LocalDate today = LocalDate.now(clock);
        return new StockAlertReport(
                low.stream().map(r -> new LowStockRow(r.branchId(), r.productId(), productNames.get(r.productId()),
                        r.available(), r.minQuantity())).toList(),
                lots.stream().map(r -> new ExpiringLotRow(r.branchId(), r.productId(),
                        productNames.get(r.productId()), r.stockLotId(), r.lotNumber(), r.expiryDate(), r.quantity(),
                        r.expiryDate().isBefore(today))).toList());
    }

    @Transactional(readOnly = true)
    public FeedbackReport feedback(Long branchId, LocalDate from, LocalDate to) {
        Long effective = scopedPeriod(branchId, from, to);
        BranchNames names = new BranchNames();
        List<FeedbackStatRow> rows = feedback.feedbackStats(effective, from, to).stream()
                .map(r -> new FeedbackStatRow(r.branchId(), r.branchId() == null ? null : names.of(r.branchId()),
                        r.count(), r.averageRating()))
                .toList();
        return new FeedbackReport(from, to, rows);
    }

    /** Phạm vi trước, kỳ sau (docs/adr/0087 G4): người ngoài phạm vi không biết kỳ có hợp lệ hay không. */
    private Long scopedPeriod(Long branchId, LocalDate from, LocalDate to) {
        Long effective = scope.resolve(branchId);
        ReportPeriod.validate(from, to, configs.getInt(ConfigKey.REPORT_MAX_PERIOD_MONTHS));
        return effective;
    }

    /** {@code part / whole}, 4 chữ số thập phân; mẫu số 0 → 0 (docs/adr/0087 G6). */
    private static BigDecimal rate(int part, int whole) {
        if (whole == 0) {
            return BigDecimal.ZERO.setScale(4);
        }
        return BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_UP);
    }

    /** Một lần tra danh mục cho các id khác nhau; không có id thì không gọi. */
    private static <T> Map<Long, String> lookup(java.util.stream.Stream<Long> ids,
            Function<List<Long>, Collection<T>> finder, Function<T, Long> idOf, Function<T, String> nameOf) {
        List<Long> distinct = ids.distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return finder.apply(distinct).stream().collect(Collectors.toMap(idOf, nameOf));
    }

    /** Tên chi nhánh, mỗi id tra một lần trong một báo cáo. */
    private final class BranchNames {

        private final Map<Long, String> cache = new HashMap<>();

        String of(Long branchId) {
            return cache.computeIfAbsent(branchId, id -> branches.findBranch(id)
                    .orElseThrow(() -> new IllegalStateException("Chi nhánh #" + id + " không tồn tại")).name());
        }
    }
}
