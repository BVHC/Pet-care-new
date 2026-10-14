package com.petcare.module.care.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.care.dto.NotificationResponse;
import com.petcare.module.care.dto.UnreadCountResponse;
import com.petcare.module.care.service.MyNotificationService;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

/**
 * Thông báo trong ứng dụng của chính người đang đăng nhập — care-v1 #5–8 (UC88, A02–A08 = mọi role; docs/adr/0039).
 * Không có body; tham số sai kiểu là 400 {@code MALFORMED_REQUEST}, page / size ngoài khoảng được kẹp. Không được miễn
 * BR-TK-17. Cài đặt nhận thông báo (#9, #10) chưa làm: nội dung còn TBD (care-v1 Q1).
 */
@RestController
@RequestMapping("/api/me/notifications")
public class MyNotificationController {

    /** Mọi role đã đăng nhập; phạm vi dữ liệu là chủ token, lọc trong service. */
    static final String ANY_SIGNED_IN = "isAuthenticated()";

    private final MyNotificationService notifications;

    public MyNotificationController(MyNotificationService notifications) {
        this.notifications = notifications;
    }

    /** care-v1 #5. Mới nhất trước. */
    @GetMapping
    @PreAuthorize(ANY_SIGNED_IN)
    public ApiResponse<PageResponse<NotificationResponse>> listMyNotifications(
            @RequestParam(required = false) Boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(notifications.listMyNotifications(unreadOnly, page, size));
    }

    /** care-v1 #6. */
    @GetMapping("/unread-count")
    @PreAuthorize(ANY_SIGNED_IN)
    public ApiResponse<UnreadCountResponse> countMyUnreadNotifications() {
        return ApiResponse.ok(notifications.countMyUnreadNotifications());
    }

    /** care-v1 #7. Gọi lại vẫn 204; thông báo không tồn tại hoặc của người khác: 404 cùng message. */
    @PostMapping("/{notificationId}/read")
    @PreAuthorize(ANY_SIGNED_IN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markNotificationRead(@PathVariable Long notificationId) {
        notifications.markNotificationRead(notificationId);
    }

    /** care-v1 #8. */
    @PostMapping("/read-all")
    @PreAuthorize(ANY_SIGNED_IN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllNotificationsRead() {
        notifications.markAllNotificationsRead();
    }
}
