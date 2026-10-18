package com.petcare.module.appointment.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.appointment.repository.AppointmentRepository;

/**
 * Cài đặt {@link AppointmentQueryApi} (docs/adr/0046): chỉ đọc, mỗi method đúng một câu SQL bất kể số dòng. Class
 * {@code readOnly} <b>tham gia</b> transaction ghi của bên gọi (branch dưới khóa {@code branches}, identity dưới khóa
 * tài khoản) và không đổi flush / readOnly của nó. Không {@code BranchScope}, không kiểm role: API nội bộ, bên gọi đã
 * phân quyền; quyết định nghiệp vụ (BR-LH-10, BR-KH-06, BR-TK-19, BR-QT-11) là của bên gọi.
 */
@Service
@Transactional(readOnly = true)
public class AppointmentLookupService implements AppointmentQueryApi {

    private final AppointmentRepository appointments;

    public AppointmentLookupService(AppointmentRepository appointments) {
        this.appointments = appointments;
    }

    @Override
    public List<BookedSlot> findBookedFrom(Long branchId, LocalDate fromDate) {
        Objects.requireNonNull(branchId, "branchId");
        Objects.requireNonNull(fromDate, "fromDate");
        return appointments.findBookedFrom(branchId, fromDate);
    }

    @Override
    public boolean existsByPet(Long petId) {
        Objects.requireNonNull(petId, "petId");
        return appointments.existsByPetId(petId);
    }

    @Override
    public boolean existsByCustomer(Long customerId) {
        Objects.requireNonNull(customerId, "customerId");
        return appointments.existsByCustomerId(customerId);
    }

    @Override
    public List<BookedSlot> findBookedByCustomer(Long customerId) {
        Objects.requireNonNull(customerId, "customerId");
        return appointments.findBookedByCustomerId(customerId);
    }
}
