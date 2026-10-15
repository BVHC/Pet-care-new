package com.petcare.module.appointment.repository;

import java.time.LocalDate;
import java.time.LocalTime;
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

    // Bốn câu dưới phục vụ đặt lịch (docs/adr/0043) và chỉ đúng khi chạy dưới khóa thú + khóa chi nhánh (docs/adr/0041):
    // READ COMMITTED, mỗi câu thấy mọi lượt đặt đã commit trước khi khóa được nhả. Trạng thái để literal (planner).

    /** Số lịch {@code BOOKED} của thú trên mọi chi nhánh (BR-LH-05: tối đa 2 [CFG]). Index {@code ix_appointments_pet_status}. */
    @Query(value = """
            SELECT CAST(count(*) AS integer) FROM appointments WHERE pet_id = :petId AND status = 'BOOKED'
            """, nativeQuery = true)
    int countBookedByPet(@Param("petId") Long petId);

    /** Thú đã có lịch BOOKED / CHECKED_IN / COMPLETED đúng khung này, mọi nhóm, mọi chi nhánh (BR-LH-05, Q3). */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM appointments
                WHERE pet_id = :petId AND status IN ('BOOKED', 'CHECKED_IN', 'COMPLETED')
                  AND slot_date = :slotDate AND slot_start = :slotStart)
            """, nativeQuery = true)
    boolean existsActiveSameSlot(@Param("petId") Long petId, @Param("slotDate") LocalDate slotDate,
            @Param("slotStart") LocalTime slotStart);

    /** Thú đã có lịch BOOKED / CHECKED_IN / COMPLETED cùng nhóm trong ngày, mọi chi nhánh (BR-LH-05, Q3). */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM appointments
                WHERE pet_id = :petId AND status IN ('BOOKED', 'CHECKED_IN', 'COMPLETED')
                  AND service_group = :serviceGroup AND slot_date = :slotDate)
            """, nativeQuery = true)
    boolean existsActiveSameGroupDay(@Param("petId") Long petId, @Param("serviceGroup") String serviceGroup,
            @Param("slotDate") LocalDate slotDate);

    /** Số lịch đang chiếm chỗ của một khung chi nhánh × nhóm (BR-LH-03). Index {@code ix_appointments_quota}. */
    @Query(value = """
            SELECT CAST(count(*) AS integer) FROM appointments
            WHERE branch_id = :branchId AND service_group = :serviceGroup AND slot_date = :slotDate
              AND slot_start = :slotStart AND status IN ('BOOKED', 'CHECKED_IN', 'COMPLETED')
            """, nativeQuery = true)
    int countActiveInSlot(@Param("branchId") Long branchId, @Param("serviceGroup") String serviceGroup,
            @Param("slotDate") LocalDate slotDate, @Param("slotStart") LocalTime slotStart);
}
