package com.petcare.module.appointment.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.petcare.module.appointment.api.AppointmentQueryApi;

/**
 * Giữ chỗ cho {@link AppointmentQueryApi} tới khi appointment (BE-1) cài thật. Theo 06-module-contracts §1: mọi
 * method ném lỗi, không trả giá trị "an toàn" (trả rỗng sẽ cho thu hẹp giờ mở cửa qua mặt BR-CN-04). Hệ quả: đặt
 * giờ mở cửa, thêm ngày nghỉ và hai endpoint xem trước ảnh hưởng của branch trả 500.
 * BE-1 xóa lớp này (cùng {@code AppointmentQueryApiPlaceholderTest}) khi thêm implementation thật.
 * <p>
 * <b>Cài thật một phần (2026-10-16, T17, BE-2 làm thay):</b> {@code existsByPet} (BR-KH-06) và {@code existsByCustomer}
 * (BR-TK-19) đọc thẳng {@code appointments} trong transaction của bên gọi; hai method danh sách vẫn ném.
 */
@Service
public class AppointmentQueryApiPlaceholder implements AppointmentQueryApi {

    static final String MESSAGE = "AppointmentQueryApi chưa được cài (module appointment, BE-1)";

    private final JdbcTemplate jdbc;

    public AppointmentQueryApiPlaceholder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<BookedSlot> findBookedFrom(Long branchId, LocalDate fromDate) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public boolean existsByPet(Long petId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM appointments WHERE pet_id = ?)", Boolean.class, petId));
    }

    @Override
    public boolean existsByCustomer(Long customerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM appointments WHERE customer_id = ?)", Boolean.class, customerId));
    }

    @Override
    public List<BookedSlot> findBookedByCustomer(Long customerId) {
        throw new UnsupportedOperationException(MESSAGE);
    }
}
