package com.petcare.module.organization.repository;

import com.petcare.module.organization.entity.OperatingHour;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OperatingHourRepository extends JpaRepository<OperatingHour, UUID> {

    List<OperatingHour> findAllByStoreIdOrderByDayOfWeek(UUID storeId);

    /**
     * Bulk JPQL DELETE (không phải derived "deleteAllByStoreId" load-rồi-remove-từng-entity) —
     * thực thi NGAY khi gọi, không bị Hibernate hoãn tới lúc flush. Bắt buộc dùng dạng này cho
     * semantics replace-all của ConfigureOperatingHour: nếu để derived delete (load entity rồi
     * remove(), bị Hibernate xếp vào action queue) chạy CÙNG transaction với saveAll() các row
     * mới, thứ tự flush mặc định của Hibernate là INSERT trước DELETE — sẽ đụng UNIQUE
     * constraint uq_operating_hours_store_day nếu dayOfWeek mới trùng dayOfWeek cũ chưa kịp xóa.
     * Bulk query này chạy độc lập, xóa xong ngay lập tức trước khi entity mới được tạo.
     */
    @Modifying
    @Query("DELETE FROM OperatingHour oh WHERE oh.storeId = :storeId")
    void deleteAllByStoreId(@Param("storeId") UUID storeId);
}
