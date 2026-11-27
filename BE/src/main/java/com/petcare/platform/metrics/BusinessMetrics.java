package com.petcare.platform.metrics;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.core.instrument.MeterRegistry;

import lombok.extern.slf4j.Slf4j;

/**
 * Đếm sự kiện nghiệp vụ cho Prometheus (docs/adr/0155). Số chỉ tăng <b>sau khi transaction của use case commit</b>:
 * rollback thì không đếm, nên dashboard không thấy lịch hẹn / thanh toán chưa từng tồn tại. Đây là số liệu, không phải
 * hệ quả nghiệp vụ, nên không trái quy tắc "hệ quả chạy trong cùng transaction"; callback không đọc hay ghi DB.
 *
 * <p>Gọi bên trong {@code @Transactional}; gọi ngoài transaction là lỗi lập trình ({@link IllegalStateException}).
 * Tag chỉ dùng giá trị có số lượng nhỏ (id chi nhánh, enum); không gắn id khách, thú, người dùng.
 */
@Slf4j
@Component
public class BusinessMetrics {

    /** Lịch hẹn được đặt; tag {@code branch}, {@code channel} ({@code ONLINE} khách tự đặt, {@code COUNTER} lễ tân). */
    public static final String APPOINTMENT_BOOKED = "petcare.appointment.booked";
    /** Visit được tạo khi tiếp nhận; tag {@code branch}, {@code kind} ({@code APPOINTMENT} | {@code WALK_IN}). */
    public static final String VISIT_CHECKED_IN = "petcare.visit.checked_in";
    /** Visit hoàn tất (Visit#5); tag {@code branch}. */
    public static final String VISIT_COMPLETED = "petcare.visit.completed";
    /** Order chuyển {@code PAID} (Order#8); tag {@code branch}, {@code source}. */
    public static final String ORDER_PAID = "petcare.order.paid";
    /** Tiền thu (VND) của các Order {@code PAID}; tag {@code branch}, {@code source}. */
    public static final String ORDER_PAID_AMOUNT = "petcare.order.paid.amount";

    private final MeterRegistry meters;

    public BusinessMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    /** Tăng {@code name} thêm 1 sau khi transaction hiện tại commit. {@code tags} là cặp khóa, giá trị. */
    public void increment(String name, String... tags) {
        add(name, 1, tags);
    }

    /** Cộng {@code amount} vào {@code name} sau khi transaction hiện tại commit. */
    public void add(String name, double amount, String... tags) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("BusinessMetrics chỉ dùng bên trong transaction: " + name);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    meters.counter(name, tags).increment(amount);
                } catch (RuntimeException ex) {
                    // Đã commit: lỗi số liệu không được biến request thành công thành 500.
                    log.warn("BUSINESS_METRIC_FAILED name={} ({})", name, ex.toString());
                }
            }
        });
    }
}
