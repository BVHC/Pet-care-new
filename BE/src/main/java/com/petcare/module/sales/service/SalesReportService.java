package com.petcare.module.sales.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.api.SalesReportApi;
import com.petcare.module.sales.repository.SalesReportRepository;
import com.petcare.module.sales.repository.SalesReportRepository.ServiceAgg;
import com.petcare.platform.config.TimeConfig;

import lombok.RequiredArgsConstructor;

/**
 * Báo cáo doanh thu, thất thu, lượt dịch vụ hoàn tất (BR-BC-02, BR-BC-03 (2); docs/adr/0087). Chỉ đọc, tham gia
 * transaction của bên gọi (report mở {@code readOnly}). Kỳ {@code [from, to]} (ngày VN, cả hai đầu) → nửa mở
 * {@code [from 00:00, to + 1 00:00)}.
 */
@Service
@RequiredArgsConstructor
public class SalesReportService implements SalesReportApi {

    private final SalesReportRepository reports;
    private final CatalogQueryApi catalog;

    @Override
    public List<RevenueRow> revenue(Long branchId, LocalDate from, LocalDate to) {
        return reports.revenue(branchId, start(from), start(to.plusDays(1))).stream()
                .map(r -> new RevenueRow(r.branchId(), OrderSource.valueOf(r.source()), r.orderCount(), r.amount()))
                .sorted(Comparator.comparing(RevenueRow::branchId).thenComparing(RevenueRow::source))
                .toList();
    }

    @Override
    public List<LostRevenueRow> lostRevenue(Long branchId, LocalDate from, LocalDate to) {
        return reports.lostRevenue(branchId, start(from), start(to.plusDays(1)));
    }

    @Override
    public List<ServiceCountRow> completedServiceCounts(Long branchId, LocalDate from, LocalDate to) {
        List<ServiceAgg> aggregates = reports.serviceCounts(branchId, start(from), start(to.plusDays(1)));
        Map<Long, ServiceInfo> services = catalog.findServices(aggregates.stream().map(ServiceAgg::serviceId)
                .distinct().toList()).stream().collect(Collectors.toMap(ServiceInfo::serviceId, s -> s));
        return aggregates.stream().map(a -> {
            ServiceInfo service = services.get(a.serviceId());
            if (service == null) {
                throw new IllegalStateException("Dịch vụ #" + a.serviceId() + " không tồn tại");
            }
            return new ServiceCountRow(a.branchId(), service.group(), a.serviceId(), a.count());
        }).toList();
    }

    private static Instant start(LocalDate date) {
        return date.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
    }
}
