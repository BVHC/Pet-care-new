package com.petcare.module.identity.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Owner: identity (TK, QT) · BE-1. Chỉ đọc thông tin nhân viên. */
public interface StaffDirectoryApi {

    /**
     * {@code active} = status ACTIVE và không bị khóa ({@code is_locked}; khóa tạm {@code locked_until} không tính);
     * {@code online} = {@code last_seen_at} trong {@code staff.online_window_minutes} [CFG] (BR-TN-06) — docs/adr/0024.
     */
    record StaffSummary(Long accountId, String fullName, Role role, Long branchId,
                        boolean active, boolean online) {}

    record PublicVetProfile(Long accountId, String fullName, String avatarUrl, String specialty,
                            String bio, Long branchId) {}

    Optional<StaffSummary> findStaff(Long accountId);

    /**
     * Như {@link #findStaff} nhưng khóa chia sẻ dòng {@code accounts} trước ({@code SELECT … FOR SHARE}) rồi mới đọc —
     * nghĩa vụ của luồng tạo việc dở cho nhân viên (visit gán lượt Visit#2 / #4, docs/adr/0061; docs/adr/0031 mục 9):
     * điều chuyển / vô hiệu hóa / khóa tài khoản giữ {@code FOR NO KEY UPDATE} trên dòng này, nên bên gọi chờ chúng
     * commit rồi thấy chi nhánh / trạng thái mới; FK {@code visits.assignee_id} chỉ lấy {@code FOR KEY SHARE}, không
     * chặn được. {@code MANDATORY}, từ chối transaction readOnly; khóa giữ tới khi transaction của bên gọi kết thúc.
     * Không phải nhân viên / không có → rỗng.
     */
    Optional<StaffSummary> findStaffForShare(Long accountId);

    /** Nhân viên active của chi nhánh theo chức vụ, người online xếp trước (BR-TN-05, 06). */
    List<StaffSummary> findAssignableStaff(Long branchId, Role role);

    /**
     * Số BRANCH_MANAGER {@code status = ACTIVE} gắn với chi nhánh, <b>tính cả người đang bị khóa</b> (BR-QT-04: quản lý
     * cuối cùng bị khóa thì chi nhánh vẫn hoạt động; điều kiện Chi nhánh#2) — docs/adr/0024. Đọc không khóa.
     */
    int countBranchManagers(Long branchId);

    /** Người nhận thông báo theo chức vụ tại chi nhánh (BRANCH_MANAGER, RECEPTIONIST…). */
    List<Long> findActiveStaffIds(Long branchId, Role role);

    List<Long> findActiveSuperManagerIds();

    /**
     * Hồ sơ VET hiển thị công khai: tài khoản ACTIVE, không bị khóa (BR-TK-20, UC15). Không lọc chi nhánh ACTIVE —
     * bên gọi lọc (BR-CK-01, docs/adr/0024).
     */
    List<PublicVetProfile> listPublicVets();

    Optional<String> findEmail(Long accountId);

    /**
     * Nhiều nhân viên một lần, mọi trạng thái kể cả bị khóa / vô hiệu hóa (visit ghép tên người phụ trách cho hàng đợi,
     * visit-v1 #2–4; docs/adr/0060). Một câu SQL bất kể số id; id không phải nhân viên thì bỏ qua, mỗi id tối đa một lần,
     * thứ tự tùy ý; tập rỗng → rỗng, không chạm DB. Bên gọi truyền tối đa 100 id và đã tự phân quyền.
     */
    List<StaffSummary> findStaffByIds(Collection<Long> accountIds);
}
