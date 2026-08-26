package com.petcare.modules.pets.service.impl;

import com.petcare.common.exception.ResourceNotFoundException;
import com.petcare.modules.pets.dto.request.CreatePetRequest;
import com.petcare.modules.pets.dto.request.UpdatePetRequest;
import com.petcare.modules.pets.dto.response.PetResponse;
import com.petcare.modules.pets.entity.Pet;
import com.petcare.modules.pets.repository.PetRepository;
import com.petcare.modules.pets.service.PetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PetServiceImpl implements PetService {

    private final PetRepository petRepository;

    @Override
    public PetResponse createPet(Long ownerId, CreatePetRequest request) {
        log.info("Creating pet for owner: {}", ownerId);

        Pet pet = Pet.builder()
                .ownerId(ownerId)
                .name(request.getName())
                .species(request.getSpecies().toUpperCase())
                .breed(request.getBreed())
                .birthDate(request.getBirthDate())
                .weight(request.getWeight())
                .imageUrl(request.getImageUrl())
                .notes(request.getNotes())
                .build();

        Pet savedPet = petRepository.save(pet);
        log.info("Created pet with id: {}", savedPet.getId());

        return mapToResponse(savedPet);
    }

    @Override
    @Transactional(readOnly = true)
    public PetResponse getPetById(Long petId) {
        Pet pet = findPetById(petId);
        return mapToResponse(pet);
    }

    @Override
    @Transactional(readOnly = true)
    public PetResponse getPetByIdForOwner(Long petId, Long ownerId) {
        Pet pet = petRepository.findByIdAndOwnerId(petId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Pet", "id", petId));
        return mapToResponse(pet);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PetResponse> getPetsByOwner(Long ownerId, Pageable pageable) {
        return petRepository.findByOwnerId(ownerId, pageable)
                .map(this::mapToResponse);
    }

    @Override
    public PetResponse updatePet(Long petId, Long ownerId, UpdatePetRequest request) {
        Pet pet = petRepository.findByIdAndOwnerId(petId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Pet", "id", petId));

        if (request.getName() != null) {
            pet.setName(request.getName());
        }
        if (request.getBreed() != null) {
            pet.setBreed(request.getBreed());
        }
        if (request.getBirthDate() != null) {
            pet.setBirthDate(request.getBirthDate());
        }
        if (request.getWeight() != null) {
            pet.setWeight(request.getWeight());
        }
        if (request.getImageUrl() != null) {
            pet.setImageUrl(request.getImageUrl());
        }
        if (request.getNotes() != null) {
            pet.setNotes(request.getNotes());
        }

        Pet updatedPet = petRepository.save(pet);
        return mapToResponse(updatedPet);
    }

    @Override
    public void deletePet(Long petId, Long ownerId) {
        Pet pet = petRepository.findByIdAndOwnerId(petId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Pet", "id", petId));
        petRepository.delete(pet);
        log.info("Deleted pet with id: {}", petId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PetResponse> searchPets(String keyword, Pageable pageable) {
        return petRepository.searchByName(keyword, pageable)
                .map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PetResponse> getAllPetsBySpecies(String species) {
        return petRepository.findBySpecies(species.toUpperCase())
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public long countPetsByOwner(Long ownerId) {
        return petRepository.countByOwnerId(ownerId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOwnerOfPet(Long petId, Long ownerId) {
        return petRepository.existsByIdAndOwnerId(petId, ownerId);
    }

    // Helper methods
    private Pet findPetById(Long id) {
        return petRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pet", "id", id));
    }

    private PetResponse mapToResponse(Pet pet) {
        return PetResponse.builder()
                .id(pet.getId())
                .ownerId(pet.getOwnerId())
                .name(pet.getName())
                .species(pet.getSpecies())
                .speciesDisplayName(getSpeciesDisplayName(pet.getSpecies()))
                .breed(pet.getBreed())
                .birthDate(pet.getBirthDate())
                .weight(pet.getWeight())
                .imageUrl(pet.getImageUrl())
                .notes(pet.getNotes())
                .createdAt(pet.getCreatedAt())
                .updatedAt(pet.getUpdatedAt())
                .build();
    }

    private String getSpeciesDisplayName(String species) {
        if (species == null) return "Khác";
        return switch (species.toUpperCase()) {
            case "CAT" -> "Mèo";
            case "DOG" -> "Chó";
            case "BIRD" -> "Chim";
            case "RABBIT" -> "Thỏ";
            default -> "Khác";
        };
    }
}
