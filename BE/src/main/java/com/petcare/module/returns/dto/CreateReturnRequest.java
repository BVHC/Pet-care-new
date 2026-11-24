package com.petcare.module.returns.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateReturnRequest(
        @NotNull @Positive Long orderId,
        List<@NotNull @Valid Line> lines,
        Reason reasonCode,
        @Size(max = 300) @Pattern(regexp = "[^\\x00]*") String reasonNote) {

    public enum Reason { DEFECTIVE, WRONG_ITEM, CHANGED_MIND, OTHER }

    public record Line(@NotNull @Positive Long orderLineId, @NotNull @Positive Integer quantity) {}
}
