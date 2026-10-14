package com.petcare.module.care.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.dto.NotificationResponse;
import com.petcare.module.care.dto.UnreadCountResponse;
import com.petcare.module.care.entity.Notification;
import com.petcare.module.care.mapper.NotificationMapper;
import com.petcare.module.care.repository.NotificationRepository;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;

/**
 * Thông báo trong ứng dụng của người đang đăng nhập — UC88, care-v1 #5–8 (docs/adr/0039). Mọi role; chủ luôn là tài
 * khoản của token, không bao giờ lấy từ request. Thông báo không có {@code branch_id} nên không qua phạm vi chi nhánh.
 * <ul>
 * <li>Đọc: readOnly, READ COMMITTED mặc định (đếm và trang có thể lệch một dòng khi ST20 vừa giao — chấp nhận).</li>
 * <li>Đánh dấu: một câu UPDATE {@code … AND read_at IS NULL}, idempotent, giữ thời điểm đọc lần đầu. Thông báo
 * không tồn tại và của người khác cùng 404, cùng message.</li>
 * </ul>
 * Không audit (không thuộc BR-QT-15), không outbox, không sự kiện.
 */
@Service
public class MyNotificationService {

    static final int MAX_PAGE_SIZE = 100;
    static final String RESOURCE = "thông báo";

    private final NotificationRepository notifications;
    private final NotificationMapper mapper;
    private final BranchScope branchScope;
    private final Clock clock;

    public MyNotificationService(NotificationRepository notifications, NotificationMapper mapper,
            BranchScope branchScope, Clock clock) {
        this.notifications = notifications;
        this.mapper = mapper;
        this.branchScope = branchScope;
        this.clock = clock;
    }

    /** care-v1 #5. {@code page} ≥ 0, {@code size} 1–100 (kẹp như {@code AccountAdminQueryService}). */
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> listMyNotifications(Boolean unreadOnly, int page, int size) {
        Long accountId = branchScope.current().accountId();
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        Page<Notification> result = Boolean.TRUE.equals(unreadOnly)
                ? notifications.findMineUnread(accountId, pageable)
                : notifications.findMine(accountId, pageable);
        return PageResponse.of(result.map(mapper::toResponse));
    }

    /** care-v1 #6. */
    @Transactional(readOnly = true)
    public UnreadCountResponse countMyUnreadNotifications() {
        Long accountId = branchScope.current().accountId();
        return mapper.toUnreadCount(Math.toIntExact(notifications.countUnread(accountId)));
    }

    /**
     * care-v1 #7. UPDATE trước; chỉ khi không đổi dòng nào mới hỏi dòng có thuộc chủ không — có (đã đọc) là thành
     * công, không có (không tồn tại / của người khác) là 404. Không ai xóa {@code notifications} nên giữa hai câu
     * không có kết quả sai.
     */
    @Transactional
    public void markNotificationRead(Long notificationId) {
        Long accountId = branchScope.current().accountId();
        if (notifications.markRead(notificationId, accountId, Instant.now(clock)) == 0
                && !notifications.existsByIdAndAccountId(notificationId, accountId)) {
            throw new ResourceNotFoundException(RESOURCE, notificationId);
        }
    }

    /** care-v1 #8. Mọi thông báo chưa đọc lúc câu lệnh chạy; dòng ST20 giao sau đó vẫn chưa đọc. */
    @Transactional
    public void markAllNotificationsRead() {
        notifications.markAllRead(branchScope.current().accountId(), Instant.now(clock));
    }
}
