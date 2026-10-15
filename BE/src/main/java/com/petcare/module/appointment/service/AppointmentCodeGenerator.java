package com.petcare.module.appointment.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.platform.config.TimeConfig;

/**
 * Mã lịch hẹn hiển thị (docs/adr/0041): {@code LH-<yyMMdd ngày đặt theo giờ Việt Nam>-<số của appointment_code_seq, ít
 * nhất 4 chữ số>}, ví dụ {@code LH-261015-0007}. Duy nhất theo cấu tạo (sequence toàn cục), không cần khóa; số không
 * reset theo ngày và có thể nhảy cóc khi transaction rollback (sequence không lùi) — lịch hẹn không yêu cầu số liên tục.
 * {@code MAXVALUE 9999999999} giữ mã ≤ 20 ký tự ({@code appointments.code VARCHAR(20)}).
 * <p>
 * Gọi sau khi mọi guard đã qua, để lượt bị từ chối không tốn số. Cần transaction ghi: PostgreSQL cấm {@code nextval} trong
 * transaction chỉ đọc.
 */
@Component
public class AppointmentCodeGenerator {

    static final String PREFIX = "LH-";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyMMdd");

    private final AppointmentRepository appointments;
    private final Clock clock;

    public AppointmentCodeGenerator(AppointmentRepository appointments, Clock clock) {
        this.appointments = appointments;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextCode() {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("AppointmentCodeGenerator.nextCode cần transaction ghi, không được readOnly");
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), TimeConfig.BUSINESS_ZONE);
        return format(today, appointments.nextCodeValue());
    }

    static String format(LocalDate day, long number) {
        return PREFIX + DAY.format(day) + "-" + String.format("%04d", number);
    }
}
