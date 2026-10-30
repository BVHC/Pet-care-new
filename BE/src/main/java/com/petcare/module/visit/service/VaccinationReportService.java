package com.petcare.module.visit.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.api.VaccinationReportApi;
import com.petcare.module.visit.repository.VaccinationReportRepository;

import lombok.RequiredArgsConstructor;

/**
 * {@link VaccinationReportApi} (BR-BC-04; docs/adr/0087). BE-2 cài thay BE-1 ngày 30/10/2026 vì interface chưa có bản
 * thật (06 §6): chỉ đọc, không khóa, tham gia transaction của bên gọi; BE-1 xem lại hoặc nhận lại hai lớp này. Hạn
 * tiêm lại muộn tối đa lấy từ [CFG] {@code report.revaccination_grace_days} tại lúc xem (BR-QT-13: giá trị chạy lúc
 * kiểm, không snapshot).
 */
@Service
@RequiredArgsConstructor
public class VaccinationReportService implements VaccinationReportApi {

    private final VaccinationReportRepository reports;
    private final SystemConfigApi configs;

    @Override
    public List<ReturnRateRow> revaccinationReturn(Long branchId, LocalDate from, LocalDate to) {
        return reports.revaccinationReturn(branchId, from, to,
                configs.getInt(ConfigKey.REPORT_REVACCINATION_GRACE_DAYS));
    }
}
