package com.petcare.module.appointment.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.entity.Appointment;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    /**
     * Advisory lock theo transaction ({@code pg_advisory_xact_lock}, docs/adr/0041): tự nhả khi commit / rollback, không
     * bao giờ kẹt lại trên connection của pool. {@code MANDATORY}: method query của interface Spring Data không tự có
     * transaction, gọi ngoài transaction thì khóa được nhả ngay khi câu lệnh xong mà không báo lỗi. Chỉ gọi qua
     * {@code AppointmentLocks}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:namespace, :key)", nativeQuery = true)
    Integer acquireTransactionLock(@Param("namespace") int namespace, @Param("key") int key);

    /** Số kế tiếp của {@code appointment_code_seq} (V13, docs/adr/0041). Chỉ gọi qua {@code AppointmentCodeGenerator}. */
    @Query(value = "SELECT nextval('appointment_code_seq')", nativeQuery = true)
    long nextCodeValue();

    /** Số lịch đang chiếm chỗ của một khung; {@code secondOfDay} là giờ bắt đầu tính bằng giây từ 00:00. */
    interface SlotBookedCount {

        int getSecondOfDay();

        int getBooked();
    }

    /**
     * Số lịch BOOKED / CHECKED_IN / COMPLETED theo từng khung của một chi nhánh × nhóm × ngày (BR-LH-03; xem khung trống,
     * docs/adr/0042). Trạng thái để <b>literal</b> để planner dùng được partial index {@code ix_appointments_quota}
     * (V1); giờ trả dạng số giây để không phụ thuộc kiểu Java mà Hibernate trả cho cột {@code TIME} của native query.
     * Không khóa: chỉ để hiển thị.
     */
    @Query(value = """
            SELECT CAST(EXTRACT(EPOCH FROM slot_start) AS integer) AS secondOfDay, CAST(count(*) AS integer) AS booked
            FROM appointments
            WHERE branch_id = :branchId AND service_group = :serviceGroup AND slot_date = :slotDate
              AND status IN ('BOOKED', 'CHECKED_IN', 'COMPLETED')
            GROUP BY slot_start
            """, nativeQuery = true)
    List<SlotBookedCount> countActiveBySlot(@Param("branchId") Long branchId, @Param("serviceGroup") String serviceGroup,
            @Param("slotDate") LocalDate slotDate);
}
