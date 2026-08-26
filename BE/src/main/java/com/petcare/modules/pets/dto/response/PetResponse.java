package com.petcare.modules.pets.dto.response;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PetResponse {
    private Long id;
    private Long ownerId;
    private String ownerName;
    private String name;
    private String species;
    private String speciesDisplayName;
    private String breed;
    private LocalDate birthDate;
    private Integer ageYears;
    private Integer ageMonths;
    private BigDecimal weight;
    private String imageUrl;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
