package com.petcare.module.appointment.listener;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.petcare.module.appointment.service.AppointmentClinicCancellationService;
import com.petcare.module.appointment.service.ClinicCancelCause;
import com.petcare.module.branch.api.BranchClinicCancellationEvent;

/**
 * BR-LH-10, BR-CN-04 (UC14): quản lý chọn hủy hàng loạt khi thêm ngày nghỉ / thu hẹp giờ → Lịch hẹn#5 cho
 * {@code appointmentIds} (docs/adr/0052; trả nợ D034). Đồng bộ, trong transaction của branch (convention 07 §7.2);
 * {@code boardingBookingIds} thuộc listener của boarding.
 */
@Component
public class BranchClinicCancellationListener {

    private final AppointmentClinicCancellationService cancellations;

    public BranchClinicCancellationListener(AppointmentClinicCancellationService cancellations) {
        this.cancellations = cancellations;
    }

    @EventListener
    public void on(BranchClinicCancellationEvent event) {
        cancellations.cancelByClinic(event.appointmentIds(), cause(event.cause()));
    }

    /** {@code switch} không có {@code default}: branch thêm nguyên nhân mới thì lỗi biên dịch ở đây. */
    static ClinicCancelCause cause(BranchClinicCancellationEvent.Cause cause) {
        return switch (cause) {
            case HOLIDAY_ADDED -> ClinicCancelCause.HOLIDAY_ADDED;
            case OPENING_HOURS_REDUCED -> ClinicCancelCause.OPENING_HOURS_REDUCED;
        };
    }
}
