package com.petcare.module.branch.api;

import java.util.Collection;
import java.util.Optional;
import java.util.SortedMap;

/**
 * Owner: branch (CN) · BE-2 — BE-1 thêm 2026-10-11 cho quản trị nhân viên (T14, docs/adr/0029, nợ D014) và 2026-10-15
 * cho lịch hẹn (T16, docs/adr/0045).
 * Caller: identity (đổi chức vụ, điều chuyển, vô hiệu hóa, kích hoạt lại, khóa); appointment (đặt lịch, đổi khung).
 */
public interface BranchApi {

    /**
     * Khóa dòng {@code branches} của từng id theo thứ tự id tăng dần, trong transaction của bên gọi
     * ({@code MANDATORY}, không được {@code readOnly}), và trả trạng thái đọc dưới khóa. Khóa giữ tới khi bên gọi
     * commit hoặc rollback. Thứ tự khóa của luồng nhân viên: {@code branches → accounts → staff_profiles → sessions}
     * — bên gọi phải gọi method này <b>trước</b> khi khóa {@code accounts}.
     * <p>
     * Id không tồn tại vắng mặt trong map (không ném lỗi): bên gọi tự chọn 400 BR-QT-03 hay 404. Lỗi khóa
     * ({@code PessimisticLockingFailureException}, deadlock) đánh dấu transaction rollback-only — bên gọi không bắt,
     * để {@code GlobalExceptionHandler} trả 409.
     */
    SortedMap<Long, BranchQueryApi.BranchStatus> lockForStaffChange(Collection<Long> branchIds);

    /**
     * Khóa dòng {@code branches} của chi nhánh đặt lịch ({@code FOR NO KEY UPDATE}) trong transaction của bên gọi
     * ({@code MANDATORY}, không được {@code readOnly}) và trả trạng thái đọc dưới khóa (docs/adr/0045). Cùng dòng mà mọi
     * luồng ghi lịch / quota / dịch vụ của branch đã khóa, nên đặt / đổi lịch xếp hàng với thêm ngày nghỉ, đổi giờ mở
     * cửa, đổi quota, bật / tắt dịch vụ và với lượt đặt khác của cùng chi nhánh. Thứ tự khóa của lịch hẹn:
     * {@code advisory(thú) → branches → appointments}; bên gọi phải gọi method này <b>trước</b> mọi
     * {@code BranchQueryApi.*} trong transaction, và chỉ sau khi đã phân quyền.
     * <p>
     * Id không tồn tại → {@code Optional.empty()} (không ném): bên gọi tự chọn 404. Lỗi khóa (deadlock) đánh dấu
     * transaction rollback-only — bên gọi không bắt, để {@code GlobalExceptionHandler} trả 409.
     */
    Optional<BranchQueryApi.BranchStatus> lockForBooking(Long branchId);
}
