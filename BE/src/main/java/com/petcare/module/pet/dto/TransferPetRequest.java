package com.petcare.module.pet.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** RULE-04-10 — chủ mới xác định bằng userId trực tiếp, không cần xác nhận. */
public record TransferPetRequest(@NotNull UUID newOwnerId) {}
