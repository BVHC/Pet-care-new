package com.petcare.module.appointment.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import com.petcare.module.appointment.api.AppointmentQueryApi;

/**
 * Giữ chỗ cho {@link AppointmentQueryApi} tới khi appointment (BE-1) cài thật. Theo 06-module-contracts §1: mọi
 * method ném lỗi, không trả giá trị "an toàn" (trả rỗng sẽ cho thu hẹp giờ mở cửa qua mặt BR-CN-04). Hệ quả: đặt
 * giờ mở cửa, thêm ngày nghỉ và hai endpoint xem trước ảnh hưởng của branch trả 500.
 * BE-1 xóa lớp này (cùng {@code AppointmentQueryApiPlaceholderTest}) khi thêm implementation thật.
 */
@Service
public class AppointmentQueryApiPlaceholder implements AppointmentQueryApi {

    static final String MESSAGE = "AppointmentQueryApi chưa được cài (module appointment, BE-1)";

    @Override
    public List<BookedSlot> findBookedFrom(Long branchId, LocalDate fromDate) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public boolean existsByPet(Long petId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public boolean existsByCustomer(Long customerId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public List<BookedSlot> findBookedByCustomer(Long customerId) {
        throw new UnsupportedOperationException(MESSAGE);
    }
}
