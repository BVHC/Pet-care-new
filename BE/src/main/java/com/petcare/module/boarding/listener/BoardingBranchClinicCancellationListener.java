package com.petcare.module.boarding.listener;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.petcare.module.boarding.service.BoardingClinicCancellationService;
import com.petcare.module.boarding.service.BoardingClinicCause;
import com.petcare.module.branch.api.BranchClinicCancellationEvent;

/**
 * BR-LH-10, BR-CN-03, 04 (UC14): quản lý chọn hủy hàng loạt khi thêm ngày nghỉ / thu hẹp giờ → Đặt chỗ#5 cho {@code boardingBookingIds}; {@code appointmentIds} thuộc listener của appointment. Đồng bộ, trong transaction của bên phát (convention 07 §7.2): không {@code @Async}, không bắt lỗi — lỗi ở đây
 * rollback cả bên phát (docs/adr/0065).
 */
@Component
public class BoardingBranchClinicCancellationListener {

    private final BoardingClinicCancellationService cancellations;

    public BoardingBranchClinicCancellationListener(BoardingClinicCancellationService cancellations) {
        this.cancellations = cancellations;
    }

    @EventListener
    public void on(BranchClinicCancellationEvent event) {
        cancellations.cancelByClinic(event.boardingBookingIds(), cause(event.cause()));
    }

    /** {@code switch} không có {@code default}: branch thêm nguyên nhân mới thì lỗi biên dịch ở đây. */
    static BoardingClinicCause cause(BranchClinicCancellationEvent.Cause cause) {
        return switch (cause) {
            case HOLIDAY_ADDED -> BoardingClinicCause.HOLIDAY_ADDED;
            case OPENING_HOURS_REDUCED -> BoardingClinicCause.OPENING_HOURS_REDUCED;
        };
    }
}
