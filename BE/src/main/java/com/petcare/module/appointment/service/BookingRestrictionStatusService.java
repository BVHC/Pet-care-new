package com.petcare.module.appointment.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.dto.RestrictionStatusResponse;
import com.petcare.module.appointment.entity.BookingRestriction;
import com.petcare.module.appointment.mapper.BookingRestrictionMapper;
import com.petcare.module.appointment.repository.BookingRestrictionRepository;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * Xem và gỡ sớm hạn chế đặt online — appointment-v1 #7–9 (BR-LH-09; docs/adr/0050).
 * <ul>
 * <li>#7 {@link #getMyStatus}: hồ sơ của chính token, không nhận tham số khách.</li>
 * <li>#8 {@link #getCustomerStatus}, #9 {@link #liftRestriction}: lễ tân / BRANCH_MANAGER, <b>toàn chuỗi</b> — hạn chế gắn
 * với khách, không có {@code branch_id}, nên không qua {@code BranchScope.resolve/check}.</li>
 * </ul>
 * "Đang hiệu lực" luôn là {@link BookingRestrictionRepository#findActive} — cùng điều kiện với guard đặt online, nên
 * {@code restricted} ở đây khớp kết quả đặt lịch.
 * <p>
 * Gỡ sớm: một transaction, READ COMMITTED (bắt buộc — xem {@link #liftRestriction}), không {@code noRollbackFor}, không
 * bắt lỗi. Lần đánh giá vi phạm sau vẫn đếm từ {@code starts_at} của lần vừa gỡ (docs/adr/0045 §5): gỡ không làm vi phạm
 * cũ bị tính lại (đã xác nhận 2026-10-20 — docs/adr/0052).
 */
@Service
public class BookingRestrictionStatusService {

    private static final String RESTRICTION = "hạn chế đặt online";

    private final BookingRestrictionRepository restrictions;
    private final AppointmentLocks locks;
    private final CustomerQueryApi customers;
    private final BranchScope branchScope;
    private final AuditRecorder audit;
    private final BookingRestrictionMapper mapper;
    private final Clock clock;

    public BookingRestrictionStatusService(BookingRestrictionRepository restrictions, AppointmentLocks locks,
            CustomerQueryApi customers, BranchScope branchScope, AuditRecorder audit, BookingRestrictionMapper mapper,
            Clock clock) {
        this.restrictions = restrictions;
        this.locks = locks;
        this.customers = customers;
        this.branchScope = branchScope;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * #7. Khách đang đăng nhập; tài khoản khách không có hồ sơ là dữ liệu hỏng → 500 (như docs/adr/0044). Không chặn khi
     * hồ sơ còn cờ chờ liên kết: chỉ đọc (docs/adr/0028).
     */
    @Transactional(readOnly = true)
    public RestrictionStatusResponse getMyStatus() {
        Long accountId = branchScope.current().accountId();
        Long customerId = customers.findCustomerIdByAccountId(accountId)
                .orElseThrow(() -> new IllegalStateException("Tài khoản khách #" + accountId + " không có hồ sơ khách"));
        return status(customerId, clock.instant());
    }

    /** #8. Khách không tồn tại → 404. */
    @Transactional(readOnly = true)
    public RestrictionStatusResponse getCustomerStatus(Long customerId) {
        Objects.requireNonNull(customerId, "customerId");
        requireCustomer(customerId);
        return status(customerId, clock.instant());
    }

    /**
     * #9. Thứ tự (docs/adr/0050):
     * <ol>
     * <li>khách tồn tại (đọc không khóa) → 404 — trước khóa, vì khóa từ chối id ≤ 0 bằng lỗi 500;</li>
     * <li>advisory <b>1002</b> theo khách, cùng khóa với {@code evaluateAfterViolation}: gỡ và đánh giá vi phạm của cùng
     * khách chạy tuần tự. Đây là khóa duy nhất của luồng, nên 1002 vẫn là khóa cuối (docs/adr/0045);</li>
     * <li>{@code now} lấy <b>sau</b> khóa, cắt µs như cột {@code TIMESTAMPTZ};</li>
     * <li>dòng hiệu lực đọc sau khóa — phải READ COMMITTED: câu đọc là câu mới sau khi có khóa nên thấy hạn chế mà lần
     * đánh giá vừa nhả khóa đã commit (REPEATABLE READ giữ snapshot từ câu đầu → 409 sai). Không có → 409;</li>
     * <li>gỡ → {@code saveAndFlush} (lỗi UPDATE lộ ra trước audit) → audit {@code BOOKING_RESTRICTION_LIFTED} cùng
     * transaction (BR-QT-15): lỗi ở bất kỳ bước nào rollback cả hai.</li>
     * </ol>
     * Không gửi thông báo cho khách (rule không yêu cầu).
     */
    @Transactional
    public RestrictionStatusResponse liftRestriction(Long customerId, String reason) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(reason, "reason");
        requireCustomer(customerId);
        locks.lockCustomerViolations(customerId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        BookingRestriction restriction = restrictions.findActive(customerId, now)
                .orElseThrow(() -> new InvalidStateTransitionException(RESTRICTION, "không còn hiệu lực", "gỡ sớm"));
        Long actorId = branchScope.current().accountId();

        restriction.lift(now, actorId, reason);
        restrictions.saveAndFlush(restriction);
        audit.record(AuditEntry.of(AppointmentAuditActions.BOOKING_RESTRICTION_LIFTED)
                .entity("booking_restrictions", restriction.getId())
                .before(liftSnapshot(null, null))
                .after(liftSnapshot(now, actorId))
                .reason(reason));
        return mapper.none();
    }

    private RestrictionStatusResponse status(Long customerId, Instant now) {
        return restrictions.findActive(customerId, now).map(mapper::active).orElseGet(mapper::none);
    }

    private void requireCustomer(Long customerId) {
        if (customers.findContact(customerId).isEmpty()) {
            throw new ResourceNotFoundException("khách hàng", customerId);
        }
    }

    /** {@code LinkedHashMap} vì giá trị có thể null ({@code Map.of} không nhận null). */
    private static Map<String, Object> liftSnapshot(Instant liftedAt, Long liftedBy) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("liftedAt", liftedAt);
        snapshot.put("liftedBy", liftedBy);
        return snapshot;
    }
}
