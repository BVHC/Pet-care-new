package com.petcare.module.visit.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.visit.entity.Vaccination;

/**
 * Bảng {@code vaccinations}. Truy vấn đọc không khóa: bên ghi (#19, docs/adr/0074) khóa {@code pets FOR SHARE}, advisory
 * 1004 rồi {@code visits} trước (docs/adr/0072). {@code vaccinations.visit_id} và {@code vaccine_type_id} chưa có index (nợ
 * D043): mọi truy vấn theo thú đi tiền tố {@code pet_id} của {@code ix_vaccinations_pet_type_administered}.
 */
public interface VaccinationRepository extends JpaRepository<Vaccination, Long> {

    /** Mũi tiêm của một lượt theo thứ tự ghi. Lọc cả {@code pet_id} để đi index (cùng lý do {@code hasVaccinationInVisit}). */
    List<Vaccination> findByPetIdAndVisitIdOrderByIdAsc(Long petId, Long visitId);

    /**
     * Mũi gần nhất của thú cùng một loại (visit-v1 #18, docs/adr/0073): ngày tiêm mới nhất, cùng ngày thì mũi ghi sau —
     * cùng cách chọn với {@link #findLatestDoses}. Đi {@code ix_vaccinations_pet_type_administered}.
     */
    Optional<Vaccination> findFirstByPetIdAndVaccineTypeIdOrderByAdministeredOnDescIdDesc(Long petId,
            Long vaccineTypeId);

    /**
     * Advisory lock theo transaction (namespace 1004, theo thú; docs/adr/0074): tuần tự hóa ghi / xóa mũi tiêm của cùng
     * một thú ở các lượt khác nhau, để {@code superseded_at} luôn đúng dưới READ COMMITTED. Lấy sau {@code pets FOR
     * SHARE}, trước {@code visits}. {@code MANDATORY}: ngoài transaction khóa nhả ngay mà không báo lỗi.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:namespace, :key)", nativeQuery = true)
    Integer acquirePetDoseLock(@Param("namespace") int namespace, @Param("key") int key);

    /**
     * Mũi cùng thú, cùng loại vaccine, chưa bị thay thế, trừ mũi vừa ghi ({@code excludeId}); đi
     * {@code ix_vaccinations_pet_type_administered}. Chỉ id: không nạp entity (bulk update không đồng bộ persistence
     * context).
     */
    @Query(value = """
            SELECT id FROM vaccinations
            WHERE pet_id = :petId AND vaccine_type_id = :vaccineTypeId AND superseded_at IS NULL AND id <> :excludeId
            ORDER BY id
            """, nativeQuery = true)
    List<Long> findUnsupersededIds(@Param("petId") Long petId, @Param("vaccineTypeId") Long vaccineTypeId,
            @Param("excludeId") Long excludeId);

    /** BR-TB-03: đặt {@code superseded_at}; tự đặt {@code updated_at} từ {@code Clock} (docs/adr/0015). */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            UPDATE vaccinations SET superseded_at = :now, updated_at = :now
            WHERE id IN (:ids) AND superseded_at IS NULL
            """, nativeQuery = true)
    int markSuperseded(@Param("ids") Collection<Long> ids, @Param("now") Instant now);

    /**
     * Khóa dòng mũi tiêm của lượt để xóa (visit-v1 #20, docs/adr/0075): {@code FOR UPDATE} vì dòng sắp bị DELETE, nhờ vậy
     * ST04 ({@code SKIP LOCKED}, T39) bỏ qua mũi đang bị xóa. Lọc cả {@code visit_id}: mũi của lượt khác là "không có".
     * Chỉ projection, không nạp entity (bulk DELETE / UPDATE sau đó không đồng bộ persistence context).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT id AS id, pet_id AS petId, vaccine_type_id AS vaccineTypeId, stock_lot_id AS stockLotId,
                   order_line_id AS orderLineId, (superseded_at IS NOT NULL) AS superseded
            FROM vaccinations
            WHERE id = :id AND visit_id = :visitId
            FOR UPDATE
            """, nativeQuery = true)
    Optional<LockedDose> lockDoseInVisit(@Param("id") Long id, @Param("visitId") Long visitId);

    /**
     * Xóa cứng một mũi tiêm (BR-KB-04, erd §0). Phải xóa trước dòng Order: {@code vaccinations.order_line_id} là FK
     * {@code RESTRICT}. Trả số dòng xóa (bên gọi đã khóa dòng nên phải là 1).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = "DELETE FROM vaccinations WHERE id = :id", nativeQuery = true)
    int deleteDose(@Param("id") Long id);

    /**
     * BR-TB-03 ngược chiều sau khi xóa mũi (docs/adr/0075 D4): mũi còn lại <b>mới nhất</b> của thú cùng loại vaccine —
     * cùng cách chọn với {@link #findLatestDoses} — hết bị thay thế. Chỉ đổi dòng đang {@code superseded}; thú không còn mũi
     * nào của loại đó thì không đổi gì (trả 0). Đi {@code ix_vaccinations_pet_type_administered}; {@code updated_at} đặt từ
     * {@code Clock} (docs/adr/0015). Gọi sau khi mũi bị xóa đã biến mất khỏi bảng.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            UPDATE vaccinations SET superseded_at = NULL, updated_at = :now
            WHERE id = (SELECT id FROM vaccinations WHERE pet_id = :petId AND vaccine_type_id = :vaccineTypeId
                        ORDER BY administered_on DESC, id DESC LIMIT 1)
              AND superseded_at IS NOT NULL
            """, nativeQuery = true)
    int restoreLatest(@Param("petId") Long petId, @Param("vaccineTypeId") Long vaccineTypeId, @Param("now") Instant now);

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

    /** Projection của {@link #lockDoseInVisit}: {@code stockLotId}, {@code orderLineId} đi thẳng vào hoàn kho / xóa dòng. */
    interface LockedDose {

        Long getId();

        Long getPetId();

        Long getVaccineTypeId();

        Long getStockLotId();

        Long getOrderLineId();

        Boolean getSuperseded();
    }

    /** Projection của {@link #findLatestDoses}. */
    interface LatestDose {

        Long getVaccineTypeId();

        LocalDate getAdministeredOn();

        LocalDate getNextDueDate();
    }
}
