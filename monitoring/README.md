# Giám sát Pet Care: Prometheus + Grafana

Quyết định và lý do: [`docs/adr/0155-prometheus-grafana-monitoring.md`](../docs/adr/0155-prometheus-grafana-monitoring.md).

## Bật cho demo

```bash
cp .env.example .env          # một lần; đã có .env thì chép thêm khối "Giám sát" từ .env.example
docker compose --profile monitoring up -d --build
```

Không có `--profile monitoring` thì compose chạy như cũ, không có container giám sát nào.

| Gì | Ở đâu | Ghi chú |
|---|---|---|
| Grafana | http://localhost:3001 | `admin` / `GRAFANA_ADMIN_PASSWORD` (mặc định `petcare-grafana`). Trang chủ là **Pet Care · Tổng quan** |
| Prometheus | http://localhost:9090 | Chỉ máy này. `Status → Targets`: 4 target phải `UP` |
| Số liệu BE | `backend:9091/actuator/prometheus` | Chỉ trong mạng docker; từ host gọi không được (cố ý) |

Dashboard trong thư mục **Pet Care**:

| Dashboard | Nguồn | Xem gì |
|---|---|---|
| Pet Care · Tổng quan | tự vẽ (`tools/build_overview.py`) | HTTP (tốc độ, p95, lỗi), outbox ST20, job định kỳ, rate limit 429, AI, nghiệp vụ theo chi nhánh |
| Pet Care · JVM (Micrometer) | grafana.com 4701 | heap, GC, thread, class loading |
| Pet Care · Spring Boot 3.x | grafana.com 19004 | HTTP, HikariCP, Tomcat, log |
| Pet Care · PostgreSQL | grafana.com 9628 | kết nối, transaction, lock, cache hit |
| Pet Care · Redis | grafana.com 763 | bộ nhớ, lệnh / giây, key |

Muốn có dữ liệu nghiệp vụ để trình bày: chạy `python BE/scripts/smoke/t41_main_flow.py` (luồng đặt lịch → khám → thu tiền) trên
stack smoke, hoặc thao tác trên FE. Số đếm nghiệp vụ bắt đầu từ 0 mỗi lần BE khởi động; Prometheus giữ lịch sử.

## Tên số liệu

| Số liệu | Ý nghĩa |
|---|---|
| `http_server_requests_seconds_*` | mọi request API (có histogram để tính p95) |
| `petcare_job_runs_total{job, outcome}` | mỗi lượt chạy job: `ok`, `failed`, `budget_stopped` |
| `petcare_job_items_total{job, result}` | số bản ghi theo kết quả (marked, skipped, failed…) |
| `tasks_scheduled_execution_seconds_*` | thời gian chạy từng job (Spring tự đo) |
| `notification_outbox_*` | ST20: tồn đọng, độ trễ, kết quả gửi |
| `petcare_ratelimit_rejected_total{group, window}` | request bị 429 |
| `petcare_ratelimit_unavailable_total` | Redis lỗi, rate limit tạm tắt (fail-open) |
| `petcare_appointment_booked_total{branch, channel}` | lịch hẹn đặt (ONLINE / COUNTER) |
| `petcare_visit_checked_in_total{branch, kind}` | tiếp nhận (APPOINTMENT / WALK_IN) |
| `petcare_visit_completed_total{branch}` | Visit hoàn tất |
| `petcare_order_paid_total`, `petcare_order_paid_amount_total{branch, source}` | Order đã thu và số tiền (VND) |
| `ai_chat_result_total`, `ai_article_sync_total` | AI |

Số nghiệp vụ chỉ tăng **sau khi transaction commit**. Đây là số liệu vận hành; số liệu chính thức vẫn là báo cáo BC.

## Thêm số liệu mới

- Job mới: gọi `JobMetrics.record(...)` ở nhánh thành công và nhánh lỗi (có `@Autowired setJobMetrics`). `JobMetricsCoverageTest` đỏ nếu quên.
- Sự kiện nghiệp vụ mới: thêm hằng vào `platform/metrics/BusinessMetrics` rồi gọi `increment` / `add` trong `@Transactional` của use case. Tag chỉ dùng giá trị ít (id chi nhánh, enum), không dùng id khách / thú / người dùng.
- Sửa panel: sửa `tools/build_overview.py` rồi chạy
  `python monitoring/grafana/tools/build_overview.py monitoring/grafana/dashboards/petcare-overview.json`.
  Sửa trên giao diện Grafana không được lưu (provisioning chỉ đọc).

## Sự cố thường gặp

| Hiện tượng | Xử lý |
|---|---|
| Target `petcare-api` DOWN | BE chưa lên, hoặc BE không chạy profile `docker` (cổng 9091 chỉ mở ở profile này) |
| Target `postgres` DOWN, log exporter báo `password authentication failed` | Đã đổi `MONITOR_DB_PASSWORD`: chạy lại `docker compose --profile monitoring up -d monitoring-db-init postgres-exporter` |
| `monitoring-db-init` thoát mã khác 0 | Xem `docker logs petcare-monitoring-db-init`; thường do `DB_USERNAME` / `DB_PASSWORD` trong `.env` không khớp DB |
| Panel nghiệp vụ "No data" | Chưa có sự kiện nào kể từ lần BE khởi động; thao tác trên FE hoặc chạy smoke |
| Máy hết RAM | Tắt riêng giám sát: `docker compose --profile monitoring stop prometheus grafana postgres-exporter redis-exporter` |

## Lên máy thật

Cấu hình trong thư mục này dùng lại được; chỉ đổi phần vận hành:

1. **Bí mật:** đặt `GRAFANA_ADMIN_PASSWORD`, `MONITOR_DB_PASSWORD` mạnh trong `.env` (không commit). Redis bật mật khẩu (`REDIS_PASSWORD`, nợ D056) thì redis-exporter tự dùng.
2. **Truy cập Grafana:** để `GRAFANA_BIND=127.0.0.1` và đặt reverse proxy có TLS (nginx / Caddy) phía trước, hoặc bật đăng nhập SSO của Grafana. Không publish Prometheus (không có đăng nhập).
3. **Cổng 9091 của BE:** giữ không publish. Prometheus chạy ở host khác thì mở 9091 chỉ cho IP của Prometheus (firewall), vì `/actuator/prometheus` không cần token.
4. **Lưu trữ:** tăng `PROMETHEUS_RETENTION` (vd `30d`) và đĩa cho volume `prometheus_data`; cần lưu lâu hơn hoặc nhiều máy thì thêm `remote_write` trong `prometheus/prometheus.yml` tới Grafana Cloud / Mimir / VictoriaMetrics. Đổi `external_labels.environment`.
5. **Tách khỏi app:** có thể chạy 4 service giám sát bằng một compose project riêng trên máy khác; chỉ cần đổi `targets` trong `prometheus/prometheus.yml` sang địa chỉ thật.
6. **Cảnh báo (chưa làm, nợ D088):** thêm file trong `grafana/provisioning/alerting/` (Grafana alerting) hoặc Alertmanager + `rule_files`; BE không phải sửa. Gợi ý luật đầu tiên: BE DOWN 2 phút, 5xx > 5 % trong 5 phút, `notification_outbox_oldest_overdue_seconds` > 300, `petcare_job_runs_total{outcome="failed"}` tăng.
7. **Phiên bản ảnh:** đã ghim tag (`*_IMAGE_TAG` trong compose). Nâng cấp thì đổi tag, kiểm dashboard cộng đồng còn hiển thị đúng.
