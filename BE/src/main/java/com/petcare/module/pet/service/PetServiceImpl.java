package com.petcare.module.pet.service;

import com.petcare.module.pet.dto.CreatePetRequest;
import com.petcare.module.pet.dto.PetResponse;
import com.petcare.module.pet.dto.UpdatePetRequest;
import com.petcare.module.pet.entity.Pet;
import com.petcare.module.pet.mapper.PetMapper;
import com.petcare.module.pet.repository.PetRepository;
import com.petcare.module.iam.service.UserProvisioningService;
import com.petcare.platform.enums.PetStatus;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.outbox.OutboxEvent;
import com.petcare.platform.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PetServiceImpl implements PetService {

    private final PetRepository pets;
    private final PetMapper mapper;
    private final UserProvisioningService users;
    private final OutboxEventRepository outbox;

    @Override
    @Transactional
    public PetResponse create(UUID me, CreatePetRequest req) {
        users.findById(me); // RULE-04-01: owner phải tồn tại; 404 nếu không
        Pet pet = new Pet(me, req.name(), req.species());
        pet.setBreed(req.breed());
        pet.setGender(req.gender() == null ? "UNKNOWN" : req.gender());
        pet.setDateOfBirth(req.dateOfBirth());
        pet.setWeightKg(req.weightKg());
        pet.setMicrochipNumber(req.microchipNumber());
        pet.setAvatarUrl(req.avatarUrl());
        try {
            pet = pets.save(pet);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessRuleViolationException("RULE-04-01", "Chủ sở hữu không hợp lệ");
        }
        OutboxEvent e = new OutboxEvent();
        e.setAggregateType("Pet");
        e.setAggregateId(pet.getId().toString());
        e.setEventType("PetAdded");
        e.setPayload("{\"petId\":\"" + pet.getId() + "\",\"ownerId\":\"" + me + "\"}");
        outbox.save(e);
        return mapper.toResponse(pet);
    }

    @Override
    @Transactional(readOnly = true)
    public PetResponse detail(UUID me, UUID id) {
        Pet pet = pets.findById(id).orElseThrow(() -> new ResourceNotFoundException("Pet", id));
        if (!pet.getOwnerId().equals(me)) {
            throw new AccessDeniedScopeException("PET", "OWNER");
        }
        return mapper.toResponse(pet);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PetResponse> list(UUID me, Pageable pageable) {
        return pets.findByOwnerId(me, pageable).map(mapper::toResponse);
    }

    @Override
    @Transactional
    public PetResponse update(UUID me, UUID id, UpdatePetRequest req) {
        Pet pet = pets.findById(id).orElseThrow(() -> new ResourceNotFoundException("Pet", id));
        if (!pet.getOwnerId().equals(me)) {
            throw new AccessDeniedScopeException("PET", "OWNER");
        }
        if (pet.getStatus() != PetStatus.ACTIVE) {
            throw new BusinessRuleViolationException("RULE-04-11", "Pet không ở trạng thái cho phép cập nhật");
        }
        if (req.name() != null) {
            pet.setName(req.name());
        }
        if (req.species() != null) {
            pet.setSpecies(req.species());
        }
        if (req.breed() != null) {
            pet.setBreed(req.breed());
        }
        if (req.gender() != null) {
            pet.setGender(req.gender());
        }
        if (req.dateOfBirth() != null) {
            pet.setDateOfBirth(req.dateOfBirth());
        }
        if (req.weightKg() != null) {
            pet.setWeightKg(req.weightKg());
        }
        if (req.microchipNumber() != null) {
            pet.setMicrochipNumber(req.microchipNumber());
        }
        if (req.avatarUrl() != null) {
            pet.setAvatarUrl(req.avatarUrl());
        }
        try {
            pet = pets.save(pet);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("Pet", id);
        }
        return mapper.toResponse(pet);
    }
}
