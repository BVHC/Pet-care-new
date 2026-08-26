package com.petcare.modules.pets.service;

import com.petcare.modules.pets.dto.request.CreatePetRequest;
import com.petcare.modules.pets.dto.request.UpdatePetRequest;
import com.petcare.modules.pets.dto.response.PetResponse;
import com.petcare.modules.pets.entity.Pet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface PetService {

    // CRUD operations
    PetResponse createPet(Long ownerId, CreatePetRequest request);

    PetResponse getPetById(Long petId);

    PetResponse getPetByIdForOwner(Long petId, Long ownerId);

    Page<PetResponse> getPetsByOwner(Long ownerId, Pageable pageable);

    PetResponse updatePet(Long petId, Long ownerId, UpdatePetRequest request);

    void deletePet(Long petId, Long ownerId);

    // Search
    Page<PetResponse> searchPets(String keyword, Pageable pageable);

    // Admin operations
    List<PetResponse> getAllPetsBySpecies(String species);

    long countPetsByOwner(Long ownerId);

    // Validation
    boolean isOwnerOfPet(Long petId, Long ownerId);
}
