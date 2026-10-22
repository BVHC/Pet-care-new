package com.petcare.module.customer.api;

import java.math.BigDecimal;
import java.time.Instant;

/** Bản ghi cân vừa ghi bởi {@link PetApi#recordWeight}: kg đã chuẩn hóa 2 chữ số, {@code measuredAt} đã cắt về µs. */
public record RecordedWeight(Long weightRecordId, BigDecimal weightKg, Instant measuredAt) {}
