package com.petcare.module.branch.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.appointment.api.AppointmentQueryApi.BookedSlot;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi.BookedStay;
import com.petcare.module.branch.dto.ScheduleImpact;
import com.petcare.module.branch.dto.ScheduleImpact.AffectedAppointment;
import com.petcare.module.branch.dto.ScheduleImpact.AffectedBoarding;
import com.petcare.module.branch.schedule.BranchSchedule;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;

/**
 * Tính lịch hẹn và đặt chỗ lưu trú BOOKED bị ảnh hưởng khi đổi giờ mở cửa hoặc thêm ngày nghỉ (BR-CN-03, BR-CN-04,
 * BR-LH-10), từ {@code AppointmentQueryApi.findBookedFrom} và {@code BoardingQueryApi.findBookedFrom} (06 §4).
 * Chỉ đọc; chạy trong transaction của use case gọi nó.
 */
@Component
class ScheduleImpactFinder {

    /** Số id tối đa một lời gọi {@code findPets} / {@code findContacts} (06 §6). */
    static final int BATCH = 100;

    /** Danh sách thô theo id, dùng để phát {@code BranchClinicCancellationEvent}. */
    record Impact(List<BookedSlot> appointments, List<BookedStay> stays) {

        /** Chi nhánh {@code DRAFT} chưa nhận đặt lịch, lưu trú nên không có gì bị ảnh hưởng (BR-CN-01). */
        static final Impact NONE = new Impact(List.of(), List.of());

        boolean isEmpty() {
            return appointments.isEmpty() && stays.isEmpty();
        }
    }

    private final AppointmentQueryApi appointments;
    private final BoardingQueryApi boardings;
    private final PetQueryApi pets;
    private final CustomerQueryApi customers;

    ScheduleImpactFinder(AppointmentQueryApi appointments, BoardingQueryApi boardings, PetQueryApi pets,
            CustomerQueryApi customers) {
        this.appointments = appointments;
        this.boardings = boardings;
        this.pets = pets;
        this.customers = customers;
    }

    /** Đổi giờ: ảnh hưởng tính trên toàn bộ lịch mới (các phiên bản sau ngày hiệu lực giữ nguyên hiệu lực). */
    Impact forHoursChange(Long branchId, BranchSchedule newSchedule, LocalDate effectiveFrom) {
        List<BookedSlot> slots = appointments.findBookedFrom(branchId, effectiveFrom).stream()
                .filter(s -> newSchedule.appointmentAffected(s.slotDate(), s.slotStart())).toList();
        List<BookedStay> stays = boardings.findBookedFrom(branchId, effectiveFrom).stream()
                .filter(b -> newSchedule.stayAffected(b.checkInDate(), b.checkOutDate(), effectiveFrom)).toList();
        return new Impact(slots, stays);
    }

    /** Thêm ngày nghỉ: lịch hẹn trong ngày đó, đặt chỗ có ngày nhận hoặc ngày trả trùng ngày đó (BR-LT-04). */
    Impact forHoliday(Long branchId, LocalDate date) {
        List<BookedSlot> slots = appointments.findBookedFrom(branchId, date).stream()
                .filter(s -> s.slotDate().equals(date)).toList();
        List<BookedStay> stays = boardings.findBookedFrom(branchId, date).stream()
                .filter(b -> b.checkInDate().equals(date) || b.checkOutDate().equals(date)).toList();
        return new Impact(slots, stays);
    }

    /**
     * Thêm tên thú, tên và SĐT khách để lễ tân đối chiếu hoặc gọi điện (06 Q4). Gom id của cả lịch hẹn lẫn đặt chỗ,
     * khử trùng, chia lô tối đa {@value #BATCH} id (nghĩa vụ của {@code PetQueryApi.findPets} /
     * {@code CustomerQueryApi.findContacts}): số lời gọi sang module khác không phụ thuộc số dòng bị ảnh hưởng, và bằng
     * 0 khi không có gì bị ảnh hưởng. Id không trả về → "(không rõ)"; id trả về hai lần là vi phạm hợp đồng → 500.
     */
    ScheduleImpact describe(Impact impact) {
        Set<Long> petIds = new LinkedHashSet<>();
        Set<Long> customerIds = new LinkedHashSet<>();
        impact.appointments().forEach(s -> {
            petIds.add(s.petId());
            customerIds.add(s.customerId());
        });
        impact.stays().forEach(b -> {
            petIds.add(b.petId());
            customerIds.add(b.customerId());
        });
        Map<Long, PetSummary> petById = fetch(petIds, pets::findPets, PetSummary::petId, "findPets");
        Map<Long, CustomerContact> customerById = fetch(customerIds, customers::findContacts, CustomerContact::customerId,
                "findContacts");

        List<AffectedAppointment> affectedAppointments = impact.appointments().stream()
                .map(s -> new AffectedAppointment(s.appointmentId(), s.code(), s.slotDate(), s.slotStart(),
                        petName(petById.get(s.petId())), customerName(customerById.get(s.customerId())),
                        customerPhone(customerById.get(s.customerId()))))
                .toList();
        List<AffectedBoarding> affectedStays = impact.stays().stream()
                .map(b -> new AffectedBoarding(b.bookingId(), b.code(), b.checkInDate(), b.checkOutDate(),
                        petName(petById.get(b.petId())), customerName(customerById.get(b.customerId())),
                        customerPhone(customerById.get(b.customerId()))))
                .toList();
        return new ScheduleImpact(affectedAppointments, affectedStays);
    }

    private static <T> Map<Long, T> fetch(Collection<Long> ids, Function<Collection<Long>, List<T>> batchCall,
            Function<T, Long> idOf, String method) {
        Map<Long, T> byId = new HashMap<>();
        List<Long> all = List.copyOf(ids);
        for (int from = 0; from < all.size(); from += BATCH) {
            for (T row : batchCall.apply(all.subList(from, Math.min(from + BATCH, all.size())))) {
                if (byId.put(idOf.apply(row), row) != null) {
                    throw new IllegalStateException(method + " trả một id hai lần: " + idOf.apply(row));
                }
            }
        }
        return byId;
    }

    private static String petName(PetSummary pet) {
        return pet == null ? "(không rõ)" : pet.name();
    }

    private static String customerName(CustomerContact contact) {
        return contact == null ? "(không rõ)" : contact.fullName();
    }

    private static String customerPhone(CustomerContact contact) {
        return contact == null ? null : contact.phone();
    }
}
