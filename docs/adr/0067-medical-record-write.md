# ADR-0067: Xem / ghi bệnh án (`GET`, `PUT /api/visits/{visitId}/medical-record`) — khóa thú, thứ tự lỗi, PUT không đổi, biên tái khám

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-22
- **Đánh số lại:** ban đầu là ADR-0065; đổi +2 ngày 2026-10-22 vì trùng ADR-0064, 0065 của BE-2 (vào `dev` trước). Mọi tham chiếu ADR-0064…0067 trong code / tài liệu visit đã đổi theo (+2).
- **Liên quan:**
  - BR-KB-01, 06 (02 L157, L162), BR-KH-03 (L89), BR-TN-05, 08, BR-QT-13, 15; 03 §4 Visit (IN_PROGRESS: VET ghi bệnh án).
  - visit-v1 #12, #13; `docs/api/00-method.md` L60–70.
  - ADR-0058 §5 (thứ tự khóa), 0061 (gán đúng người đang gán: 200, không ghi), 0063 §8, 0066 (nền bệnh án; mục 1–4, 7 áp dụng nguyên).
  - Nợ: không trả, không mở; D043 giữ nguyên (đọc bản bổ sung quét bảng).
- **Triển khai (T27 phần 1):** `service/MedicalRecordService`, `controller/MedicalRecordController` (mới); `entity/MedicalRecord.rewrite`, `sameContent`; hợp đồng `m_visit.py` (#12 thêm 400 / 403, #13 thêm 403, ghi chú thứ tự lỗi); không migration. Test: `MedicalRecordServiceTest`, `MedicalRecordTest`, `MedicalRecordControllerSecurityTest`, `MedicalRecordIT`, `OpenApiContractParityIT` (104 → 106).

## Quyết định

1. **#13 khóa `pets FOR SHARE` (`PetQueryApi.findPetForShare`) trước `visits`.** `PetService.updatePet` khóa thú `FOR NO KEY UPDATE` rồi mới kiểm `hasMedicalHistory` (BR-KH-03). Lần ghi đầu chèn `medical_records` mà không khóa thú có thể chen sau bước kiểm đó: thú có bệnh án nhưng đổi loài được. `FOR SHARE` xung đột `FOR NO KEY UPDATE` nên bên đến sau chờ và thấy dữ liệu đã commit. Khớp thứ tự ADR-0058 §5.
2. **Không khóa tài khoản người gọi** (khác #8, ADR-0062 Q4): ghi bệnh án không giữ bất biến nào trên `accounts`; listener khóa tài khoản UPDATE `visits` nên chờ khóa lượt của #13.
3. **Thứ tự lỗi #13:** 400 body → 403 role → 404 lượt → 403 chi nhánh → 404 `GROOMING` → [khóa thú, lượt] → 403 không phải người phụ trách hiện tại → 409 không `IN_PROGRESS` → 400 BR-KB-06. **#12:** 403 role → 404 → 403 chi nhánh → 404 `GROOMING`. Người phụ trách và trạng thái chỉ đọc sau khóa (ADR-0063 §8); 403 đứng trước 409 để người không phải chủ lượt không dò được trạng thái.
4. **PUT không đổi nội dung** (đã có dòng, 5 trường giống hệt sau chuẩn hóa) → 200, không ghi, không audit, không đổi `last_edited_by` (khuôn ADR-0061). Lần ghi đầu luôn tạo dòng, kể cả khi mọi trường rỗng.
5. **BR-KB-06 chỉ kiểm khi `followUpDate` khác giá trị đã lưu** (lần ghi đầu coi giá trị cũ là null). Ngày đã lưu hợp lệ lúc ghi thì không bị chặn khi ADMIN giảm [CFG] (BR-QT-13); xóa ngày không cần kiểm. Điều kiện: `ngày check-in (giờ VN) < ngày ≤ ngày check-in + MEDICAL_RECORD_FOLLOW_UP_MAX_DAYS`, [CFG] đọc lúc ghi.
6. **#12:** `readOnly` + REPEATABLE READ, không khóa. `locked` = lượt `COMPLETED`; bản bổ sung chỉ truy vấn khi lượt `COMPLETED`, tên người ghi tra một lần cho cả lô (`findStaffByIds`), thiếu tên là `IllegalStateException`.

## Bảng đảm bảo

| # | Ràng buộc | Ai giữ | Test |
|---|---|---|---|
| G2 | Chỉ lượt `MEDICAL` có bệnh án | service #12, #13 | IT `aGroomingVisitHasNoMedicalRecordOnGetAndPut` |
| G4 | Biên tái khám, [CFG] | service (mục 5) | unit biên + múi giờ; IT biên, nửa đêm, [CFG] thu hẹp |
| G5 | Chỉ VET phụ trách hiện tại | `@PreAuthorize` + service dưới khóa | unit `InOrder`; IT role, chi nhánh, VET khác, sau gán lại, race với khóa lượt |
| G6 | Lượt không `IN_PROGRESS` thì không sửa | service 409 | IT: `COMPLETED`, `WAITING`, `xmin` không đổi |
| G12 | Biết ai ghi phần nào | audit mỗi lần ghi | IT hai VET qua gán lại → hai dòng audit |
| G14 | Khóa loài sau bệnh án | khóa thú (mục 1) + `hasMedicalHistory` | IT `PATCH` loài → 400 BR-KH-03; race giữ khóa thú |
| — | Audit lỗi thì rollback cả bệnh án | một transaction, `saveAndFlush` trước audit | IT trigger tạm chặn INSERT `audit_logs` / `medical_records` |

## Hệ quả

- Mỗi lần VET bấm lưu có thay đổi thêm một dòng `audit_logs` (đã nêu ở ADR-0066); lần không đổi thì không.
- Request trùng của hai tab cùng VET chạy tuần tự nhờ khóa lượt; lần sau thấy nội dung giống thì là no-op.
- Hồ sơ cho khách (#21) vẫn phải dùng DTO riêng (ADR-0066 §9).
