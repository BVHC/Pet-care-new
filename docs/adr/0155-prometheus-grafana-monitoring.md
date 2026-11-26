# ADR-0155: Giám sát bằng Prometheus + Grafana (cổng management riêng, meter job / rate limit / nghiệp vụ)

- **Trạng thái:** Proposed
- **Ngày:** 2026-11-25
- **Liên quan:** ADR-0005 (path public luôn ẩn danh), ADR-0007 (job), ADR-0012 / 0017 / 0154 (meter outbox ST20), ADR-0100 / 0110 (rate limit), ADR-0130 (meter AI); convention 07 §7.2
- **Triển khai:**
  - BE: `pom.xml` (`micrometer-registry-prometheus`), `application.yml` / `application-docker.yml`, `platform/config/ManagementExposureGuard`, `platform/metrics/JobMetrics`, `platform/metrics/BusinessMetrics`.
  - Call site: `platform/security/{SecurityConfig, RateLimitFilter, RedisRateLimiter}`, 26 job `module/*/job/*Job.java` và `care/job/NotificationOutboxRunner`; `AppointmentBookingService`, `VisitCheckInService`, `VisitCompletionService`, `sales/service/PaymentService`.
  - Hạ tầng: `docker-compose.yml` (profile `monitoring`), `BE/Dockerfile`, `monitoring/`.

## Bối cảnh

BE đã có actuator và vài meter Micrometer (outbox ST20, AI) nhưng không nơi nào đọc được. Người dùng chốt ngày 25/11/2026:
- Mục tiêu: đủ để demo đồ án, và có đường lên máy thật mà không phải làm lại.
- Phạm vi: JVM / HTTP / DB pool, các meter đã có, Postgres + Redis exporter, cùng ba nhóm meter mới (kết quả job, rate limit, sự kiện nghiệp vụ).
- `/actuator/prometheus` chỉ mở trên cổng management riêng.
- Không làm cảnh báo, Loki, tracing hay cAdvisor.

## Quyết định

1. **Cổng management riêng chỉ ở profile docker.**
   - `management.server.port=9091` và expose `health,info,prometheus`, khai báo trong `application-docker.yml`.
   - Compose chỉ `expose` cổng này trong mạng `petcare-network`, không publish ra host.
   - Profile mặc định và profile test vẫn chỉ expose `health,info`.
2. **`/actuator/prometheus` nằm trong `SecurityConfig.PUBLIC_PATHS`.**
   - Spring Boot gắn cả chuỗi filter của app vào cổng management (`ServletManagementChildContextConfiguration$ServletManagementContextSecurityConfiguration`, Boot 3.5.15). Nếu không thêm, Prometheus nhận 401.
   - Trên cổng app không có endpoint này (404).
   - `ManagementExposureGuard` chặn khởi động khi `prometheus` được expose mà cổng management trùng cổng app (`ManagementPortType.SAME`). Nhờ vậy không có cấu hình nào làm số liệu lộ trên 8080.
3. **Probe trên cổng app.** Bật `management.endpoint.health.probes.enabled` (chỉ tự bật trên Kubernetes) và `add-additional-paths`, nên `/livez` và `/readyz` trả lời trên cổng app (thêm vào `PUBLIC_PATHS`).
   - Healthcheck của compose và Dockerfile gọi `localhost:9091/actuator/health` (vẫn gồm db / redis).
   - Smoke script ở ngoài host gọi `/readyz`.
4. **Nhãn chung và histogram.**
   - `management.metrics.tags.application=petcare-api`, trùng biến `application` của các dashboard cộng đồng.
   - Bật histogram cho `http.server.requests` (SLO 100 ms / 500 ms / 2 s) để tính p95.
5. **`JobMetrics`.**
   - Thời gian chạy đã có sẵn ở `tasks.scheduled.execution` (Boot tự bật). Nhưng job tự bắt lỗi rồi log `<NAME>_FAILED` (ADR-0007), nên meter đó luôn báo `SUCCESS`.
   - Mỗi job gọi `record(job, outcome, items)` cạnh dòng log tổng kết và trong `catch` ngoài cùng, tạo ra hai meter:
     - `petcare.job.runs{job, outcome=ok|failed|budget_stopped}`
     - `petcare.job.items{job, result}`, với `result` lấy đúng tên bộ đếm job đang log.
   - Tiêm bằng setter `@Autowired setJobMetrics`, mặc định là `JobMetrics.NOOP`. Nhờ vậy 43 chỗ test dựng job bằng tay không phải sửa.
   - `JobMetricsCoverageTest` quét mọi `@Scheduled` và đỏ khi một job thiếu setter hoặc thiếu lời gọi ở một trong hai nhánh. `PrometheusEndpointIT` kiểm mọi bean job đã nhận bean thật.
   - Ba job ST20 chỉ ủy quyền, nên `NotificationOutboxRunner` mang setter thay chúng và chỉ ghi `runs`: số dòng đã có ở `notification.outbox.result`.
   - Tên job là tên sự kiện log viết thường (`NO_SHOW` → `no_show`).
6. **Rate limit.**
   - `petcare.ratelimit.rejected{group, window}` ở nhánh 429 của `RateLimitFilter`.
   - `petcare.ratelimit.unavailable` mỗi lần `RedisRateLimiter` mở circuit fail-open (không đếm request đi qua khi circuit đang mở).
   - `SecurityConfig` lấy `MeterRegistry` qua `ObjectProvider`, rơi về `Metrics.globalRegistry`, vì slice `@WebMvcTest` không có bean này.
7. **`BusinessMetrics`: chỉ đếm sau commit.**
   - Callback `TransactionSynchronization.afterCommit` chỉ tăng counter: không đọc hay ghi DB, không phải hệ quả nghiệp vụ. Vì vậy không trái quy tắc "hệ quả trong cùng transaction" và convention 07 §7.2. Rollback thì không đếm.
   - Gọi ngoài transaction là lỗi lập trình (`IllegalStateException`).
   - Lỗi lúc tăng số sau commit chỉ được log (`BUSINESS_METRIC_FAILED`), không biến request đã thành công thành 500.
   - Các meter:
     - `petcare.appointment.booked{branch, channel}`
     - `petcare.visit.checked_in{branch, kind}`
     - `petcare.visit.completed{branch}`
     - `petcare.order.paid{branch, source}` và `petcare.order.paid.amount{branch, source}` (VND)
   - Tag chỉ dùng id chi nhánh và enum; không gắn id khách, thú hay người dùng, để số chuỗi số liệu không phình ra.
   - Đơn online thanh toán qua cổng (module `payment`, chưa có controller) chưa được đếm.
8. **Hạ tầng dưới `profiles: [monitoring]`.**
   - Năm service: `prometheus`, `grafana`, `monitoring-db-init`, `postgres-exporter`, `redis-exporter`, tag ảnh được ghim, có `mem_limit`.
   - `docker compose up` không có profile thì không chạy service nào trong số này (máy dev ít RAM).
   - Prometheus bind `127.0.0.1:9090` (không có đăng nhập). Grafana bind `${GRAFANA_BIND:-127.0.0.1}:3001`, với datasource và dashboard nạp từ file (chỉ đọc).
   - Mật khẩu có mặc định cho demo, vì compose nội suy biến của mọi service kể cả service có profile. Dùng `:?` thì `.env` cũ sẽ làm hỏng mọi lệnh `docker compose`.
9. **Role DB riêng.**
   - `monitoring-db-init` chạy `monitoring/postgres/monitor-role.sql` (idempotent, `\gexec`) mỗi lần bật profile, tạo / cập nhật `petcare_monitor` có `pg_monitor`.
   - Không thuộc Flyway (vận hành từng máy, không phải schema), nên DB cũ không cần thao tác tay.
10. **Dashboard.**
    - 4 dashboard cộng đồng (grafana.com 4701, 19004, 9628, 763) đã chuyển sang datasource cố định, chuyển bằng `monitoring/grafana/tools/prepare_dashboards.py`.
    - 1 dashboard "Pet Care · Tổng quan" sinh từ `tools/build_overview.py`.
11. **Đường lên máy thật** ghi ở `monitoring/README.md`: bí mật, reverse proxy + TLS, firewall cho 9091, retention / `remote_write`, tách compose project, chỗ thêm cảnh báo. Lên máy thật không phải sửa BE.

## Hệ quả

- `/actuator/prometheus` không cần token: an toàn nhờ cổng 9091 không ra khỏi mạng docker. Lên máy thật mà Prometheus ở host khác thì phải chặn 9091 bằng firewall (README mục 3).
- Thay đổi chạm 11 job và `PaymentService` của BE-2 (chỉ thêm lời gọi metrics; `GatewayRefundJob` thêm `try` ngoài cùng theo quy ước job). Đã ghi ở `docs/06-module-contracts.md` §6.
- Số nghiệp vụ reset khi BE khởi động lại (`increase()` xử lý được). Đây là số liệu vận hành, không thay báo cáo BC.
- Nợ còn lại: D088 (chưa có cảnh báo; dashboard cộng đồng phụ thuộc phiên bản Grafana; số liệu nghiệp vụ chưa có đơn online).

## Kiểm chứng

- Unit:
  - `JobMetricsTest`, `BusinessMetricsTest` (commit đếm, rollback không đếm, ngoài transaction ném lỗi), `ManagementExposureGuardTest` (10 cấu hình).
  - `JobMetricsCoverageTest`, `RateLimitFilterTest`, `RedisRateLimiterTest`, `NoShowJobTest`, `SecurityConfigTest`.
  - Test của 4 service nghiệp vụ: đúng tag ở nhánh thành công, không gọi ở nhánh bị từ chối.
- IT `PrometheusEndpointIT` (cần `@AutoConfigureObservability`: `@SpringBootTest` tắt metrics export mặc định):
  - Cổng management trả 200 không cần token, có `http_server_requests_seconds_bucket`, `jvm_*`, `hikaricp_*`, `notification_outbox_pending`.
  - Cổng app trả 404 cho `/actuator/prometheus`; `/readyz` và `/livez` trả 200; `/actuator/env`, `/metrics`, `/beans`, `/heapdump` không đọc được.
  - Mọi bean job nhận `JobMetrics` thật; `BusinessMetrics` đếm sau commit và bỏ qua rollback trên transaction manager thật.
- End-to-end: `docker compose --profile monitoring up -d --build`, 4 target UP, 5 dashboard nạp được (kết quả chạy ghi ở `docs/architecture/system-overview.md`).
