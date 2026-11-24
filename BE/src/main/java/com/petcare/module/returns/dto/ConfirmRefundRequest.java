package com.petcare.module.returns.dto;

import jakarta.validation.constraints.Size;

public record ConfirmRefundRequest(@Size(max = 300) String note) {}
