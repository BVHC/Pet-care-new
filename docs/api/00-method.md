# Phương pháp viết & kiểm chứng API contract

> Áp dụng cho mọi file trong `docs/api/`. Viết contract mới phải đi hết 3 pha
> (thiết kế → viết → kiểm chứng). Không báo "xong module X" khi chưa pass V1–V5.

## 1. Nguyên tắc thiết kế (system-first, chống dập khuôn)

1. **Đi từ hệ thống, không từ kiến trúc.** Thứ tự suy luận bắt buộc:
   ops (`01`) → rules (`02`) → FSM (`03`) → ngôn ngữ (`04`) → domain (`05`) →
   ERD (`06`) → resources/operations → contract → OpenAPI. Cấm vẽ endpoint
   từ cảm quan REST/CRUD rồi gán rule ngược lại.
2. **Mỗi endpoint phải có business operation đứng sau.** Không có op thì không
   có endpoint. Bảng DB không phải resource; field DB không tự thành field API.
3. **Không phát minh hành vi.** Thiếu thông tin → chọn phát minh ít nhất làm
   `ASSUMPTION (A#)`, hoặc đẩy về `TBD (Q#)`. Cấm "thêm cho chắc" (policy giả,
   rotation giả, filter giả) rồi ghi CONFIRMED.
4. **Convention BE thắng cảm quan REST.** Envelope + status mapping theo
   `docs/convention/backend/04-exception-handling` (không 422, conflict → 409).
   Quy ước mới chỉ được PROPOSED + TBD, không tự chốt.
5. **YAGNI.** Pagination/filter/custom-role/policy/rotation… chưa có trong docs
   thì PROPOSED + TBD, không mặc định confirmed. File contract chỉ chứa cái
   module đó cần, không "chuẩn bị cho tương lai".

## 2. Checklist kiểm chứng (chạy sau mỗi module)

### V1 — File hoạt động được (máy kiểm)
- [ ] `node docs/api/check-contracts.mjs` pass cho file yaml mới (parse OK,
  `$ref` resolve hết, `operationId` unique, responses đầy đủ).
- [ ] Không còn `description:` trần chứa ` #` (YAML cắt thành comment) hay
  `": "` gây lỗi parse — script đã quét, nhưng khi viết tay phải quote trước.
- [ ] Mọi path trong yaml xuất hiện trong bảng A của file md (và ngược lại).
- [ ] `required` trong schema khớp với contract md (field nào req phải ghi rõ nguồn).

### V2 — Đúng với docs (người kiểm, đối chiếu từng dòng)
- [ ] Mỗi endpoint trace về đúng mã op trong `01` (ghi mã op vào bảng A).
- [ ] Mọi RULE-ID trích dẫn tồn tại trong `02` và đúng số (không nhầm 01-02/01-08…).
- [ ] Transition khớp **chính xác** mermaid FSM trong `03` — không thêm cạnh
      (bài học: auto-unlock ở auth-v1).
- [ ] Enum khớp `04`/ERD; required/nullable/UK khớp bảng ERD trong `06`.
- [ ] Op system-internal (Check/Expire/Process…) không expose thành API.
- [ ] Tham chiếu cross-module trỏ đúng module chủ (không định nghĩa lại ở module khác).

### V3 — Chuyên nghiệp, không over-engineering
- [ ] Không endpoint thừa (mỗi endpoint có op), không CRUD theo bảng.
- [ ] Mỗi assumption đều là phương án ít-phát-minh-nhất và đã ghi A#.
- [ ] Không 422; envelope chuẩn convention; idempotency chỉ khi docs đòi hỏi.
- [ ] Endpoint internal/system phân loại đúng (public/authenticated/role/owner).

### V4 — Nhất quán liên file
- [ ] Cấu trúc A–F + legend giữ nguyên như auth-v1.
- [ ] Enum `RoleCode`/`AccountStatus`/envelope `Error` giống các file trước.
- [ ] Không mâu thuẫn contract đã chốt (auth A5 manual-unlock, A7 single-role…).
- [ ] Q đánh số local trong file (Q1…), không dùng chung số với file khác.

### V5 — Bằng chứng trước khẳng định
- [ ] Script pass (dán output), yaml parse ra đúng paths — rồi mới báo xong.
