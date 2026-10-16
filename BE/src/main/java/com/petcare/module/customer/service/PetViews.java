package com.petcare.module.customer.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.customer.dto.PetResponse;
import com.petcare.module.customer.entity.Pet;
import com.petcare.module.customer.mapper.PetMapper;
import com.petcare.module.customer.repository.WeightRecordRepository;
import com.petcare.module.visit.api.VisitQueryApi;

/**
 * Dựng {@link PetResponse}: ba trường suy ra — {@code speciesLocked} (BR-KH-03), {@code currentWeightKg} (BR-KH-04),
 * {@code deletable} (BR-KH-06) — đọc qua {@code api} của module khác. Chỉ gọi bên trong transaction của service. Danh sách
 * N thú gọi 4N lần đọc (visit, appointment, boarding) — chấp nhận ở quy mô một khách có vài thú (docs/adr/0041).
 * <p>
 * {@code deletable} là {@code false} với thú đã mất: hồ sơ chỉ đọc (BR-KH-05), xóa bị từ chối.
 */
@Component
class PetViews {

    private final VisitQueryApi visits;
    private final AppointmentQueryApi appointments;
    private final BoardingQueryApi boardings;
    private final WeightRecordRepository weights;
    private final PetMapper mapper;

    PetViews(VisitQueryApi visits, AppointmentQueryApi appointments, BoardingQueryApi boardings,
            WeightRecordRepository weights, PetMapper mapper) {
        this.visits = visits;
        this.appointments = appointments;
        this.boardings = boardings;
        this.weights = weights;
        this.mapper = mapper;
    }

    PetResponse view(Pet pet) {
        return mapper.toPetResponse(pet, visits.hasMedicalHistory(pet.getId()),
                weights.findCurrentWeight(pet.getId()).orElse(null), isDeletable(pet));
    }

    List<PetResponse> views(List<Pet> pets) {
        return pets.stream().map(this::view).toList();
    }

    /** BR-KH-06: chưa phát sinh lịch hẹn, đặt chỗ, Visit nào (Order luôn kèm Visit hoặc đặt chỗ) và chưa mất. */
    boolean isDeletable(Pet pet) {
        return !pet.isDeceased() && !hasTransactions(pet.getId());
    }

    boolean hasTransactions(Long petId) {
        return appointments.existsByPet(petId) || boardings.existsByPet(petId) || visits.existsByPet(petId);
    }
}
