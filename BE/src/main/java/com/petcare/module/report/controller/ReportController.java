package com.petcare.module.report.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.report.dto.CompletedServicesReport;
import com.petcare.module.report.dto.FeedbackReport;
import com.petcare.module.report.dto.LostRevenueReport;
import com.petcare.module.report.dto.NoShowReport;
import com.petcare.module.report.dto.RevaccinationReport;
import com.petcare.module.report.dto.RevenueReport;
import com.petcare.module.report.dto.StockAlertReport;
import com.petcare.module.report.service.ReportService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

/** Báo cáo UC89 (report-v1 #1–7). Không chứa logic: phạm vi, kỳ và ghép số liệu ở {@link ReportService}. */
@RestController
@RequestMapping("/api/reports")
@Validated
public class ReportController {

    private static final String ACCESS = " Actor: A04, A05. Quyền: SUPER_MANAGER: toàn chuỗi · BRANCH_MANAGER: chi nhánh "
            + "mình. Chuyển trạng thái: —.";

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @Operation(operationId = "getRevenueReport", tags = "Reports", summary = "Doanh thu theo nguồn",
            description = "UC89 · BR-BC-01, 02, 03." + ACCESS,
            parameters = {
                    @Parameter(name = "branchId", in = ParameterIn.QUERY, example = "2"),
                    @Parameter(name = "from", in = ParameterIn.QUERY, example = "2026-10-01"),
                    @Parameter(name = "to", in = ParameterIn.QUERY, example = "2026-10-31")})
    @ApiErrorDoc(status = 400, description = "`BR-BC-01` kỳ vượt 12 tháng [CFG] hoặc from > to")
    @ApiErrorDoc(status = 403, description = "`BR-BC-01` chi nhánh ngoài phạm vi")
    @GetMapping("/revenue")
    @PreAuthorize(ReportAccess.MANAGERS)
    public ApiResponse<RevenueReport> revenue(
            @RequestParam(required = false) @Positive(message = "branchId phải là số dương") Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reports.revenue(branchId, from, to));
    }

    @Operation(operationId = "getLostRevenueReport", tags = "Reports", summary = "Thất thu",
            description = "UC89 · BR-BC-01, 02, BR-BH-05." + ACCESS,
            parameters = {
                    @Parameter(name = "branchId", in = ParameterIn.QUERY, example = "2"),
                    @Parameter(name = "from", in = ParameterIn.QUERY, example = "2026-10-01"),
                    @Parameter(name = "to", in = ParameterIn.QUERY, example = "2026-10-31")})
    @ApiErrorDoc(status = 400, description = "`BR-BC-01` kỳ vượt 12 tháng [CFG] hoặc from > to")
    @ApiErrorDoc(status = 403, description = "`BR-BC-01` chi nhánh ngoài phạm vi")
    @GetMapping("/lost-revenue")
    @PreAuthorize(ReportAccess.MANAGERS)
    public ApiResponse<LostRevenueReport> lostRevenue(
            @RequestParam(required = false) @Positive(message = "branchId phải là số dương") Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reports.lostRevenue(branchId, from, to));
    }

    @Operation(operationId = "getCompletedServicesReport", tags = "Reports", summary = "Lượt dịch vụ hoàn tất",
            description = "UC89 · BR-BC-01, 03." + ACCESS,
            parameters = {
                    @Parameter(name = "branchId", in = ParameterIn.QUERY, example = "2"),
                    @Parameter(name = "from", in = ParameterIn.QUERY, example = "2026-10-01"),
                    @Parameter(name = "to", in = ParameterIn.QUERY, example = "2026-10-31")})
    @ApiErrorDoc(status = 400, description = "`BR-BC-01` kỳ vượt 12 tháng [CFG] hoặc from > to")
    @ApiErrorDoc(status = 403, description = "`BR-BC-01` chi nhánh ngoài phạm vi")
    @GetMapping("/completed-services")
    @PreAuthorize(ReportAccess.MANAGERS)
    public ApiResponse<CompletedServicesReport> completedServices(
            @RequestParam(required = false) @Positive(message = "branchId phải là số dương") Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reports.completedServices(branchId, from, to));
    }

    @Operation(operationId = "getNoShowReport", tags = "Reports", summary = "Tỷ lệ NO_SHOW và hủy muộn",
            description = "UC89 · BR-BC-01, 03." + ACCESS,
            parameters = {
                    @Parameter(name = "branchId", in = ParameterIn.QUERY, example = "2"),
                    @Parameter(name = "from", in = ParameterIn.QUERY, example = "2026-10-01"),
                    @Parameter(name = "to", in = ParameterIn.QUERY, example = "2026-10-31")})
    @ApiErrorDoc(status = 400, description = "`BR-BC-01` kỳ vượt 12 tháng [CFG] hoặc from > to")
    @ApiErrorDoc(status = 403, description = "`BR-BC-01` chi nhánh ngoài phạm vi")
    @GetMapping("/no-show")
    @PreAuthorize(ReportAccess.MANAGERS)
    public ApiResponse<NoShowReport> noShow(
            @RequestParam(required = false) @Positive(message = "branchId phải là số dương") Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reports.noShow(branchId, from, to));
    }

    @Operation(operationId = "getRevaccinationReport", tags = "Reports", summary = "Tỷ lệ quay lại tái chủng",
            description = "UC89 · BR-BC-01, 03, 04." + ACCESS,
            parameters = {
                    @Parameter(name = "branchId", in = ParameterIn.QUERY, example = "2"),
                    @Parameter(name = "from", in = ParameterIn.QUERY, example = "2026-10-01"),
                    @Parameter(name = "to", in = ParameterIn.QUERY, example = "2026-10-31")})
    @ApiErrorDoc(status = 400, description = "`BR-BC-01` kỳ vượt 12 tháng [CFG] hoặc from > to")
    @ApiErrorDoc(status = 403, description = "`BR-BC-01` chi nhánh ngoài phạm vi")
    @GetMapping("/revaccination-return")
    @PreAuthorize(ReportAccess.MANAGERS)
    public ApiResponse<RevaccinationReport> revaccinationReturn(
            @RequestParam(required = false) @Positive(message = "branchId phải là số dương") Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reports.revaccinationReturn(branchId, from, to));
    }

    @Operation(operationId = "getStockAlertReport", tags = "Reports",
            summary = "Tồn dưới ngưỡng và lô sắp / đã hết hạn",
            description = "UC89 · BR-BC-01, 03, BR-KO-07." + ACCESS,
            parameters = {
                    @Parameter(name = "branchId", in = ParameterIn.QUERY, example = "2"),
                    @Parameter(name = "withinDays", in = ParameterIn.QUERY, example = "30")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403, description = "`BR-BC-01` chi nhánh ngoài phạm vi")
    @GetMapping("/stock-alerts")
    @PreAuthorize(ReportAccess.MANAGERS)
    public ApiResponse<StockAlertReport> stockAlerts(
            @RequestParam(required = false) @Positive(message = "branchId phải là số dương") Long branchId,
            @RequestParam(required = false) @Min(value = 0, message = "withinDays phải từ 0 tới 365")
            @Max(value = 365, message = "withinDays phải từ 0 tới 365") Integer withinDays) {
        return ApiResponse.ok(reports.stockAlerts(branchId, withinDays));
    }

    @Operation(operationId = "getFeedbackReport", tags = "Reports", summary = "Feedback theo chi nhánh",
            description = "UC89 · BR-BC-01, 03, BR-DG-03." + ACCESS,
            parameters = {
                    @Parameter(name = "branchId", in = ParameterIn.QUERY, example = "2"),
                    @Parameter(name = "from", in = ParameterIn.QUERY, example = "2026-10-01"),
                    @Parameter(name = "to", in = ParameterIn.QUERY, example = "2026-10-31")})
    @ApiErrorDoc(status = 400, description = "`BR-BC-01` kỳ vượt 12 tháng [CFG] hoặc from > to")
    @ApiErrorDoc(status = 403, description = "`BR-BC-01` chi nhánh ngoài phạm vi")
    @GetMapping("/feedback")
    @PreAuthorize(ReportAccess.MANAGERS)
    public ApiResponse<FeedbackReport> feedback(
            @RequestParam(required = false) @Positive(message = "branchId phải là số dương") Long branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reports.feedback(branchId, from, to));
    }
}
