package com.petcare.module.workforce.listener;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.petcare.module.identity.api.StaffLeftBranchEvent;
import com.petcare.module.workforce.service.StaffLeftBranchService;

/**
 * BR-NS-09 (03 Phân ca#6, Yêu cầu#5): nhân viên bị vô hiệu hóa hoặc điều chuyển → phân ca {@code SCHEDULED} chưa bắt đầu
 * của họ tại chi nhánh cũ bị hủy, yêu cầu {@code PENDING} bị từ chối (docs/adr/0149 bước 5, trả D071). Đồng bộ, trong
 * transaction của identity (convention 07 §7.2): không {@code @Async}, không {@code AFTER_COMMIT}, không bắt lỗi — lỗi ở
 * đây rút cả thao tác gốc. Hiện là listener duy nhất của sự kiện nên không cần {@code @Order}; thêm listener thứ hai thì
 * phải ghi thứ tự khóa vào {@code docs/architecture/concurrency-locking.md}.
 */
@Component
public class StaffLeftBranchListener {

    private final StaffLeftBranchService service;

    public StaffLeftBranchListener(StaffLeftBranchService service) {
        this.service = service;
    }

    @EventListener
    public void on(StaffLeftBranchEvent event) {
        service.cancelUpcomingShiftsOf(event.staffId(), event.branchId());
    }
}
