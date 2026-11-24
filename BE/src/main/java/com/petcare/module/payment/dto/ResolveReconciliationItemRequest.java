package com.petcare.module.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "ResolveItemRequest")
public record ResolveReconciliationItemRequest(@NotBlank @Size(max = 300) String note) {}
