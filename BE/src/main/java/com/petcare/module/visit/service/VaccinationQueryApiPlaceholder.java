package com.petcare.module.visit.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import com.petcare.module.visit.api.VaccinationQueryApi;

/**
 * Giữ chỗ cho {@link VaccinationQueryApi} tới khi module visit (BE-1) cài thật. Theo 06-module-contracts §1: mọi
 * method ném lỗi, không trả giá trị "an toàn". Hệ quả: xóa loại vaccine (BR-SP-07) trả 500 khi loại đó chưa có
 * phác đồ hay sản phẩm. BE-1 xóa lớp này (cùng {@code VaccinationQueryApiPlaceholderTest}) khi thêm implementation thật.
 */
@Service
public class VaccinationQueryApiPlaceholder implements VaccinationQueryApi {

    static final String MESSAGE = "VaccinationQueryApi chưa được cài (module visit, BE-1)";

    @Override
    public List<BoardingVaccineGap> findBoardingVaccineGaps(Long petId, LocalDate onDate) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public boolean existsByVaccineType(Long vaccineTypeId) {
        throw new UnsupportedOperationException(MESSAGE);
    }
}
