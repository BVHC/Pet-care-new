package com.petcare.module.visit.api;

import java.time.LocalDate;
import java.util.List;

/** Owner: visit (KB, Vaccination) · BE-1. Chỉ tính mũi tiêm tại hệ thống (04 nguyên tắc 9). */
public interface VaccinationQueryApi {

    /** {@code lastAdministeredOn} null = chưa từng tiêm loại này tại hệ thống. */
    record BoardingVaccineGap(Long vaccineTypeId, LocalDate lastAdministeredOn, LocalDate nextDueDate) {}

    /**
     * BR-LT-05: các loại vaccine bắt buộc khi lưu trú (theo loài của thú) mà thú chưa đạt vào {@code onDate}.
     * Rỗng = đạt. Đặt chỗ#1 dùng để cảnh báo, Đặt chỗ#3 dùng để chặn.
     */
    List<BoardingVaccineGap> findBoardingVaccineGaps(Long petId, LocalDate onDate);

    /** Loại vaccine đã có mũi tiêm thì không xóa, chỉ ngừng (BR-SP-07). */
    boolean existsByVaccineType(Long vaccineTypeId);
}
