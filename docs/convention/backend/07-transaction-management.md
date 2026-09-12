[← Backend Convention Index](../backend-convention.md)

# 7. Transaction Management

- `@Transactional` đặt ở **Service / TransitionHandler**, bao trọn 1 use case nghiệp vụ hoàn chỉnh (vd Atomic Reschedule: khoá slot mới + giải phóng slot cũ trong cùng 1 transaction).
- Không đặt `@Transactional` ở Controller hoặc Repository.
- **Ngoại lệ đã biết:** job dọn dữ liệu định kỳ chạy trên tập lớn (vd `RefreshTokenCleanupService`) cố ý tách `@Transactional` theo từng batch thay vì bao trọn toàn bộ job trong 1 transaction, để tránh transaction/lock kéo dài trên bảng lớn — xem lý do chi tiết ở `docs/adr/0003-refresh-token-cleanup-job.md`.
- Cơ chế xử lý TTL (BookingHold, OrderHold, OTP, CaregiverInvite...) và chiến lược concurrency locking (optimistic/pessimistic) thuộc phạm vi **architecture**, đặc tả riêng ở `docs/architecture/` — không nằm trong tài liệu convention này.

---

[← 6. Validation](06-validation.md) · [Backend Convention Index](../backend-convention.md) · [Tiếp: 8. Logging & Audit →](08-logging-and-audit.md)
