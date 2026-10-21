package com.petcare.module.appointment.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.petcare.module.appointment.dto.AppointmentResponse;
import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.entity.AppointmentCancelSource;
import com.petcare.module.appointment.entity.AppointmentChannel;
import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.module.appointment.service.AppointmentActions;
import com.petcare.module.catalog.api.ServiceGroup;

/**
 * {@link AppointmentMapper}: mỗi trường một giá trị riêng, nên map nhầm nguồn là đỏ; so cả record (đủ 24 trường của
 * appointment-v1 {@code Appointment}); {@code visitId} lấy từ tham số riêng (docs/adr/0058).
 */
class AppointmentMapperTest {

    private final AppointmentMapper mapper = Mappers.getMapper(AppointmentMapper.class);

    @Test
    void mapsEveryFieldFromItsOwnSource() {
        Appointment appointment = Appointment.book("LH-261015-0007", 11L, 22L, 33L, 44L, ServiceGroup.GROOMING,
                LocalDate.of(2026, 10, 20), LocalTime.of(14, 15), AppointmentChannel.COUNTER, 55L, "Ghi chú");
        set(appointment, "id", 66L);
        set(appointment, "status", AppointmentStatus.CANCELLED);
        set(appointment, "rescheduleCount", (short) 2);
        set(appointment, "lateCancel", true);
        set(appointment, "cancelSource", AppointmentCancelSource.STAFF);
        set(appointment, "cancelReason", "Khách bận");
        set(appointment, "cancelledAt", Instant.parse("2026-10-19T01:02:03Z"));

        AppointmentResponse response = mapper.toResponse(appointment,
                new AppointmentMapper.Names("Khách A", "Thú B", "Chi nhánh C", "Dịch vụ D"),
                new AppointmentActions.Flags(true, false), 77L);

        assertThat(response).isEqualTo(new AppointmentResponse(66L, "LH-261015-0007", 11L, "Khách A", 22L, "Thú B",
                33L, "Chi nhánh C", 44L, "Dịch vụ D", ServiceGroup.GROOMING, LocalDate.of(2026, 10, 20),
                LocalTime.of(14, 15), AppointmentStatus.CANCELLED, AppointmentChannel.COUNTER, 2, true,
                AppointmentCancelSource.STAFF, "Khách bận", Instant.parse("2026-10-19T01:02:03Z"), "Ghi chú", 77L,
                true, false));
        assertThat(AppointmentResponse.class.getRecordComponents()).hasSize(24);
    }

    @Test
    void flagsAreNotSwapped() {
        Appointment appointment = Appointment.book("LH-1", 1L, 2L, 3L, 4L, ServiceGroup.MEDICAL,
                LocalDate.of(2026, 10, 20), LocalTime.of(8, 0), AppointmentChannel.ONLINE, 5L, null);

        AppointmentResponse response = mapper.toResponse(appointment, new AppointmentMapper.Names("a", "b", "c", "d"),
                new AppointmentActions.Flags(false, true), null);

        assertThat(response.canReschedule()).isFalse();
        assertThat(response.canCancel()).isTrue();
        assertThat(response.note()).isNull();
        assertThat(response.cancelSource()).isNull();
        assertThat(response.visitId()).isNull();
    }

    private static void set(Appointment appointment, String field, Object value) {
        try {
            Field f = Appointment.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(appointment, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
