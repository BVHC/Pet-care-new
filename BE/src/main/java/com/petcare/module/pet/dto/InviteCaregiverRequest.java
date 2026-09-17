package com.petcare.module.pet.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** validUntil tùy chọn: NULL = ủy quyền chạy tới khi bị thu hồi (spec D-03). */
public record InviteCaregiverRequest(
        @NotBlank @Email @Size(max = 100) String caregiverEmail,
        @Size(max = 20) String caregiverPhone,
        @Future LocalDateTime validUntil
) {}
