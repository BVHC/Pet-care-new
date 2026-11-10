# API contract — Index

> Hợp đồng FE ↔ BE bản v1, thiết kế từ `docs/01`–`05` + `INDEX` (v16). Phương pháp, quy ước chung (envelope, mã lỗi, phân trang, kiểu dữ liệu) và checklist: [`00-method.md`](./00-method.md). Hợp đồng gọi chéo giữa module BE: [`../06-module-contracts.md`](../06-module-contracts.md).
>
> Mọi file `.md` / `.yaml` dưới đây được **sinh** từ `generator/m_<module>.py` — sửa khai báo rồi chạy `python docs/api/generator/generate.py`, sau đó `node docs/api/check-contracts.mjs`.

| Module | Mã | Owner | Endpoint | Đọc | Máy đọc |
|---|---|---|---|---|---|
| identity | TK, QT | BE-1 | 38 | [identity-v1.md](./identity-v1.md) | [openapi/identity-v1.yaml](./openapi/identity-v1.yaml) |
| customer | KH | BE-2 | 21 | [customer-v1.md](./customer-v1.md) | [openapi/customer-v1.yaml](./openapi/customer-v1.yaml) |
| catalog | SP (+ CK sản phẩm, dịch vụ) | BE-2 | 22 | [catalog-v1.md](./catalog-v1.md) | [openapi/catalog-v1.yaml](./openapi/catalog-v1.yaml) |
| branch | CN (+ UC33, UC42) | BE-2 | 21 | [branch-v1.md](./branch-v1.md) | [openapi/branch-v1.yaml](./openapi/branch-v1.yaml) |
| appointment | LH | BE-1 | 9 | [appointment-v1.md](./appointment-v1.md) | [openapi/appointment-v1.yaml](./openapi/appointment-v1.yaml) |
| visit | TN, KB | BE-1 | 21 | [visit-v1.md](./visit-v1.md) | [openapi/visit-v1.yaml](./openapi/visit-v1.yaml) |
| sales | BH, TG | BE-2 | 21 | [sales-v1.md](./sales-v1.md) | [openapi/sales-v1.yaml](./openapi/sales-v1.yaml) |
| inventory | KO | BE-2 | 17 | [inventory-v1.md](./inventory-v1.md) | [openapi/inventory-v1.yaml](./openapi/inventory-v1.yaml) |
| boarding | LT | BE-2 | 22 | [boarding-v1.md](./boarding-v1.md) | [openapi/boarding-v1.yaml](./openapi/boarding-v1.yaml) |
| content | CK (trang), BV, DG | BE-2 | 21 | [content-v1.md](./content-v1.md) | [openapi/content-v1.yaml](./openapi/content-v1.yaml) |
| care | TB | BE-1 | 10 | [care-v1.md](./care-v1.md) | [openapi/care-v1.yaml](./openapi/care-v1.yaml) |
| report | BC | BE-2 | 7 | [report-v1.md](./report-v1.md) | [openapi/report-v1.yaml](./openapi/report-v1.yaml) |
| workforce | NS (tầng 3, T51, duyệt T54; ADR-0123) | BE-1 | 16 | [workforce-v1.md](./workforce-v1.md) | [openapi/workforce-v1.yaml](./openapi/workforce-v1.yaml) |
| ai | AI (tầng 3, T53, duyệt T54; đặc tả đầy đủ [07-ai-subsystem.md](../07-ai-subsystem.md)) | FE-1 (dẫn) · cả nhóm | 13 | [ai-v1.md](./ai-v1.md) | [openapi/ai-v1.yaml](./openapi/ai-v1.yaml) |
| fees | SP mở rộng (UC32; T52, [08](../08-tang3-be2-spec.md) §A) | BE-2 | 4 | [fees-v1.md](./fees-v1.md) | [openapi/fees-v1.yaml](./openapi/fees-v1.yaml) |
| shop | BH thương mại điện tử (UC61, 62, 64, 65; [08](../08-tang3-be2-spec.md) §B) | BE-2 | 19 | [shop-v1.md](./shop-v1.md) | [openapi/shop-v1.yaml](./openapi/shop-v1.yaml) |
| payment | TG thanh toán online (UC63; [08](../08-tang3-be2-spec.md) §C) | BE-2 | 6 | [payment-v1.md](./payment-v1.md) | [openapi/payment-v1.yaml](./openapi/payment-v1.yaml) |
| returns | BH, TG trả hàng – hoàn tiền (UC68, 69; [08](../08-tang3-be2-spec.md) §D) | BE-2 | 9 | [returns-v1.md](./returns-v1.md) | [openapi/returns-v1.yaml](./openapi/returns-v1.yaml) |
| stockops | KO chuyển kho, kiểm kê, truy vết lô (UC77–79; [08](../08-tang3-be2-spec.md) §E) | BE-2 | 13 | [stockops-v1.md](./stockops-v1.md) | [openapi/stockops-v1.yaml](./openapi/stockops-v1.yaml) |
| branchlife | CN tạm ngừng / đóng cửa (UC13; [08](../08-tang3-be2-spec.md) §F.1) | BE-2 | 4 | [branchlife-v1.md](./branchlife-v1.md) | [openapi/branchlife-v1.yaml](./openapi/branchlife-v1.yaml) |
| custmerge | KH gộp hồ sơ khách (UC27; [08](../08-tang3-be2-spec.md) §F.2) | BE-2 | 2 | [custmerge-v1.md](./custmerge-v1.md) | [openapi/custmerge-v1.yaml](./openapi/custmerge-v1.yaml) |
| articlex | BV bài viết của bác sĩ, bình luận (UC80, 82; [08](../08-tang3-be2-spec.md) §F.3) | BE-2 | 14 | [articlex-v1.md](./articlex-v1.md) | [openapi/articlex-v1.yaml](./openapi/articlex-v1.yaml) |
| inpatient | LT nội trú (UC55, 56; [09](../09-tang3-be1-spec.md) §C) | BE-1 | 10 | [inpatient-v1.md](./inpatient-v1.md) | [openapi/inpatient-v1.yaml](./openapi/inpatient-v1.yaml) |
| materialuse | KB trừ vật tư tiêu hao (ST07; [09](../09-tang3-be1-spec.md) §E) | BE-1 | 3 | [materialuse-v1.md](./materialuse-v1.md) | [openapi/materialuse-v1.yaml](./openapi/materialuse-v1.yaml) |
| **Tổng** | | | **344** (231 GĐ1 + 113 tầng 3, sau khi bỏ khám tại nhà, bỏ khiếu nại ở ADR-0122, thêm 2 endpoint workforce ở ADR-0123 và bỏ phẫu thuật `surgery-v1` (8 endpoint) ở ADR-0125; duyệt T54 5/11/2026; chưa cài đặt) | | |

## Tra nhanh theo use case

| Use case | File |
|---|---|
| UC01–UC11, sửa email khách / liên kết tại quầy (UC22), bác sĩ công khai (UC15), nhân viên có thể gán lượt (UC45) | identity |
| UC06 phần khách (hồ sơ khách, sổ địa chỉ), UC22–UC26 | customer |
| UC28–UC31, dịch vụ / sản phẩm công khai (UC15, UC16) | catalog |
| UC12, UC14, UC33, UC42, chi nhánh công khai (UC15) | branch |
| UC39, UC40, hạn chế đặt online | appointment |
| UC25 (hồ sơ sức khỏe), UC44–UC49, UC52, UC53 | visit |
| UC59 (hủy phiên trả thú), UC66, UC67, UC70–UC72 | sales |
| UC73–UC76 | inventory |
| UC54, UC57–UC60 | boarding |
| UC17, UC21, UC81, UC83, UC84 | content |
| UC87, UC88 | care |
| UC89 | report |

Use case tầng 3 (INDEX Bảng 2) chưa có endpoint, trừ phần đã có đặc tả bổ sung GĐ2 (duyệt T54, 5/11/2026): UC34–UC38 → workforce; UC90–UC94 → ai; UC32 → fees; UC61, UC62, UC64, UC65 → shop; UC63 → payment; UC68, UC69 → returns; UC77–UC79 → stockops; UC13 → branchlife; UC27 → custmerge; UC80, UC82 (và duyệt bài, ẩn bình luận của UC81) → articlex; UC55, UC56 → inpatient; ST07 → materialuse (UC85, UC86 và ST16, ST17 đã bỏ, ADR-0122; UC50 đã bỏ, ADR-0125). ST09–ST12, ST14, ST21 là tác vụ hệ thống, không có endpoint riêng.
