package com.petcare.module.appointment.fsm;

import static com.petcare.module.appointment.entity.AppointmentStatus.BOOKED;
import static com.petcare.module.appointment.entity.AppointmentStatus.CANCELLED;
import static com.petcare.module.appointment.entity.AppointmentStatus.CHECKED_IN;
import static com.petcare.module.appointment.entity.AppointmentStatus.COMPLETED;
import static com.petcare.module.appointment.entity.AppointmentStatus.NO_SHOW;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bảng "3. Lịch hẹn" của docs/03-state-machines.md (L79–88). #4 (khách / lễ tân hủy) và #5 (phòng khám hủy) là cùng
 * một cạnh {@code BOOKED → CANCELLED} nhưng là hai thao tác khác nhau ({@code cancelAppointment}, {@code cancelByClinic}).
 * {@code COMPLETED}, {@code CANCELLED}, {@code NO_SHOW} là FINAL nên không có khóa.
 */
@Component
public class AppointmentTransitionHandler extends StateMachineBase<AppointmentStatus> {

    @Override
    public Map<AppointmentStatus, Set<AppointmentStatus>> allowedTransitions() {
        return Map.of(
                BOOKED, Set.of(BOOKED,          // #2 đổi khung giờ
                        CHECKED_IN,             // #3 tiếp nhận (SYS ← Visit#1)
                        CANCELLED,              // #4 khách / lễ tân hủy, #5 phòng khám hủy
                        NO_SHOW),               // #6 ST05
                CHECKED_IN, Set.of(COMPLETED,   // #7 hoàn tất (SYS ← Visit#5)
                        CANCELLED));            // #8 hủy lượt khách bỏ về (SYS ← Visit#6)
    }

    @Override
    public Set<AppointmentStatus> initialStates() {
        return Set.of(BOOKED);                  // #1 đặt lịch
    }

    /** Tên hiện trong message 409 mà client nhận — tên nghiệp vụ, không lộ tên lớp (khuôn docs/adr/0033). */
    @Override
    protected String fsmName() {
        return "lịch hẹn";
    }
}
