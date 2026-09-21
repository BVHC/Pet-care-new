package com.petcare.module.pet.service;

import com.petcare.module.pet.dto.CreatePetRequest;
import com.petcare.module.pet.dto.PetResponse;
import com.petcare.module.pet.dto.TransferPetRequest;
import com.petcare.module.pet.dto.UpdatePetRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface PetService {

    PetResponse create(UUID me, CreatePetRequest req);

    PetResponse detail(UUID me, UUID id);

    Page<PetResponse> list(UUID me, Pageable pageable);

    PetResponse update(UUID me, UUID id, UpdatePetRequest req);

    /**
     * RULE-04-10 — Tên method trùng chính xác Command trong docs/01-business-operations.md §4.
     * Chuyển chủ sở hữu chính; PetStatus giữ nguyên ACTIVE (khác hẳn RULE-04-11/TRANSFERRED).
     */
    PetResponse managePetOwnership(UUID me, UUID petId, TransferPetRequest req);
}
