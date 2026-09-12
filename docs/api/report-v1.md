# Report API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Reporting & Analytics (Module 24): 10 view báo cáo theo scope
> + đối soát doanh thu. Read-only — không có write endpoint nào (đúng bản chất
> read model).
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#24`), `docs/02-business-rules.md`
> (RULE-24-01→08), `docs/04-glossary.md` (`24`), `docs/05-domain-model.md` (`4.24`).
> Không ERD riêng (read model CONFIRMED) — shape metric PROPOSED + TBD.
> **Contract máy đọc:** [`./openapi/report-v1.yaml`](./openapi/report-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling` (cho lỗi; `204` không dùng
ở đây vì reports luôn trả body).

**Đóng băng phạm vi:**
- Không write endpoint: reports là read model trên dữ liệu các module khác.
- Metric sets chi tiết từng báo cáo TBD Q7 — v1 dùng 1 response generic
  `{period, metrics{}, rows[]}` thay vì phát minh 10 schemas (YAGNI).
- Báo cáo lớn chạy nền/replica (RULE-24-08): v1 giữ sync + giới hạn kỳ (A1);
  async export TBD Q8, không phát minh job endpoints.

---

## A. Confirmed Report API (10 endpoints, all GET)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `GET /reports/revenue` | `ViewRevenueReport` (Store) — `01#24`, RULE-24-01 |
| 2 | `GET /reports/appointments` | `ViewAppointmentReport` — `01#24`, RULE-24-01 |
| 3 | `GET /reports/services` | `ViewServiceReport` — `01#24`, RULE-24-01 |
| 4 | `GET /reports/inventory` | `ViewInventoryReport` — `01#24`, RULE-24-01 |
| 5 | `GET /reports/staff` | `ViewStaffReport` — `01#24`, RULE-24-01 |
| 6 | `GET /reports/organization-revenue` | `ViewOrganizationRevenue` (tổng chuỗi) — `01#24`, RULE-24-01/03 |
| 7 | `GET /reports/store-comparison` | `CompareStoreRevenue` (cùng Org) — `01#24`, RULE-24-04 |
| 8 | `GET /reports/customer-pet` | `ViewCustomerPetReport` (gated privacy) — `01#24`, RULE-24-06 |
| 9 | `GET /reports/revenue/reconciliation` | `ReconcileRevenue` (3 nguồn khớp) — `01#24`, RULE-24-05 |
| 10 | `GET /reports/platform` | `ViewPlatformReport` — `01#24`, RULE-24-01 |

---

## B. API Design Matrix (gộp — cùng guards)

| Nhóm | Actor | Scope CONFIRMED (RULE-24-01) | Endpoints |
|---|---|---|---|
| Store reports (1–5) | StoreManager (+ Finance/Inventory staff theo chuyên môn — A2) | Store mình | revenue/appointments/services/inventory/staff |
| Org reports (6–8) | OrganizationAdmin (+ FinanceStaff) | Org mình (cách ly 100% — RULE-24-02) | organization-revenue, store-comparison (cùng Org — RULE-24-04), customer-pet (gated RULE-24-06) |
| Reconciliation (9) | FinanceStaff | Store/Org mình | Công thức CONFIRMED: `Net = Σ PAID invoices − Σ refunded` (RULE-24-05) |
| Platform (10) | SuperAdmin | Toàn nền tảng | platform overview |

Params chung PROPOSED: `{from (req), to (req), storeId?}` + `page/pageSize` cho rows.
Guards chung: sai scope → `403`; cross-Org → `403` (RULE-24-02); kỳ đã khóa sổ →
snapshot bất biến (RULE-24-07 — đọc, không cần param); kỳ dài → `413`? Không —
giới hạn kỳ sync `≤93 ngày` (A1) + async TBD Q8.

**ASSUMPTIONS dùng chung:** A1 kỳ sync tối đa 93 ngày (docs không số — chống nghẽn
OLTP theo tinh thần RULE-24-08) · A2 staff chuyên môn xem báo cáo Store mình
(RULE-24-01 liệt kê role theo scope: FINANCE xem revenue, INVENTORY xem kho —
suy trực tiếp) · A3 metric sets TBD Q7 nên response generic (không phát minh 10 schemas).

---

## C. Detailed endpoint contract

### C1. Reads (proposed, chung 1 shape)

- Mỗi endpoint: GET + `{from (req, date), to (req, date), storeId?}`.
  `store-comparison` thêm `{storeIds?}` (mặc định all-store Org — A4).
  `customer-pet` tuân privacy M22 (thiếu consent/chính sách → `403` PROPOSED
  `PRIVACY_BLOCKED`).
- Response chung `200 {report, period: {from, to}, scope: {type, id}, metrics: {},
  rows: []}` — `metrics`/`rows` nội dung TBD Q7 (v1 BE tự map từ nguồn).
- `revenue/reconciliation` response kèm 3 nguồn CONFIRMED
  `{paidInvoicesTotal, successPaymentsTotal, refundedTotal, netRevenue}` (công thức
  RULE-24-05) — 4 số này CONFIRMED có, còn lại TBD Q7.
- **Status:** `200` · `400` (kỳ sai/quá 93 ngày — A1) · `401` · `403` · `404`.

---

## D. Security & reliability

1. Scope + Org isolation ở tầng query (không dựa vào FE chọn store) — test cross-Org.
2. Báo cáo tài chính kỳ khóa sổ đọc snapshot bất biến (RULE-24-07).
3. Báo cáo lớn không đụng OLTP (RULE-24-08) — giới hạn kỳ v1, async TBD.

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | Kỳ tối đa sync (A1: 93 ngày)? | TBD (BE) | RULE-24-08 |
| Q4 | `storeIds` compare tối đa mấy store? | TBD (BE) | docs không nêu |
| Q5 | `PRIVACY_BLOCKED` khi nào (thiếu consent nào)? | TBD (PO) | RULE-24-06 |
| Q6 | Reconciliation chênh lệch có sinh cảnh báo/task không? | TBD (PO) | RULE-24-05 |
| Q7 | Metric sets chi tiết từng báo cáo? | TBD (PO) | docs không liệt kê |
| Q8 | Async export (format, notify khi xong)? | TBD (BE) | RULE-24-08 |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/report-v1.yaml`](./openapi/report-v1.yaml).
