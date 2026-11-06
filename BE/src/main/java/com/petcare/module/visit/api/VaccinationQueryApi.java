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

    // ------------------------------------------------------------------------------------------------------------
    // Tầng 3 (T55b, docs/06 §6b, docs/adr/0113): placeholder dạng default — ném UnsupportedOperationException tới khi
    // VaccinationQueryService ghi đè; khi đó xóa default.
    // ------------------------------------------------------------------------------------------------------------

    /** Một mũi tiêm ghi nhận tại hệ thống; {@code superseded} = đã bị mũi mới thay thế ({@code superseded_at} khác NULL). */
    record DoseRecord(Long vaccinationId, Long vaccineTypeId, int doseNumber, LocalDate administeredOn,
                      LocalDate nextDueDate, boolean superseded) {}

    /**
     * I-2 (docs/07 §11, UC91): lịch sử mũi tiêm của thú, mới nhất trước; chỉ mũi ghi nhận tại hệ thống (04 nguyên tắc 8),
     * kể cả mũi đã bị thay thế. Đọc không khóa. Bản thật trước 30/11 (T77).
     */
    default List<DoseRecord> findDoseHistory(Long petId) {
        throw new UnsupportedOperationException("VaccinationQueryApi.findDoseHistory chưa cài (placeholder T55b, "
                + "docs/06 §6b); bản thật ở T77");
    }

    /** Một mũi tiêm dùng lô kho: thú, lượt khám, ngày tiêm (BR-KO-13). */
    record LotVaccination(Long vaccinationId, Long petId, Long visitId, LocalDate administeredOn) {}

    /**
     * BR-KO-13 (docs/08 §E): các mũi tiêm dùng lô {@code stockLotId}, theo ngày tiêm. Tên thú / chủ do bên gọi tra bằng
     * {@code PetQueryApi}. Đọc không khóa. Bản thật trước 3/12 (T74).
     */
    default List<LotVaccination> findByStockLot(Long stockLotId) {
        throw new UnsupportedOperationException("VaccinationQueryApi.findByStockLot chưa cài (placeholder T55b, "
                + "docs/06 §6b); bản thật ở T74");
    }
}
