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
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

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
    @Operation(operationId = "listMyNotifications", tags = "Notifications",
            summary = "Thông báo của tôi",
            description = "UC88 · —. Actor: A02–A08. Quyền: Người đang đăng nhập. Chuyển trạng thái: —. "
                + "Mới nhất trước (`createdAt` giảm dần, trùng giờ thì `notificationId` giảm dần). `unreadOnly` rỗng / "
                + "vắng = false. `page` < 0 → 0, `size` kẹp 1–100. Chỉ thông báo của chủ token (docs/adr/0039).",
            parameters = {
                    @Parameter(name = "unreadOnly", in = ParameterIn.QUERY, example = "true"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0",
                            description = "Số trang, bắt đầu từ 0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400, description = "Tham số sai kiểu (`MALFORMED_REQUEST`); BR-TK-17")
    @GetMapping
    @PreAuthorize(ANY_SIGNED_IN)
    public ApiResponse<PageResponse<NotificationResponse>> listMyNotifications(
            @RequestParam(required = false) Boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(notifications.listMyNotifications(unreadOnly, page, size));
    }

    /** care-v1 #6. */
    @Operation(operationId = "countMyUnreadNotifications", tags = "Notifications",
            summary = "Số thông báo chưa đọc",
            description = "UC88 · —. Actor: A02–A08. Quyền: Người đang đăng nhập. Chuyển trạng thái: —. A3.")
    @ApiErrorDoc(status = 400, description = "BR-TK-17")
    @GetMapping("/unread-count")
    @PreAuthorize(ANY_SIGNED_IN)
    public ApiResponse<UnreadCountResponse> countMyUnreadNotifications() {
        return ApiResponse.ok(notifications.countMyUnreadNotifications());
    }

    /** care-v1 #7. Gọi lại vẫn 204; thông báo không tồn tại hoặc của người khác: 404 cùng message. */
    @Operation(operationId = "markNotificationRead", tags = "Notifications",
            summary = "Đánh dấu đã đọc",
            description = "UC88 · —. Actor: A02–A08. Quyền: Chủ thông báo. Chuyển trạng thái: —. Gọi lại "
                + "vẫn 204 và giữ `readAt` của lần đọc đầu (docs/adr/0039).",
            parameters = {
                    @Parameter(name = "notificationId", in = ParameterIn.PATH, example = "128")})
    @ApiErrorDoc(status = 400, description = "`notificationId` sai kiểu (`MALFORMED_REQUEST`); BR-TK-17")
    @ApiErrorDoc(status = 404, description = "Không tìm thấy thông báo #<id> — cả khi thông báo là của "
            + "người khác")
    @PostMapping("/{notificationId}/read")
    @PreAuthorize(ANY_SIGNED_IN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markNotificationRead(@PathVariable Long notificationId) {
        notifications.markNotificationRead(notificationId);
    }

    /** care-v1 #8. */
    @Operation(operationId = "markAllNotificationsRead", tags = "Notifications",
            summary = "Đánh dấu tất cả đã đọc",
            description = "UC88 · —. Actor: A02–A08. Quyền: Người đang đăng nhập. Chuyển trạng thái: —. "
                + "Đánh dấu mọi thông báo chưa đọc tại thời điểm gọi; thông báo đến sau vẫn chưa đọc. Thông báo đã đọc "
                + "giữ `readAt` cũ (docs/adr/0039).")
    @ApiErrorDoc(status = 400, description = "BR-TK-17")
    @PostMapping("/read-all")
    @PreAuthorize(ANY_SIGNED_IN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllNotificationsRead() {
        notifications.markAllNotificationsRead();
    }
}
