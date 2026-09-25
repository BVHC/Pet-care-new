package com.petcare.module.inventory.repository;

import com.petcare.module.inventory.entity.InventoryReservation;
import com.petcare.platform.enums.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    List<InventoryReservation> findAllByOrderIdAndStatus(UUID orderId, ReservationStatus status);

    /**
     * Conditional transition HELD -> RELEASED, trả về số dòng bị ảnh hưởng (0 hoặc 1). Dùng thay
     * cho optimistic-lock retry trên chính dòng reservation — atomic ở tầng DB nên
     * releaseReservation() có thể chạy an toàn khi CancelOrder và ProcessOrderTimeout race nhau:
     * caller thấy {@code 0} nghĩa là reservation đã được release bởi lệnh khác, bỏ qua không mutate
     * InventoryItem lần 2 (chống double-release cộng dồn sai quantityAvailable).
     */
    @Modifying
    @Query("UPDATE InventoryReservation r SET r.status = com.petcare.platform.enums.ReservationStatus.RELEASED "
            + "WHERE r.id = :id AND r.status = com.petcare.platform.enums.ReservationStatus.HELD")
    int releaseIfHeld(@Param("id") UUID id);
}
