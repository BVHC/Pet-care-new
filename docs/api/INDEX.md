# API contract — Index

> Hợp đồng FE ↔ BE bản v1, thiết kế từ `docs/01`–`05` + `INDEX` (v16). Phương pháp, quy ước chung (envelope, mã lỗi, phân trang, kiểu dữ liệu) và checklist: [`00-method.md`](./00-method.md). Hợp đồng gọi chéo giữa module BE: [`../06-module-contracts.md`](../06-module-contracts.md).
>
> Mọi file `.md` / `.yaml` dưới đây được **sinh** từ `generator/m_<module>.py` — sửa khai báo rồi chạy `python docs/api/generator/generate.py`, sau đó `node docs/api/check-contracts.mjs`.

| Module | Mã | Owner | Endpoint | Đọc | Máy đọc |
|---|---|---|---|---|---|
| identity | TK, QT | BE-1 | 37 | [identity-v1.md](./identity-v1.md) | [openapi/identity-v1.yaml](./openapi/identity-v1.yaml) |
| customer | KH | BE-2 | 21 | [customer-v1.md](./customer-v1.md) | [openapi/customer-v1.yaml](./openapi/customer-v1.yaml) |
| catalog | SP (+ CK sản phẩm, dịch vụ) | BE-2 | 22 | [catalog-v1.md](./catalog-v1.md) | [openapi/catalog-v1.yaml](./openapi/catalog-v1.yaml) |
| branch | CN (+ UC33, UC42) | BE-2 | 21 | [branch-v1.md](./branch-v1.md) | [openapi/branch-v1.yaml](./openapi/branch-v1.yaml) |
| appointment | LH | BE-1 | 9 | [appointment-v1.md](./appointment-v1.md) | [openapi/appointment-v1.yaml](./openapi/appointment-v1.yaml) |
| visit | TN, KB | BE-1 | 21 | [visit-v1.md](./visit-v1.md) | [openapi/visit-v1.yaml](./openapi/visit-v1.yaml) |
| sales | BH, TG | BE-2 | 21 | [sales-v1.md](./sales-v1.md) | [openapi/sales-v1.yaml](./openapi/sales-v1.yaml) |
| inventory | KO | BE-2 | 17 | [inventory-v1.md](./inventory-v1.md) | [openapi/inventory-v1.yaml](./openapi/inventory-v1.yaml) |
| boarding | LT | BE-2 | 22 | [boarding-v1.md](./boarding-v1.md) | [openapi/boarding-v1.yaml](./openapi/boarding-v1.yaml) |
| content | CK (trang), BV, DG | BE-2 | 22 | [content-v1.md](./content-v1.md) | [openapi/content-v1.yaml](./openapi/content-v1.yaml) |
| care | TB | BE-1 | 10 | [care-v1.md](./care-v1.md) | [openapi/care-v1.yaml](./openapi/care-v1.yaml) |
| report | BC | BE-2 | 7 | [report-v1.md](./report-v1.md) | [openapi/report-v1.yaml](./openapi/report-v1.yaml) |
| **Tổng** | | | **230** | | |

## Tra nhanh theo use case

| Use case | File |
|---|---|
| UC01–UC11, sửa email khách / liên kết tại quầy (UC22), bác sĩ công khai (UC15) | identity |
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

Use case tầng 3 (INDEX Bảng 2) không có endpoint.
