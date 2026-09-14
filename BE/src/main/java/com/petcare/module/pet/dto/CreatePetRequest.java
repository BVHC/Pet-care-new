package com.petcare.module.pet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreatePetRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank String species,
        @Size(max = 100) String breed,
        String gender,
        LocalDate dateOfBirth,
        BigDecimal weightKg,
        @Size(max = 50) String microchipNumber,
        @Size(max = 255) String avatarUrl) {}
