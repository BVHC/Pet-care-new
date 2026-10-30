package com.petcare.module.appointment.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import com.petcare.module.appointment.api.AppointmentReportApi;
import com.petcare.module.appointment.repository.AppointmentReportRepository;

import lombok.RequiredArgsConstructor;

/**
 * {@link AppointmentReportApi} (BR-BC-03 (3); docs/adr/0087). BE-2 cài thay BE-1 ngày 30/10/2026 vì interface chưa có bản
 * thật (06 §6): chỉ đọc, không khóa, tham gia transaction của bên gọi; BE-1 xem lại hoặc nhận lại hai lớp này.
 */
@Service
@RequiredArgsConstructor
public class AppointmentReportService implements AppointmentReportApi {

    private final AppointmentReportRepository reports;

    @Override
    public List<NoShowRow> noShowAndLateCancel(Long branchId, LocalDate from, LocalDate to) {
        return reports.noShowAndLateCancel(branchId, from, to);
    }
}
