package com.petcare.module.visit.api;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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

    /**
     * Loại vaccine của các mũi tiêm, để care ghép tên loại vào việc gọi điện (care-v1 {@code CareTask.context}, T39,
     * docs/adr/0080). Key là id mũi tiêm; id không có thì vắng mặt; tập rỗng → map rỗng, không chạm DB. Đọc không khóa,
     * tham gia transaction của bên gọi; bên gọi truyền tối đa 100 id và tự phân quyền.
     */
    Map<Long, Long> findVaccineTypeIds(Collection<Long> vaccinationIds);
}
