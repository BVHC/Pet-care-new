package com.petcare.module.customer.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.customer.entity.WeightRecord;

/** Lịch sử cân nặng (BR-KH-04); mọi câu theo thú dùng {@code ix_weight_records_pet_measured_at}. */
public interface WeightRecordRepository extends JpaRepository<WeightRecord, Long> {

    /** Mới nhất trước; cùng {@code measured_at} thì bản ghi sau (id lớn hơn) trước. */
    @Query(value = "SELECT * FROM weight_records WHERE pet_id = :petId ORDER BY measured_at DESC, id DESC",
            nativeQuery = true)
    List<WeightRecord> findByPet(@Param("petId") Long petId);

    @Query(value = """
            SELECT weight_kg FROM weight_records WHERE pet_id = :petId ORDER BY measured_at DESC, id DESC LIMIT 1
            """, nativeQuery = true)
    Optional<BigDecimal> findCurrentWeight(@Param("petId") Long petId);

<<<<<<< HEAD
    /** Cân nặng mới nhất của nhiều thú một lần ({@code PetQueryApi.findPets}); thú chưa cân không có dòng. */
    @Query(value = """
            SELECT DISTINCT ON (pet_id) pet_id AS petId, weight_kg AS weightKg FROM weight_records
            WHERE pet_id IN (:petIds) ORDER BY pet_id, measured_at DESC, id DESC
            """, nativeQuery = true)
    List<CurrentWeightRow> findCurrentWeights(@Param("petIds") Collection<Long> petIds);

    interface CurrentWeightRow {
=======
    /** Cân nặng hiện tại của một thú trong {@link #findCurrentWeights}. */
    interface CurrentWeight {
>>>>>>> e18c4ed (feat: triển khai hủy do phòng khám)
        Long getPetId();

        BigDecimal getWeightKg();
    }

<<<<<<< HEAD
=======
    /**
     * Cân nặng hiện tại theo lô ({@code PetQueryApi.findPets}), cùng thứ tự với {@link #findCurrentWeight}: một câu SQL,
     * mỗi thú tối đa một dòng; thú chưa cân thì không có dòng. Bên gọi không truyền tập rỗng.
     */
    @Query(value = """
            SELECT DISTINCT ON (pet_id) pet_id AS petId, weight_kg AS weightKg FROM weight_records
            WHERE pet_id IN (:petIds) ORDER BY pet_id, measured_at DESC, id DESC
            """, nativeQuery = true)
    List<CurrentWeight> findCurrentWeights(@Param("petIds") Collection<Long> petIds);

>>>>>>> e18c4ed (feat: triển khai hủy do phòng khám)
    /** Chỉ dùng khi xóa thú chưa giao dịch (BR-KH-06): khi đó chỉ có thể là bản ghi {@code OWNER}. */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM weight_records WHERE pet_id = :petId", nativeQuery = true)
    int deleteByPet(@Param("petId") Long petId);
}
