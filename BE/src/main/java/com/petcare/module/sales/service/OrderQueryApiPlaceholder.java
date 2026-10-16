package com.petcare.module.sales.service;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.visit.api.VisitQueryApi;

/**
 * Giữ chỗ cho {@link OrderQueryApi} tới khi module sales (BE-2) cài thật — nợ D001/D010 phía khách (docs/dept/INDEX.md).
 * BE-1 tạo thay owner (2026-10-12, docs/adr/0035) vì khóa tài khoản khách (identity-v1 #29) là bên gọi đầu tiên: thiếu
 * bean thì app không khởi động. Theo 06-module-contracts §1: mọi method ném lỗi, không trả giá trị "an toàn" (danh sách
 * Order chưa thanh toán rỗng sẽ làm lễ tân không được báo việc dở, 06 §8 Q2). Hệ quả: khóa tài khoản khách trả 500 trên
 * app thật tới khi có bản thật (vốn đã 500 vì {@code CustomerQueryApi} còn placeholder). BE-2 xóa lớp này (cùng
 * {@code OrderQueryApiPlaceholderTest}) khi thêm implementation thật — <b>không</b> tạo placeholder thứ hai (trùng bean).
 * <p>
 * <b>Cài thật một phần (2026-10-16, T17):</b> {@code existsByCustomer} (BR-TK-19) và {@code hasPendingOrderForPet}
 * (BR-KH-08) đọc thẳng {@code orders}; {@code findUnpaidOrdersByCustomer} vẫn ném.
 */
@Service
public class OrderQueryApiPlaceholder implements OrderQueryApi {

    static final String MESSAGE = "OrderQueryApi chưa được cài (module sales, BE-2)";

    private final JdbcTemplate jdbc;
    private final VisitQueryApi visits;
    private final BoardingQueryApi boardings;

    public OrderQueryApiPlaceholder(JdbcTemplate jdbc, VisitQueryApi visits, BoardingQueryApi boardings) {
        this.jdbc = jdbc;
        this.visits = visits;
        this.boardings = boardings;
    }

    @Override
    public boolean existsByCustomer(Long customerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM orders WHERE customer_id = ?)", Boolean.class, customerId));
    }

    @Override
    public boolean hasPendingOrderForPet(Long petId) {
        List<Long> visitIds = visits.findVisitIdsByPet(petId);
        List<Long> bookingIds = boardings.findBookingIdsByPet(petId);
        return pendingOrderExists("visit_id", visitIds) || pendingOrderExists("boarding_booking_id", bookingIds);
    }

    /** Order nguồn VISIT / BOARDING luôn kèm Visit / đặt chỗ của thú (06 §4); danh sách rỗng thì không có Order. */
    private boolean pendingOrderExists(String column, List<Long> ids) {
        if (ids.isEmpty()) {
            return false;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM orders WHERE status = 'PENDING' AND " + column + " IN (" + marks + "))",
                Boolean.class, ids.toArray()));
    }

    @Override
    public List<UnpaidOrderRef> findUnpaidOrdersByCustomer(Long customerId) {
        throw new UnsupportedOperationException(MESSAGE);
    }
}
