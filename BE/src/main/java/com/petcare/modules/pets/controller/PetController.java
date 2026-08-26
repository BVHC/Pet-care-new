package com.petcare.modules.pets.controller;

import com.petcare.common.model.ApiResponse;
import com.petcare.common.model.PageResponse;
import com.petcare.modules.pets.dto.request.CreatePetRequest;
import com.petcare.modules.pets.dto.request.UpdatePetRequest;
import com.petcare.modules.pets.dto.response.PetResponse;
import com.petcare.modules.pets.service.PetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pets")
@RequiredArgsConstructor
public class PetController {

    private final PetService petService;

    @PostMapping
    @PreAuthorize("hasAnyRole('CUSTOMER', 'RECEPTIONIST')")
    public ResponseEntity<ApiResponse<PetResponse>> createPet(
            @Valid @RequestBody CreatePetRequest request) {

        Long ownerId = getCurrentUserId();
        PetResponse pet = petService.createPet(ownerId, request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.created(pet, "Pet added successfully"));
    }

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<PageResponse<PetResponse>>> getMyPets(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Long ownerId = getCurrentUserId();
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<PetResponse> pets = petService.getPetsByOwner(ownerId, pageable);

        return ResponseEntity.ok(ApiResponse.ok(PageResponse.of(pets)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'RECEPTIONIST', 'VETERINARIAN', 'STORE_MANAGER')")
    public ResponseEntity<ApiResponse<PetResponse>> getPetById(@PathVariable Long id) {
        Long currentUserId = getCurrentUserId();
        String role = getCurrentUserRole();

        PetResponse pet;
        if (role.contains("CUSTOMER")) {
            pet = petService.getPetByIdForOwner(id, currentUserId);
        } else {
            pet = petService.getPetById(id);
        }

        return ResponseEntity.ok(ApiResponse.ok(pet));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'RECEPTIONIST')")
    public ResponseEntity<ApiResponse<PetResponse>> updatePet(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePetRequest request) {

        Long ownerId = getCurrentUserId();
        PetResponse pet = petService.updatePet(id, ownerId, request);

        return ResponseEntity.ok(ApiResponse.ok(pet, "Pet updated successfully"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Void>> deletePet(@PathVariable Long id) {
        Long ownerId = getCurrentUserId();
        petService.deletePet(id, ownerId);

        return ResponseEntity.ok(ApiResponse.ok(null, "Pet deleted successfully"));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('VETERINARIAN', 'STORE_MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<PetResponse>>> searchPets(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<PetResponse> pets = petService.searchPets(keyword, pageable);

        return ResponseEntity.ok(ApiResponse.ok(PageResponse.of(pets)));
    }

    // Placeholder methods - implement with SecurityContext
    private Long getCurrentUserId() {
        return 1L;
    }

    private String getCurrentUserRole() {
        return "CUSTOMER";
    }
}
