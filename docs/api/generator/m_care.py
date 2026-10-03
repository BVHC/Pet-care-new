from lib import Module

m = Module(
    key="care", title="Care & Notification API v1", codes="TB", owner="BE-1",
    sources="01 UC87, UC88; 02 BR-TB-01…06, BR-LT-10; 03 #10 Care Task; 04 §11; 05 §11 (care_tasks, notifications, notification_outbox, accounts.notification_settings)",
    intro="Danh sách việc gọi điện của lễ tân (Care Task), thông báo trong ứng dụng, cài đặt nhận thông báo.",
    frozen=[
        "ST04 (nhắc tái chủng / tái khám), ST18 (sinh Care Task), ST19 (hủy Care Task khi thú mất), ST20 (gửi thông báo, thử lại) là tác vụ hệ thống; sinh / hủy Care Task tự động đi qua `CareTaskApi` (06-module-contracts).",
        "Không có endpoint tạo Care Task thủ công: Care Task#1 chỉ do ST18 kích hoạt.",
        "Mẫu thông báo do ADMIN sửa ở module identity (`/notification-templates`).",
    ],
    tags={
        "Care tasks": "Việc gọi điện chăm sóc khách (UC87)",
        "Notifications": "Thông báo trong ứng dụng và cài đặt (UC88)",
    },
    security=[
        "Care Task chỉ lễ tân của chi nhánh được giao xem và thực hiện (BR-TB-05); vai trò khác không thấy chức năng.",
        "Mỗi người chỉ đọc / đánh dấu đã đọc thông báo của chính mình. Cài đặt nhận thông báo chỉ dành cho CUSTOMER (UC88).",
        "SĐT cần gọi: Care Task PICKUP_OVERDUE dùng SĐT khẩn ghi lúc nhận thú, loại khác dùng SĐT hồ sơ khách (BR-TB-05).",
    ],
    assumptions=[
        ("A1", "Cài đặt nhận thông báo là bật / tắt từng loại nhắc (lịch hẹn, tái chủng, tái khám) theo từng kênh; email bảo mật (OTP, mật khẩu, đổi email) luôn gửi", "05 `accounts.notification_settings` JSONB [ERD], docs không quy định nội dung"),
        ("A2", "Tắt nhắc qua cài đặt không làm sinh Care Task thay thế", "BR-TB-02 chỉ sinh Care Task cho hồ sơ không có email"),
        ("A3", "Có endpoint đếm thông báo chưa đọc cho huy hiệu trên giao diện", "Nhu cầu hiển thị; chỉ đọc, không đổi nghiệp vụ"),
    ],
    questions=[
        ("Q1", "Cài đặt nhận thông báo (UC88) gồm những gì? Contract đang đề xuất bật / tắt 3 loại nhắc theo 2 kênh (A1).", "TBD (PO)", "05 `notification_settings` [ERD]"),
    ],
)

m.param("CareTaskId", "careTaskId", "path", "int64")
m.param("NotificationId", "notificationId", "path", "int64")

m.schema("CareTaskType", None, enum=["VACCINE_DUE", "VACCINE_OVERDUE", "FOLLOW_UP_DUE", "PICKUP_OVERDUE"],
         desc="TÁI_CHỦNG, QUÁ_HẠN_TÁI_CHỦNG, TÁI_KHÁM, QUÁ_HẠN_ĐÓN (05 §0)")
m.schema("CareTask", {
    "careTaskId": "int64", "type": "ref:CareTaskType", "status": "enum:OPEN,DONE,CANCELLED", "branchId": "int64",
    "customerId": "int64", "customerName": "string", "phoneToCall": "phone?|BR-TB-05",
    "petId": "int64", "petName": "string", "vaccinationId": "int64?", "medicalRecordVisitId": "int64?",
    "boardingBookingId": "int64?", "dueDate": "date|Ngày tái chủng / tái khám / ngày quá hạn",
    "context": "string|Mô tả ngắn: tên loại vaccine, ngày tái khám, mã đặt chỗ…",
    "result": "enum:REACHED,UNREACHABLE?", "note": "string?", "cancelReason": "string?",
    "handledBy": "int64?", "handledAt": "datetime?", "createdAt": "datetime",
})
m.schema("CompleteCareTaskRequest", {
    "result": "enum:REACHED,UNREACHABLE|LIÊN_HỆ_ĐƯỢC / KHÔNG_LIÊN_LẠC_ĐƯỢC", "note": "string",
})
m.schema("CancelCareTaskRequest", {"reason": "string:300"})
m.schema("Notification", {
    "notificationId": "int64", "type": "string|Ví dụ VACCINE_REMINDER, CARE_LOG_ABNORMAL", "title": "string",
    "body": "string", "linkUrl": "url?|Ví dụ link form đặt lịch điền sẵn (BR-TB-01, 06)", "readAt": "datetime?",
    "createdAt": "datetime",
})
m.schema("UnreadCount", {"count": "int"})
m.schema("ChannelToggle", {"email": "bool", "inApp": "bool"})
m.schema("NotificationSettings", {
    "appointmentReminder": "ref:ChannelToggle", "vaccineReminder": "ref:ChannelToggle",
    "followUpReminder": "ref:ChannelToggle",
}, desc="Đề xuất, xem A1 / Q1")

LT = "Lễ tân chi nhánh được giao"
m.op("get", "/care-tasks", "listCareTasks", "Danh sách việc gọi điện của chi nhánh", "Care tasks", "UC87",
     "BR-TB-05, BR-LT-10", "A06", LT, resp="CareTask", page=True,
     query=["status=enum:OPEN,DONE,CANCELLED", "type=enum:VACCINE_DUE,VACCINE_OVERDUE,FOLLOW_UP_DUE,PICKUP_OVERDUE",
            "dueFrom=date", "dueTo=date", "page", "size"], errors=(400, 401, 403),
     notes="Mặc định OPEN, sắp theo dueDate.")
m.op("get", "/care-tasks/{careTaskId}", "getCareTask", "Chi tiết Care Task", "Care tasks", "UC87", "BR-TB-05", "A06",
     LT, resp="CareTask", path_params=["CareTaskId"], errors=(401, 403, 404))
m.op("post", "/care-tasks/{careTaskId}/complete", "completeCareTask", "Ghi kết quả gọi điện", "Care tasks", "UC87",
     "BR-TB-05", "A06", LT, "OPEN → DONE (Care Task#2)", body="CompleteCareTaskRequest", resp="CareTask",
     path_params=["CareTaskId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "`BR-TB-05` chưa chọn kết quả / thiếu ghi chú", 409: "Task không OPEN"},
     notes="Không liên lạc được: không sinh task gọi lại.")
m.op("post", "/care-tasks/{careTaskId}/cancel", "cancelCareTask", "Hủy Care Task", "Care tasks", "UC87", "BR-TB-05",
     "A06", LT, "OPEN → CANCELLED (Care Task#3)", body="CancelCareTaskRequest", resp="CareTask",
     path_params=["CareTaskId"], errors=(400, 401, 403, 404, 409),
     err_desc={400: "Thiếu lý do", 409: "Task không OPEN"})

m.op("get", "/me/notifications", "listMyNotifications", "Thông báo của tôi", "Notifications", "UC88", "—", "A02–A08",
     "Người đang đăng nhập", resp="Notification", page=True, query=["unreadOnly=bool", "page", "size"],
     errors=(401,), notes="Mới nhất trước.")
m.op("get", "/me/notifications/unread-count", "countMyUnreadNotifications", "Số thông báo chưa đọc", "Notifications",
     "UC88", "—", "A02–A08", "Người đang đăng nhập", resp="UnreadCount", errors=(401,), notes="A3.")
m.op("post", "/me/notifications/{notificationId}/read", "markNotificationRead", "Đánh dấu đã đọc", "Notifications",
     "UC88", "—", "A02–A08", "Chủ thông báo", status=204, path_params=["NotificationId"], errors=(401, 404),
     idem="Có")
m.op("post", "/me/notifications/read-all", "markAllNotificationsRead", "Đánh dấu tất cả đã đọc", "Notifications",
     "UC88", "—", "A02–A08", "Người đang đăng nhập", status=204, errors=(401,), idem="Có")
m.op("get", "/me/notification-settings", "getMyNotificationSettings", "Cài đặt nhận thông báo", "Notifications",
     "UC88", "BR-TB-02", "A02", "Chỉ CUSTOMER", resp="NotificationSettings", errors=(401, 403))
m.op("put", "/me/notification-settings", "updateMyNotificationSettings", "Sửa cài đặt nhận thông báo",
     "Notifications", "UC88", "BR-TB-02", "A02", "Chỉ CUSTOMER", body="NotificationSettings",
     resp="NotificationSettings", errors=(400, 401, 403), notes="A1, A2; nội dung chờ Q1.")
