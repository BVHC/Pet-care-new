package com.petcare.module.ai.service;

import java.math.BigDecimal;

import com.petcare.module.customer.api.Species;

/** Đặc điểm của thú đang xét gợi ý; {@code null} = chưa có dữ liệu nên tiêu chí tương ứng không đánh giá (BR-AI-20). */
record PetFacts(Species species, String breed, Long ageWeeks, BigDecimal weightKg) {
}
