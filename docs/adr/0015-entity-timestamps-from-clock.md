# ADR-0015: `created_at` / `updated_at` lấy từ bean `Clock` qua Spring Data JPA Auditing

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-07
- **Liên quan:**
  - `docs/05-erd.md` §0 (cột chung `created_at`, `updated_at` TIMESTAMPTZ).
  - `docs/convention/backend/09-testing.md` (thời gian qua `Clock`, test dùng `Clock.fixed`).
  - BR-TK-07 (quota OTP đếm theo `otp_tokens.created_at`), BR-QT-13.
  - Nợ D004 (trả bởi ADR này). ADR-0013 (dùng cột snapshot riêng thay cho `created_at` một phần vì D004).
- **Triển khai:**
  - `BE/src/main/java/com/petcare/platform/config/JpaAuditingConfig.java` (`@EnableJpaAuditing(dateTimeProviderRef = "clockDateTimeProvider")`, `DateTimeProvider` = `Instant.now(clock)`).
  - `BE/src/main/java/com/petcare/platform/model/{CreatedAtEntity, TimestampedEntity}.java` (`@EntityListeners(AuditingEntityListener.class)`, `@CreatedDate`, `@LastModifiedDate`).
  - Test (tiêu chí → test):
    - Entity id IDENTITY: tạo đặt cả hai cột theo clock, sửa chỉ dời `updated_at`: `EntityTimestampsIT.identityEntityGetsBothTimestampsFromClockAndUpdateMovesOnlyUpdatedAt`.
    - Entity id gán tay (PK chuỗi): sửa lấy `updated_at` từ clock: `EntityTimestampsIT.assignedIdEntityUpdateTakesUpdatedAtFromClock`.
    - Bảng LOG (`audit_logs`): `EntityTimestampsIT.logTableCreatedAtComesFromClock`.
    - BR-TK-07 điều khiển hoàn toàn bằng clock, không sửa `created_at` bằng SQL: `RegistrationResendIT.quotaFollowsInjectedClockWithoutBackdating`.

## Bối cảnh

`@CreationTimestamp` / `@UpdateTimestamp` của Hibernate lấy giờ JVM, không qua bean `Clock` (`TimeConfig`). Ở production hai nguồn trùng nhau, nhưng test thay `Clock` (`Clock.fixed`, `MutableClock`) không điều khiển được cột thời điểm: BR-TK-07 so `created_at` (giờ JVM) với `Instant.now(clock)`, nên IT phải UPDATE `created_at` bằng SQL (`RegistrationResendIT.sentSecondsAgo`) và ADR-0013 phải tránh dựa vào `created_at`. Mọi rule đếm theo thời gian sau này (hạn chế đặt online 90 ngày BR-LH-09, feedback 5/ngày BR-DG-01…) sẽ gặp cùng vấn đề.

## Quyết định

1. **Dùng Spring Data JPA Auditing** cho hai base entity: `@EntityListeners(AuditingEntityListener.class)` trên `CreatedAtEntity`/`TimestampedEntity`, `@CreatedDate` cho `created_at`, `@LastModifiedDate` cho `updated_at` (mặc định cũng đặt lúc tạo — giống `@UpdateTimestamp` trước đây). Tên field giữ nguyên.
2. **Nguồn thời gian là bean `Clock`**: `platform/config/JpaAuditingConfig` khai báo `DateTimeProvider` trả `Instant.now(clock)`. Listener được Spring tạo (Spring Boot cấu hình `SpringBeanContainer` cho Hibernate), không cần static holder.
3. **DB giữ `DEFAULT now()`** cho dòng chèn bằng SQL (migration, test). Không sửa V1 (đã apply; header V1 L6-7 còn nhắc Hibernate — ghi chú ở system-overview).
4. **Ngoài phạm vi listener**, như trước: UPDATE hàng loạt JPQL/native không qua entity nên tự đặt `updated_at` nếu cần (`SessionRepository.revoke*` đã đặt `updatedAt = :now` từ `Clock`; `AccountRepository.touchLastSeen` cố ý không đổi `updated_at`).

## Lý do và phương án bị loại

| Phương án | Vì sao không chọn |
|---|---|
| Listener `@PrePersist`/`@PreUpdate` tự viết, lấy `Clock` qua static holder | Tự viết lại đúng thứ Spring Data đã có và đã được kiểm chứng; static holder là trạng thái toàn cục khó test |
| Generator Hibernate tùy biến (`@ValueGenerationType`) | Gắn chặt vào SPI của Hibernate 6, nhiều code hơn, vẫn cần lấy bean `Clock` từ ngoài Spring |
| Giữ Hibernate, rule đếm thời gian dùng cột riêng do service ghi | Chỉ trả nợ từng phần; mỗi rule mới phải nhớ thêm cột |

## Hệ quả

- Nợ D004 đã trả. IT điều khiển được cả `created_at`/`updated_at` bằng `Clock` của test; các IT đặt clock về năm 2000/2003 tạo dòng có `created_at` theo năm đó.
- Entity mới chỉ cần kế thừa một trong hai base. Entity id gán tay lưu qua `save()` đi đường `merge`: `@PrePersist` chạy trên bản managed nên vẫn có `created_at` (đường tạo mới chưa có trong code — kiểm khi task đầu tiên tạo `StaffProfile`/`SystemConfig`).
- Lệch giờ giữa ứng dụng và DB (nếu có) giờ thể hiện ở cột do JPA ghi so với cột `DEFAULT now()` chèn bằng SQL — như các cột thời điểm nghiệp vụ khác vốn đã lấy từ `Clock`.
- Mutation: bỏ `@EntityListeners` → cột lấy `DEFAULT now()` của DB (giờ thật), `EntityTimestampsIT` đỏ.
- **Đã kiểm 2026-10-07:** `mvn clean verify` xanh (466 unit + 174 IT); mutation bỏ `@EntityListeners` ở hai base entity → `EntityTimestampsIT` đỏ cả 3 case, khôi phục thì xanh.
