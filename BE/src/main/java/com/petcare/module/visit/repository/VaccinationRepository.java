package com.petcare.module.visit.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.visit.entity.Vaccination;

/**
 * Bảng {@code vaccinations}. Chỉ {@code SELECT} thường, không khóa: bên ghi (T28 phần 2) khóa {@code pets FOR SHARE}
 * rồi {@code visits} trước (docs/adr/0072). {@code vaccinations.visit_id} và {@code vaccine_type_id} chưa có index (nợ
 * D043): mọi truy vấn theo thú đi tiền tố {@code pet_id} của {@code ix_vaccinations_pet_type_administered}.
 */
public interface VaccinationRepository extends JpaRepository<Vaccination, Long> {

    /** Mũi tiêm của một lượt theo thứ tự ghi. Lọc cả {@code pet_id} để đi index (cùng lý do {@code hasVaccinationInVisit}). */
    List<Vaccination> findByPetIdAndVisitIdOrderByIdAsc(Long petId, Long visitId);

    /**
     * Mũi gần nhất của thú cùng một loại (visit-v1 #18, docs/adr/0073): ngày tiêm mới nhất, cùng ngày thì mũi ghi sau —
     * cùng cách chọn với {@link #findLatestDoses}. Đi {@code ix_vaccinations_pet_type_administered}.
     */
    java.util.Optional<Vaccination> findFirstByPetIdAndVaccineTypeIdOrderByAdministeredOnDescIdDesc(Long petId,
            Long vaccineTypeId);

    /** Loại vaccine đã có mũi tiêm thì không xóa (BR-SP-07). Chưa có index theo {@code vaccine_type_id} (nợ D043). */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM vaccinations WHERE vaccine_type_id = :vaccineTypeId)",
            nativeQuery = true)
    boolean existsByVaccineTypeId(@Param("vaccineTypeId") Long vaccineTypeId);

    /**
     * Mũi gần nhất của thú theo từng loại trong {@code vaccineTypeIds} (BR-LT-05): ngày tiêm mới nhất, cùng ngày thì
     * mũi ghi sau. Đi {@code ix_vaccinations_pet_type_administered}. Bên gọi không truyền tập rỗng.
     */
    @Query(value = """
            SELECT DISTINCT ON (vaccine_type_id) vaccine_type_id AS vaccineTypeId, administered_on AS administeredOn,
                   next_due_date AS nextDueDate
            FROM vaccinations
            WHERE pet_id = :petId AND vaccine_type_id IN (:vaccineTypeIds)
            ORDER BY vaccine_type_id, administered_on DESC, id DESC
            """, nativeQuery = true)
    List<LatestDose> findLatestDoses(@Param("petId") Long petId,
            @Param("vaccineTypeIds") Collection<Long> vaccineTypeIds);

    /** Projection của {@link #findLatestDoses}. */
    interface LatestDose {

        Long getVaccineTypeId();

        LocalDate getAdministeredOn();

        LocalDate getNextDueDate();
    }
}
