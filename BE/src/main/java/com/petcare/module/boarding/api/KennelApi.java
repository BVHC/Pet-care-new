package com.petcare.module.boarding.api;

import java.util.Collection;
import java.util.Map;

/**
 * Chuồng dùng cho điều trị nội trú (docs/09 §C, BR-LT-13, BR-LT-14; docs/adr/0124 mục 5, docs/adr/0148). Chỉ dùng khi nhập
 * viện từ lượt khám (đường a): thú đang lưu trú giữ nguyên chuồng của đặt chỗ nên không đi qua đây.
 * <p>
 * Mọi method chạy trong transaction của bên gọi ({@code MANDATORY}); bên gọi không bắt ngoại lệ của chúng. Khóa dòng
 * chuồng {@code FOR NO KEY UPDATE} trước khi đọc, nên bên gọi (inpatient) khóa chuồng <b>sau cùng</b> trong thứ tự
 * {@code pets FOR SHARE → visits → orders → kennels}.
 */
public interface KennelApi {

    /**
     * Chuồng#6 ({@code AVAILABLE → OCCUPIED}, SYS ← Nhập viện#1 đường a). Không kiểm loài, cân nặng hay sức chứa đặt chỗ
     * (VET chịu trách nhiệm, docs/adr/0124 mục 5).
     *
     * @param branchId chi nhánh của lượt khám; chuồng phải cùng chi nhánh
     * @throws com.petcare.platform.exception.ResourceNotFoundException      chuồng không tồn tại
     * @throws com.petcare.platform.exception.BusinessRuleViolationException {@code BR-LT-13}: chuồng khác chi nhánh hoặc
     *                                                                       không {@code AVAILABLE}
     */
    void occupyForTreatment(Long kennelId, Long branchId);

    /**
     * Chuồng#7 ({@code OCCUPIED → AVAILABLE}, SYS ← Nhập viện#2 đường a).
     *
     * @throws com.petcare.platform.exception.ResourceNotFoundException        chuồng không tồn tại
     * @throws com.petcare.platform.exception.InvalidStateTransitionException chuồng không {@code OCCUPIED} (kể cả {@code MAINTENANCE}: kết thúc bảo trì là Chuồng#5 của UC54)
     */
    void release(Long kennelId);

    /**
     * Mã chuồng theo id chuồng — danh sách ca nội trú nhập từ lượt khám (inpatient-v1 #2, docs/adr/0149 mục 19). Đọc
     * không khóa, trong transaction của bên gọi ({@code MANDATORY}, như mọi method của interface này; bên gọi đọc
     * {@code readOnly} + REPEATABLE READ để cùng ảnh chụp với trang). Id không tồn tại thì vắng mặt; tập rỗng → map rỗng,
     * không chạy SQL. Bên gọi truyền tối đa 100 id (một trang).
     */
    Map<Long, String> findCodesByKennelIds(Collection<Long> kennelIds);

    /**
     * Mã chuồng đang gán cho đặt chỗ ({@code boarding_bookings.kennel_id}) theo id đặt chỗ — ca nội trú nhập từ lưu trú
     * nằm ở chuồng của đặt chỗ (inpatient-v1 #2). Đặt chỗ chưa gán chuồng hoặc không tồn tại thì vắng mặt; còn lại như
     * {@link #findCodesByKennelIds}.
     */
    Map<Long, String> findCodesByBookingIds(Collection<Long> bookingIds);
}
