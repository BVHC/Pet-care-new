package com.petcare.module.appointment.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.platform.config.TimeConfig;

/**
 * Hai cờ {@code canReschedule}, {@code canCancel} của response {@code Appointment} (appointment-v1, "theo vai trò người
 * xem"; docs/adr/0043). Lớp thuần, dùng chung cho đặt lịch, xem, đổi khung và hủy.
 * <ul>
 * <li>{@code canCancel}: lịch {@code BOOKED} và chưa tới giờ hẹn (BR-LH-07: khách hủy "bất kỳ lúc nào trước giờ hẹn";
 * lễ tân hủy hộ cùng mốc).</li>
 * <li>{@code canReschedule}: lịch {@code BOOKED}, số lần đổi {@code < appointment.max_reschedules} [CFG]; khách còn ít
 * nhất {@code appointment.reschedule_min_lead_hours} [CFG] trước giờ hẹn (đúng bằng vẫn được, giống
 * {@link BookingWindow}); lễ tân chỉ cần chưa tới giờ hẹn (BR-LH-06).</li>
 * </ul>
 * Giờ hẹn hiểu theo {@link TimeConfig#BUSINESS_ZONE}.
 */
public final class AppointmentActions {

    /** Hai cờ của response. */
    public record Flags(boolean canReschedule, boolean canCancel) {}

    private AppointmentActions() {
    }

    /**
     * @param customerViewer người xem là khách (A02); {@code false} = lễ tân
     */
    public static Flags forViewer(Appointment appointment, boolean customerViewer, Instant now, int maxReschedules,
            int rescheduleMinLeadHours) {
        Objects.requireNonNull(appointment, "appointment");
        Objects.requireNonNull(now, "now");
        if (appointment.getStatus() != AppointmentStatus.BOOKED) {
            return new Flags(false, false);
        }
        Instant start = appointment.getSlotDate().atTime(appointment.getSlotStart())
                .atZone(TimeConfig.BUSINESS_ZONE).toInstant();
        boolean notStarted = now.isBefore(start);
        boolean turnsLeft = appointment.getRescheduleCount() < maxReschedules;
        boolean leadOk = customerViewer
                ? !now.plus(Duration.ofHours(rescheduleMinLeadHours)).isAfter(start)
                : notStarted;
        return new Flags(turnsLeft && notStarted && leadOk, notStarted);
    }
}
