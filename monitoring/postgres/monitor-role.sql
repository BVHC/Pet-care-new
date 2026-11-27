-- Role chỉ-đọc cho postgres-exporter (docs/adr/0155). Chạy lại được nhiều lần: service `monitoring-db-init` của
-- docker-compose chạy file này mỗi lần bật profile monitoring, nên DB đã có sẵn dữ liệu cũng không cần thao tác tay.
-- Không thuộc Flyway: role là việc vận hành của từng máy, không phải schema của ứng dụng (test không chạy file này).
-- Biến psql: monitor_password (truyền bằng -v).

SELECT format('CREATE ROLE petcare_monitor LOGIN PASSWORD %L', :'monitor_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'petcare_monitor')
\gexec

ALTER ROLE petcare_monitor WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'monitor_password';
GRANT pg_monitor TO petcare_monitor;
