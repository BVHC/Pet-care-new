package com.petcare.module.pet.dto;

import com.petcare.platform.enums.PetStatus;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdatePetRequest(
        @Size(max = 100) String name,
        String species,
        @Size(max = 100) String breed,
        String gender,
        LocalDate dateOfBirth,
        BigDecimal weightKg,
        @Size(max = 50) String microchipNumber,
        @Size(max = 255) String avatarUrl,
        /** RULE-04-11 — chỉ chấp nhận thực tế ACTIVE->DECEASED/TRANSFERRED (guard ở PetServiceImpl.update()). */
        PetStatus status) {}
