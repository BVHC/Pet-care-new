package com.petcare.module.returns.dto;

import jakarta.validation.constraints.Size;

public record RejectReturnRequest(@Size(max = 300) String reason) {}
