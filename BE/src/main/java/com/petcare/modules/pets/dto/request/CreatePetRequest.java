package com.petcare.modules.pets.dto.request;

import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreatePetRequest {

    @NotBlank(message = "Pet name is required")
    @Size(max = 100, message = "Name must be less than 100 characters")
    private String name;

    @NotBlank(message = "Species is required")
    @Pattern(regexp = "^(CAT|DOG|BIRD|RABBIT|OTHER)$", message = "Invalid species")
    private String species;

    @Size(max = 100, message = "Breed must be less than 100 characters")
    private String breed;

    @Past(message = "Birth date must be in the past")
    private LocalDate birthDate;

    @DecimalMin(value = "0.1", message = "Weight must be greater than 0")
    @DecimalMax(value = "999.99", message = "Weight must be less than 1000")
    private BigDecimal weight;

    @Size(max = 500, message = "Image URL must be less than 500 characters")
    private String imageUrl;

    @Size(max = 1000, message = "Notes must be less than 1000 characters")
    private String notes;
}
