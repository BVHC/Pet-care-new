package com.petcare.module.sales.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.petcare.module.sales.entity.Payment;
import com.petcare.module.sales.entity.PaymentMethod;

/** Phiếu thu (docs/adr/0064). */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** Số phiếu thu của mỗi ca. */
    interface ShiftCount {
        Long getShiftId();

        long getCount();
    }

    /** Tổng tiền một ca theo phương thức. */
    interface MethodTotal {
        PaymentMethod getMethod();

        long getTotal();
    }

    /** Dùng {@code ix_payments_shift_method}; phương thức không có phiếu thì không xuất hiện. */
    @Query("SELECT p.method AS method, COALESCE(SUM(p.amount), 0) AS total FROM Payment p "
            + "WHERE p.cashierShiftId = :shiftId GROUP BY p.method")
    List<MethodTotal> totalsByMethod(@Param("shiftId") Long shiftId);

    /** Một câu cho cả trang ca; ca không có phiếu thu không xuất hiện. */
    @Query("SELECT p.cashierShiftId AS shiftId, count(p) AS count FROM Payment p "
            + "WHERE p.cashierShiftId IN :ids GROUP BY p.cashierShiftId")
    List<ShiftCount> countByShiftIds(@Param("ids") Collection<Long> ids);
}
