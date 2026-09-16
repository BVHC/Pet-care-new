package com.petcare.module.pet.dto;

import com.petcare.platform.enums.PetStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PetResponse(
        UUID id,
        UUID ownerId,
        String name,
        String species,
        String breed,
        String gender,
        LocalDate dateOfBirth,
        BigDecimal weightKg,
        String microchipNumber,
        String avatarUrl,
        PetStatus status) {}
