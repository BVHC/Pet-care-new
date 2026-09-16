package com.petcare.module.pet.service;

import com.petcare.module.pet.dto.CreatePetRequest;
import com.petcare.module.pet.dto.PetResponse;
import com.petcare.module.pet.dto.UpdatePetRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface PetService {

    PetResponse create(UUID me, CreatePetRequest req);

    PetResponse detail(UUID me, UUID id);

    Page<PetResponse> list(UUID me, Pageable pageable);

    PetResponse update(UUID me, UUID id, UpdatePetRequest req);
}
