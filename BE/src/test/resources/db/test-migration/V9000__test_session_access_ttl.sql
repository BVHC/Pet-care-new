-- Chỉ chạy ở profile test (spring.flyway.locations của application-test.yml), không bao giờ ở production.
-- Access token mặc định sống 10 phút (V27), nhưng 70+ IT dịch MutableClock hàng giờ / hàng ngày sau khi mở phiên.
-- Baseline test = 720 phút (bằng hạn phiên mặc định) để các IT đó không đổi hành vi; IT kiểm hạn access token
-- (RefreshTokenIT) tự đặt 10 rồi trả lại 720. Xem docs/adr/0091.
UPDATE system_configs SET value = '720' WHERE key = 'session.access_ttl_minutes';
