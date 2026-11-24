package com.petcare.module.returns.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RejectReturnRequest(@Size(max = 300) @Pattern(regexp = "[^\\x00]*") String reason) {}
