package com.petcare.module.appointment.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.api.AppointmentQueryApi.BookedSlot;
import com.petcare.module.appointment.entity.Appointment;

import jakarta.persistence.LockModeType;

/**
 * {@link JpaSpecificationExecutor}: danh sách lịch hẹn (appointment-v1 #3, docs/adr/0048) chỉ ghép điều kiện được gửi,
 * không bao giờ {@code :x IS NULL OR} (khuôn {@code catalog/service/ProductService}).
 */
public interface AppointmentRepository extends JpaRepository<Appointment, Long>, JpaSpecificationExecutor<Appointment> {

    /**
     * Advisory lock theo transaction ({@code pg_advisory_xact_lock}, docs/adr/0045): tự nhả khi commit / rollback, không
     * bao giờ kẹt lại trên connection của pool. {@code MANDATORY}: method query của interface Spring Data không tự có
     * transaction, gọi ngoài transaction thì khóa được nhả ngay khi câu lệnh xong mà không báo lỗi. Chỉ gọi qua
     * {@code AppointmentLocks}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:namespace, :key)", nativeQuery = true)
    Integer acquireTransactionLock(@Param("namespace") int namespace, @Param("key") int key);

<<<<<<< HEAD
    /** Số kế tiếp của {@code appointment_code_seq} (V16, docs/adr/0045). Chỉ gọi qua {@code AppointmentCodeGenerator}. */
=======
    /** Số kế tiếp của {@code appointment_code_seq} (V16, docs/adr/0041). Chỉ gọi qua {@code AppointmentCodeGenerator}. */
>>>>>>> e18c4ed (feat: triển khai hủy do phòng khám)
    @Query(value = "SELECT nextval('appointment_code_seq')", nativeQuery = true)
    long nextCodeValue();

    /** Số lịch đang chiếm chỗ của một khung; {@code secondOfDay} là giờ bắt đầu tính bằng giây từ 00:00. */
    interface SlotBookedCount {

        int getSecondOfDay();

        int getBooked();
    }

    /**
     * Số lịch BOOKED / CHECKED_IN / COMPLETED theo từng khung của một chi nhánh × nhóm × ngày (BR-LH-03; xem khung trống,
     * docs/adr/0046). Trạng thái để <b>literal</b> để planner dùng được partial index {@code ix_appointments_quota}
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

    // Bốn câu dưới phục vụ đặt lịch (docs/adr/0047) và chỉ đúng khi chạy dưới khóa thú + khóa chi nhánh (docs/adr/0045):
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

    // Đổi khung / hủy (docs/adr/0049).

    /** Ba cột không bao giờ đổi của một lịch ({@code updatable = false}): đủ để phân quyền trước khi khóa. */
    interface AccessRow {

        Long getCustomerId();

        Long getPetId();

        Long getBranchId();
    }

    /**
     * Đọc không khóa, dạng projection (không nạp entity vào persistence context): {@link #findByIdForUpdate} sau đó luôn
     * trả bản mới đọc dưới khóa, không trả lại một entity cũ (bẫy docs/adr/0029 §4).
     */
    @Query("SELECT a.customerId AS customerId, a.petId AS petId, a.branchId AS branchId FROM Appointment a WHERE a.id = :id")
    Optional<AccessRow> findAccessRow(@Param("id") Long id);

    /**
     * Khóa dòng lịch hẹn ({@code FOR NO KEY UPDATE} — Hibernate 6 với {@code PESSIMISTIC_WRITE}), khóa cuối của thứ tự
     * {@code advisory(thú) → branches → appointments} (docs/adr/0045, 0049). {@code MANDATORY}: ngoài transaction thì
     * khóa được nhả ngay khi câu lệnh xong.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Appointment a WHERE a.id = :id")
    Optional<Appointment> findByIdForUpdate(@Param("id") Long id);

    /**
     * Khóa nhiều lịch hẹn trong <b>một</b> câu, theo {@code id} tăng dần (Lịch hẹn#5, docs/adr/0047): mọi luồng khóa
     * nhiều dòng {@code appointments} đều theo thứ tự này nên không tạo vòng chờ. {@code FOR NO KEY UPDATE} như
     * {@link #findByIdForUpdate}; khóa cuối của {@code branches → appointments} và {@code customers → pets →
     * appointments}. Bên gọi khóa tối đa 100 id mỗi câu và không truyền tập rỗng; id không có thì không có dòng.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Appointment a WHERE a.id IN :ids ORDER BY a.id")
    List<Appointment> findAllByIdInForUpdate(@Param("ids") Collection<Long> ids);

    /**
     * Mã các lịch {@code BOOKED} của thú, không khóa, chỉ lấy id (không nạp entity vào persistence context — bước khóa
     * sau đó đọc trạng thái mới nhất). Thú mất / chuyển chủ (BR-KH-05, BR-KH-08; docs/adr/0047). Index
     * {@code ix_appointments_pet_status}.
     */
    @Query("""
            SELECT a.id FROM Appointment a
            WHERE a.petId = :petId AND a.status = com.petcare.module.appointment.entity.AppointmentStatus.BOOKED
            ORDER BY a.id
            """)
    List<Long> findBookedIdsByPetId(@Param("petId") Long petId);

    /** Như {@link #existsActiveSameSlot} nhưng không tính lịch {@code excludeId} (đổi khung của chính lịch đó, BR-LH-05). */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM appointments
                WHERE pet_id = :petId AND status IN ('BOOKED', 'CHECKED_IN', 'COMPLETED')
                  AND slot_date = :slotDate AND slot_start = :slotStart AND id <> :excludeId)
            """, nativeQuery = true)
    boolean existsActiveSameSlotExcept(@Param("petId") Long petId, @Param("slotDate") LocalDate slotDate,
            @Param("slotStart") LocalTime slotStart, @Param("excludeId") Long excludeId);

    /** Như {@link #existsActiveSameGroupDay} nhưng không tính lịch {@code excludeId} (BR-LH-05). */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM appointments
                WHERE pet_id = :petId AND status IN ('BOOKED', 'CHECKED_IN', 'COMPLETED')
                  AND service_group = :serviceGroup AND slot_date = :slotDate AND id <> :excludeId)
            """, nativeQuery = true)
    boolean existsActiveSameGroupDayExcept(@Param("petId") Long petId, @Param("serviceGroup") String serviceGroup,
            @Param("slotDate") LocalDate slotDate, @Param("excludeId") Long excludeId);

    /**
     * Số vi phạm BR-LH-09 của khách từ lịch hẹn, xảy ra <b>sau</b> {@code since} (loại trừ, docs/adr/0049): hủy muộn tính
     * theo {@code cancelled_at}; {@code NO_SHOW} tính theo giờ hẹn (bảng không có cột thời điểm NO_SHOW). Phải chạy dưới
     * khóa khách ({@code AppointmentLocks.lockCustomerViolations}), READ COMMITTED: thấy mọi vi phạm đã commit trước khi
     * khóa được nhả.
     */
    @Query(value = """
            SELECT CAST(count(*) AS integer) FROM appointments
            WHERE customer_id = :customerId
              AND ((status = 'CANCELLED' AND late_cancel AND cancelled_at > :since)
                OR (status = 'NO_SHOW' AND (slot_date + slot_start) AT TIME ZONE 'Asia/Ho_Chi_Minh' > :since))
            """, nativeQuery = true)
    int countViolationsAfter(@Param("customerId") Long customerId, @Param("since") Instant since);

<<<<<<< HEAD
    // AppointmentQueryApi (docs/adr/0050): chỉ đọc, JPQL constructor để không nạp entity vào persistence context của
=======
    // ST05 NO_SHOW (docs/adr/0048). Trạng thái để literal để dùng index ix_appointments_status_slot (V1).

    /**
     * Id các lịch {@code BOOKED} đã quá hạn NO_SHOW (khung < mốc), có {@code id > afterId}, tăng dần theo id (keyset: lịch
     * lỗi vẫn BOOKED nên không đọc lại trong cùng lượt). {@code cutoffTime} là chuỗi {@code LocalTime.toString()}: bind
     * {@code LocalTime} vào native query mất độ chính xác dưới mili giây (IT chứng minh biên 1 µs). Không khóa; {@link #lockOverdueBooked} kiểm lại dưới khóa.
     */
    @Query(value = """
            SELECT id FROM appointments
            WHERE status = 'BOOKED' AND (slot_date, slot_start) < (CAST(:cutoffDate AS date), CAST(:cutoffTime AS time))
              AND id > :afterId
            ORDER BY id LIMIT :limit
            """, nativeQuery = true)
    List<Long> findOverdueBookedIds(@Param("cutoffDate") LocalDate cutoffDate, @Param("cutoffTime") String cutoffTime,
            @Param("afterId") long afterId, @Param("limit") int limit);

    /**
     * Khóa dòng ({@code FOR NO KEY UPDATE SKIP LOCKED}) nếu lịch <b>vẫn</b> {@code BOOKED} và quá hạn: check-in, hủy,
     * hủy do phòng khám có thể đã commit sau {@link #findOverdueBookedIds}; dòng đang bị luồng khác giữ thì bỏ qua, lượt
     * sau xét lại. Rỗng = không làm gì. {@code MANDATORY}: ngoài transaction khóa nhả ngay.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT id FROM appointments
            WHERE id = :id AND status = 'BOOKED'
              AND (slot_date, slot_start) < (CAST(:cutoffDate AS date), CAST(:cutoffTime AS time))
            FOR NO KEY UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Long> lockOverdueBooked(@Param("id") long id, @Param("cutoffDate") LocalDate cutoffDate,
            @Param("cutoffTime") String cutoffTime);

    // AppointmentQueryApi (docs/adr/0046): chỉ đọc, JPQL constructor để không nạp entity vào persistence context của
>>>>>>> a32bf1b (feat: triển khai job appointment/job/NoShowJob)
    // bên gọi; một câu SQL bất kể số dòng (Appointment không có quan hệ JPA, FK là Long).

    /** Lịch BOOKED của chi nhánh từ {@code fromDate} (BR-CN-04, BR-LH-10). Index {@code ix_appointments_status_slot}. */
    @Query("""
            SELECT new com.petcare.module.appointment.api.AppointmentQueryApi$BookedSlot(
                a.id, a.code, a.branchId, a.petId, a.customerId, a.slotDate, a.slotStart)
            FROM Appointment a
            WHERE a.branchId = :branchId AND a.status = com.petcare.module.appointment.entity.AppointmentStatus.BOOKED
              AND a.slotDate >= :fromDate
            ORDER BY a.slotDate, a.slotStart, a.id
            """)
    List<BookedSlot> findBookedFrom(@Param("branchId") Long branchId, @Param("fromDate") LocalDate fromDate);

    /** Lịch BOOKED của khách trên mọi chi nhánh (BR-QT-11). Index {@code ix_appointments_customer_created_at}. */
    @Query("""
            SELECT new com.petcare.module.appointment.api.AppointmentQueryApi$BookedSlot(
                a.id, a.code, a.branchId, a.petId, a.customerId, a.slotDate, a.slotStart)
            FROM Appointment a
            WHERE a.customerId = :customerId
              AND a.status = com.petcare.module.appointment.entity.AppointmentStatus.BOOKED
            ORDER BY a.slotDate, a.slotStart, a.id
            """)
    List<BookedSlot> findBookedByCustomerId(@Param("customerId") Long customerId);

    /** Thú đã từng có lịch hẹn ở bất kỳ trạng thái nào (BR-KH-06). */
    boolean existsByPetId(Long petId);

    /** Khách đã từng có lịch hẹn ở bất kỳ trạng thái nào (BR-TK-19). */
    boolean existsByCustomerId(Long customerId);
}
