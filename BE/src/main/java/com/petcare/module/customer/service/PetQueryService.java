package com.petcare.module.customer.service;

import java.math.BigDecimal;
import java.util.Collection;
<<<<<<< HEAD
import java.util.List;
import java.util.Map;
import java.util.Optional;
=======
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
>>>>>>> e18c4ed (feat: triển khai hủy do phòng khám)
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.entity.Pet;
import com.petcare.module.customer.repository.PetRepository;
import com.petcare.module.customer.repository.WeightRecordRepository;
<<<<<<< HEAD
import com.petcare.module.customer.repository.WeightRecordRepository.CurrentWeightRow;
=======
import com.petcare.module.customer.repository.WeightRecordRepository.CurrentWeight;
>>>>>>> e18c4ed (feat: triển khai hủy do phòng khám)

/**
 * {@link PetQueryApi} thật (thay {@code PetQueryApiPlaceholder}, docs/adr/0041). {@code findPet} đọc không khóa;
 * {@code findPetForShare} khóa chia sẻ dòng thú để bên chèn dòng trỏ tới thú xếp hàng sau đánh dấu đã mất / chuyển chủ;
 * {@code findPets} theo lô cho appointment / branch (06 §6; BE-1 bổ sung khi gộp nhánh, 2026-10-18).
 */
@Service
public class PetQueryService implements PetQueryApi {

    private final PetRepository pets;
    private final WeightRecordRepository weights;

    public PetQueryService(PetRepository pets, WeightRecordRepository weights) {
        this.pets = pets;
        this.weights = weights;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PetSummary> findPet(Long petId) {
        return pets.findById(petId).map(this::summarize);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PetSummary> findPetForShare(Long petId) {
        return pets.findByIdForShare(petId).map(this::summarize);
    }

<<<<<<< HEAD
    /** Hai câu cho cả lô (thú + cân nặng mới nhất), không N+1; id không có thì bỏ qua (06 §6). */
    @Override
    @Transactional(readOnly = true)
    public List<PetSummary> findPets(Collection<Long> petIds) {
        if (petIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = petIds.stream().distinct().toList();
        Map<Long, BigDecimal> current = weights.findCurrentWeights(ids).stream()
                .collect(Collectors.toMap(CurrentWeightRow::getPetId, CurrentWeightRow::getWeightKg));
        return pets.findAllById(ids).stream()
                .map(pet -> summarize(pet, current.get(pet.getId())))
=======
    /**
     * Hai câu SQL bất kể số id (thú + cân nặng hiện tại theo lô), không nạp entity. Id không có thì bỏ qua, mỗi id tối
     * đa một lần (trùng trong đầu vào bị khử), tập rỗng → rỗng không chạm DB (nghĩa vụ của {@link PetQueryApi#findPets}).
     */
    @Override
    @Transactional(readOnly = true)
    public List<PetSummary> findPets(Collection<Long> petIds) {
        Set<Long> ids = new LinkedHashSet<>(petIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, BigDecimal> current = weights.findCurrentWeights(ids).stream()
                .collect(Collectors.toMap(CurrentWeight::getPetId, CurrentWeight::getWeightKg));
        return pets.findRowsByIds(ids).stream()
                .map(p -> new PetSummary(p.id(), p.customerId(), p.name(), p.species(), p.birthDate(),
                        p.deceasedOn(), current.get(p.id())))
>>>>>>> e18c4ed (feat: triển khai hủy do phòng khám)
                .toList();
    }

    private PetSummary summarize(Pet pet) {
        return summarize(pet, weights.findCurrentWeight(pet.getId()).orElse(null));
    }

    private static PetSummary summarize(Pet pet, BigDecimal currentWeightKg) {
        return new PetSummary(pet.getId(), pet.getCustomerId(), pet.getName(), pet.getSpecies(), pet.getBirthDate(),
                pet.getDeceasedOn(), currentWeightKg);
    }
}
