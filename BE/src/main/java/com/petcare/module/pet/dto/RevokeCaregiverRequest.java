package com.petcare.module.pet.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Định danh bằng email vì lời mời đang treo có thể chưa có caregiverUserId (spec D-05). */
public record RevokeCaregiverRequest(
        @NotBlank @Email @Size(max = 100) String caregiverEmail
) {}
