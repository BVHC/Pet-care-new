package com.petcare.module.customer.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.entity.Pet;
import com.petcare.module.customer.repository.PetRepository;
import com.petcare.module.customer.repository.WeightRecordRepository;

/**
 * {@link PetQueryApi} thật (thay {@code PetQueryApiPlaceholder}, docs/adr/0041). {@code findPet} đọc không khóa;
 * {@code findPetForShare} khóa chia sẻ dòng thú để bên chèn dòng trỏ tới thú xếp hàng sau đánh dấu đã mất / chuyển chủ.
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

    private PetSummary summarize(Pet pet) {
        return new PetSummary(pet.getId(), pet.getCustomerId(), pet.getName(), pet.getSpecies(), pet.getBirthDate(),
                pet.getDeceasedOn(), weights.findCurrentWeight(pet.getId()).orElse(null));
    }
}
