package com.petcare.module.workforce.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.workforce.dto.CreateShiftAssignmentRequest;
import com.petcare.module.workforce.dto.ShiftAssignmentResponse;
import com.petcare.module.workforce.dto.ShiftAssignmentAuditSnapshot;
import com.petcare.module.workforce.dto.ShiftReasonRequest;
import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.entity.ShiftAssignmentSource;
import com.petcare.module.workforce.entity.ShiftAssignmentStatus;
import com.petcare.module.workforce.entity.ShiftDefinition;
import com.petcare.module.workforce.entity.ShiftRequest;
import com.petcare.module.workforce.fsm.ShiftAssignmentTransitionHandler;
import com.petcare.module.workforce.mapper.ShiftAssignmentMapper;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository.AssignmentScope;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository.OverlappingShift;
import com.petcare.module.workforce.repository.ShiftDefinitionRepository;
import com.petcare.module.workforce.repository.ShiftRequestRepository;
import com.petcare.module.workforce.repository.ShiftRequestRepository.PendingRequestRow;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;

/**
 * Phân ca của chi nhánh (UC34, workforce-v1 #4–5; BR-NS-02, BR-NS-03, BR-NS-10; docs/adr/0138 bước 3).
 * <p>
 * <b>#5 tạo (một transaction, READ COMMITTED)</b> — thứ tự cố định: kiểm không khóa nhân viên và ca có thuộc chi nhánh
 * người gọi không (không thì 404, đồng dạng với "không tồn tại" để không lộ id của chi nhánh khác) → ngày ≥ hôm nay →
 * khóa {@code accounts} của nhân viên {@code FOR NO KEY UPDATE} và <b>đọc lại dưới khóa</b> → khóa ca {@code FOR SHARE}
 * → kiểm vai trò / trạng thái / ca đang dùng / chồng giờ → {@code saveAndFlush} → audit. Thứ tự khóa cùng luồng
 * vô hiệu hóa / điều chuyển (accounts → ca → phân ca, docs/adr/0123 mục 8). Phải READ COMMITTED: kiểm sơ bộ chạy trước
 * khóa trong cùng transaction nên ở REPEATABLE_READ truy vấn chồng giờ sau khi chờ khóa sẽ không thấy phân ca vừa
 * commit. Không thông báo (BR-NS-08 không liệt kê phân hộ).
 * <p>
 * <b>#4 danh sách</b> — {@code readOnly} + REPEATABLE_READ để trang, tổng và dữ liệu ghép cùng một ảnh chụp; luôn lọc theo
 * chi nhánh của người gọi (không có tham số chi nhánh).
 * <p>
 * <b>#6 hủy (một transaction, READ COMMITTED)</b> — đọc không khóa phạm vi (404 / 403) → khóa {@code accounts} của nhân
 * viên → phân ca → yêu cầu {@code PENDING} (ADR-0123 mục 8) → <b>đọc lại dưới khóa</b>: chuyển trạng thái hợp lệ (409)
 * trước, rồi ca chưa bắt đầu (400) → ghi phân ca và Yêu cầu#5 → audit → hai thông báo (outbox). Mọi bước là một
 * transaction nên lỗi ở bất kỳ bước nào rút cả bốn hiệu ứng.
 * <p>
 * <b>#7 ghi vắng đột xuất</b> — cùng khuôn #6 (dùng chung {@link #lockForTransition}), thêm guard ngày BR-NS-07 sau
 * chuyển trạng thái (docs/adr/0138 bước 7).
 * <p>
 * <b>#8 lịch của tôi</b> — {@code readOnly} + REPEATABLE_READ; {@code staffId} luôn là người gọi.
 */
@Service
public class ShiftAssignmentService {

    /** BR-NS-03, A9: khoảng {@code from}–{@code to} tối đa 62 ngày (tính cả hai đầu). */
    static final int MAX_RANGE_DAYS = 62;
    static final int MAX_PAGE_SIZE = 100;

    /**
     * Trang yêu cầu cho mọi danh sách của module (#4, #10, #12): {@code size} kẹp 1–{@link #MAX_PAGE_SIZE}, {@code page}
     * kẹp 0–{@code Integer.MAX_VALUE / size} để {@code page × size} không vượt {@code int}. Spring Data JPA ném
     * {@code InvalidDataAccessApiUsageException} (500) khi offset vượt {@code Integer.MAX_VALUE}; kẹp thì trang đó chỉ là
     * trang rỗng (200), cùng chính sách "kẹp, không 400" của {@code page} < 0 (docs/adr/0138 bước 8, nợ D075 cho module khác).
     */
    static PageRequest pageOf(int page, int size) {
        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageRequest.of(Math.min(Math.max(page, 0), Integer.MAX_VALUE / clampedSize), clampedSize);
    }

    /** Ghi chú hệ thống của Yêu cầu#5 khi phân ca bị quản lý hủy (03 Yêu cầu#5; lý do thật nằm ở phân ca). */
    static final String PENDING_REQUEST_CLOSED_NOTE = "Phân ca đã bị quản lý hủy";

    /** Ghi chú hệ thống của Yêu cầu#5 khi phân ca được ghi nhận vắng đột xuất (BR-NS-07; lý do thật nằm ở phân ca). */
    static final String PENDING_REQUEST_ABSENCE_NOTE = "Phân ca đã được ghi nhận vắng đột xuất";

    /** Vai trò có lịch làm việc (BR-NS-02); dùng chung với {@code ShiftRegistrationService} (#15 đọc lại dưới khóa). */
    static final Set<Role> SCHEDULABLE_ROLES = Set.of(Role.RECEPTIONIST, Role.VET, Role.CARETAKER);
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    private final BranchScope branchScope;
    private final StaffDirectoryApi staffDirectory;
    private final ShiftDefinitionRepository definitions;
    private final ShiftAssignmentRepository assignments;
    private final ShiftRequestRepository requests;
    private final ShiftAssignmentMapper mapper;
    private final ShiftAssignmentTransitionHandler transitions;
    private final NotificationApi notifications;
    private final BranchQueryApi branches;
    private final AuditRecorder audit;
    private final SystemConfigApi configs;
    private final Clock clock;

    public ShiftAssignmentService(BranchScope branchScope, StaffDirectoryApi staffDirectory,
            ShiftDefinitionRepository definitions, ShiftAssignmentRepository assignments,
            ShiftRequestRepository requests, ShiftAssignmentMapper mapper,
            ShiftAssignmentTransitionHandler transitions, NotificationApi notifications, BranchQueryApi branches,
            AuditRecorder audit, SystemConfigApi configs, Clock clock) {
        this.branchScope = branchScope;
        this.staffDirectory = staffDirectory;
        this.definitions = definitions;
        this.assignments = assignments;
        this.requests = requests;
        this.mapper = mapper;
        this.transitions = transitions;
        this.notifications = notifications;
        this.branches = branches;
        this.audit = audit;
        this.configs = configs;
        this.clock = clock;
    }

    /** workforce-v1 #5 — BRANCH_MANAGER phân hộ (BR-NS-02), {@code source = MANAGER}, không kiểm định mức (BR-NS-12). */
    @Transactional
    public ShiftAssignmentResponse createShiftAssignment(CreateShiftAssignmentRequest request) {
        Long branchId = requireBranch(branchScope.current().branchId());

        // 1. Kiểm không khóa: là bước phân quyền dữ liệu, chạy trước mọi khóa.
        staffDirectory.findStaff(request.staffId())
                .filter(staff -> branchId.equals(staff.branchId()))
                .orElseThrow(() -> new ResourceNotFoundException("nhân viên", request.staffId()));
        definitions.findBranchIdById(request.shiftDefinitionId())
                .filter(branchId::equals)
                .orElseThrow(() -> new ResourceNotFoundException("ca làm việc", request.shiftDefinitionId()));

        // 2. Guard thuần Clock, không cần khóa.
        if (request.workDate().isBefore(LocalDate.now(clock))) {
            throw new BusinessRuleViolationException("BR-NS-02", "Không phân ca cho ngày trong quá khứ");
        }

        // 3. Khóa nhân viên rồi đọc lại: chi nhánh / trạng thái có thể đã đổi giữa kiểm sơ bộ và khóa.
        StaffSummary staff = staffDirectory.findStaffForUpdate(request.staffId())
                .filter(found -> branchId.equals(found.branchId()))
                .orElseThrow(() -> new ResourceNotFoundException("nhân viên", request.staffId()));
        if (!SCHEDULABLE_ROLES.contains(staff.role()) || !staff.active()) {
            throw new BusinessRuleViolationException("BR-NS-02",
                    "Chỉ phân ca cho lễ tân, bác sĩ hoặc nhân viên chăm sóc đang hoạt động của chi nhánh");
        }

        // 4. Khóa ca chia sẻ (xung đột với PATCH ngừng dùng) rồi kiểm.
        ShiftDefinition definition = definitions.findByIdForShare(request.shiftDefinitionId())
                .filter(found -> branchId.equals(found.getBranchId()))
                .orElseThrow(() -> new ResourceNotFoundException("ca làm việc", request.shiftDefinitionId()));
        if (!definition.isActive()) {
            throw new BusinessRuleViolationException("BR-NS-02", "Ca đã ngừng dùng, không phân ca được");
        }
        List<OverlappingShift> overlaps = assignments.findOverlapping(staff.accountId(), request.workDate(),
                definition.getStartTime(), definition.getEndTime());
        if (!overlaps.isEmpty()) {
            throw new BusinessRuleViolationException("BR-NS-02",
                    "Nhân viên đã có phân ca trùng hoặc chồng giờ: " + describe(overlaps));
        }

        ShiftAssignment saved = assignments.saveAndFlush(ShiftAssignment.schedule(branchId, staff.accountId(),
                definition.getId(), request.workDate(), ShiftAssignmentSource.MANAGER));
        audit.record(AuditEntry.of(WorkforceAuditActions.SHIFT_ASSIGNMENT_CREATED)
                .entity(WorkforceAuditActions.SHIFT_ASSIGNMENT_ENTITY, saved.getId())
                .after(mapper.toSnapshot(saved)));
        return mapper.toResponse(saved, staff.fullName(), definition, null);
    }

    /**
     * workforce-v1 #4 — lịch phân ca của chi nhánh người gọi trong {@code [from, to]}. {@code page} < 0 → 0, {@code size}
     * kẹp 1–100 (khuôn {@code MyNotificationService}).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<ShiftAssignmentResponse> listShiftAssignments(LocalDate from, LocalDate to, Long staffId,
            ShiftAssignmentStatus status, int page, int size) {
        Long branchId = requireBranch(branchScope.current().branchId());
        requireValidRange(from, to);
        Page<ShiftAssignment> result = assignments.findBranchSchedule(branchId, from, to, staffId, status,
                pageOf(page, size));
        List<ShiftAssignmentResponse> content = toResponses(result.getContent());
        return PageResponse.of(new PageImpl<>(content, result.getPageable(), result.getTotalElements()));
    }

    /**
     * workforce-v1 #8 — lịch làm việc của <b>chính người gọi</b> trong {@code [from, to]}, mọi trạng thái và mọi chi
     * nhánh (nhân viên đã điều chuyển vẫn thấy ca cũ, BR-NS-03). {@code staffId} lấy từ principal, không có tham số
     * định danh nên không xem được lịch người khác. Không phân trang: tối đa 62 ngày.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ShiftAssignmentResponse> listMyShiftAssignments(LocalDate from, LocalDate to) {
        Long staffId = branchScope.current().accountId();
        requireValidRange(from, to);
        return toResponses(assignments.findStaffSchedule(staffId, from, to));
    }

    /**
     * workforce-v1 #6 — BRANCH_MANAGER hủy phân ca {@code SCHEDULED} chưa bắt đầu, cả hai nguồn (BR-NS-02, BR-NS-10).
     * Thứ tự kiểm theo contract §D.5–6 / A11: phạm vi (404 / 403) trước khóa; sau khóa thì chuyển trạng thái (409) rồi
     * mới guard "ca đã bắt đầu" (400). Hệ quả (Yêu cầu#5, audit, thông báo) cùng transaction.
     */
    @Transactional
    public ShiftAssignmentResponse cancelShiftAssignment(Long shiftAssignmentId, ShiftReasonRequest request) {
        // 0–3. Phạm vi (không khóa) → khóa accounts → phân ca → yêu cầu PENDING → đọc lại, chuyển trạng thái (409).
        LockedAssignment locked = lockForTransition(shiftAssignmentId, ShiftAssignmentStatus.CANCELLED);
        StaffSummary staff = locked.staff();
        ShiftAssignment assignment = locked.assignment();
        ShiftRequest pending = locked.pending();
        ShiftDefinition definition = locked.definition();
        Instant now = clock.instant();
        if (hasStarted(now, assignment.getWorkDate(), definition.getStartTime())) {
            throw new BusinessRuleViolationException("BR-NS-02",
                    "Ca đã bắt đầu, không hủy được; hãy ghi nhận vắng đột xuất");
        }

        // 4. Ghi. before chụp trước khi đổi vì entity là đối tượng sống.
        ShiftAssignmentAuditSnapshot before = mapper.toSnapshot(assignment);
        assignment.cancel(request.reason(), branchScope.current().accountId(), now);
        ShiftAssignment saved = assignments.saveAndFlush(assignment);
        closePendingRequest(pending, now, PENDING_REQUEST_CLOSED_NOTE);

        audit.record(AuditEntry.of(WorkforceAuditActions.SHIFT_ASSIGNMENT_CANCELLED)
                .entity(WorkforceAuditActions.SHIFT_ASSIGNMENT_ENTITY, saved.getId())
                .before(before).after(mapper.toSnapshot(saved)).reason(saved.getReason()));

        // 5. Thông báo (BR-NS-08) dùng đúng lý do đã strip lưu trong phân ca.
        String branchName = branchNameOf(saved);
        ShiftNotices.cancelled(staff.accountId(), staffDirectory.findEmail(staff.accountId()).orElse(null),
                staff.fullName(), definition.getName(), saved.getWorkDate(), definition.getStartTime(),
                definition.getEndTime(), branchName, saved.getReason()).forEach(notifications::enqueue);

        return mapper.toResponse(saved, staff.fullName(), definition, null);
    }

    /**
     * workforce-v1 #7 — BRANCH_MANAGER ghi nhận vắng đột xuất cho phân ca {@code SCHEDULED} (BR-NS-07, BR-NS-10; UC38).
     * Cùng khuôn #6: phạm vi (404 / 403) trước khóa; sau khóa thì chuyển trạng thái (409) rồi mới guard ngày (400
     * {@code BR-NS-07}). Guard ngày chỉ theo ngày, không theo giờ: {@code work_date} từ hôm nay trở về trước (ca của hôm
     * nay chưa bắt đầu vẫn ghi được) và không quá {@code workforce.absence_report_max_days} ngày về trước (biên
     * <b>inclusive</b>: đúng N ngày trước vẫn được). [CFG] đọc mỗi lần gọi (BR-QT-13, ADR-0057), "hôm nay" theo giờ
     * Việt Nam. Hệ quả (Yêu cầu#5, audit, thông báo) cùng transaction; lỗi ở bất kỳ bước nào rút cả bốn hiệu ứng.
     */
    @Transactional
    public ShiftAssignmentResponse recordShiftAbsence(Long shiftAssignmentId, ShiftReasonRequest request) {
        LockedAssignment locked = lockForTransition(shiftAssignmentId, ShiftAssignmentStatus.ABSENT);
        StaffSummary staff = locked.staff();
        ShiftAssignment assignment = locked.assignment();
        ShiftDefinition definition = locked.definition();

        // Guard nghiệp vụ (400) sau chuyển trạng thái (409). "Hôm nay" theo giờ Việt Nam từ một mốc đọc sau khi khóa.
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, TimeConfig.BUSINESS_ZONE);
        LocalDate workDate = assignment.getWorkDate();
        if (workDate.isAfter(today)) {
            throw new BusinessRuleViolationException("BR-NS-07",
                    "Ca của ngày tương lai chưa ghi nhận vắng được; hãy hủy phân ca hoặc để nhân viên xin nghỉ");
        }
        int maxDays = configs.getInt(ConfigKey.WORKFORCE_ABSENCE_REPORT_MAX_DAYS);
        if (workDate.isBefore(today.minusDays(maxDays))) {
            throw new BusinessRuleViolationException("BR-NS-07",
                    "Chỉ ghi nhận vắng cho ca trong vòng " + maxDays + " ngày gần đây");
        }

        // Ghi. before chụp trước khi đổi vì entity là đối tượng sống.
        ShiftAssignmentAuditSnapshot before = mapper.toSnapshot(assignment);
        assignment.markAbsent(request.reason(), branchScope.current().accountId(), now);
        ShiftAssignment saved = assignments.saveAndFlush(assignment);
        closePendingRequest(locked.pending(), now, PENDING_REQUEST_ABSENCE_NOTE);

        audit.record(AuditEntry.of(WorkforceAuditActions.SHIFT_ASSIGNMENT_ABSENCE_RECORDED)
                .entity(WorkforceAuditActions.SHIFT_ASSIGNMENT_ENTITY, saved.getId())
                .before(before).after(mapper.toSnapshot(saved)).reason(saved.getReason()));

        // Thông báo (BR-NS-08) dùng đúng lý do đã strip lưu trong phân ca; chi nhánh là của phân ca.
        ShiftNotices.absence(staff.accountId(), staffDirectory.findEmail(staff.accountId()).orElse(null),
                staff.fullName(), definition.getName(), saved.getWorkDate(), branchNameOf(saved), saved.getReason())
                .forEach(notifications::enqueue);

        return mapper.toResponse(saved, staff.fullName(), definition, null);
    }

    /**
     * Bước chung của hủy (#6) và ghi vắng (#7), <b>không</b> có {@code @Transactional} riêng: chạy trong transaction của
     * hàm gọi. Thứ tự cố định (ADR-0123 mục 8): lớp phòng thủ thứ hai sau {@code @PreAuthorize} (người gọi phạm vi toàn
     * chuỗi không có chi nhánh bị từ chối, vì {@code branchScope.check} một mình cho SUPER_MANAGER / ADMIN đi qua mọi chi
     * nhánh) → phân quyền dữ liệu bằng <b>đọc không khóa</b> (404 / 403, không khóa dòng tài khoản của chi nhánh khác) →
     * khóa {@code accounts} → phân ca → yêu cầu {@code PENDING}; từ đây mọi đọc là đọc dưới khóa → chuyển trạng thái hợp
     * lệ (409) <b>trước</b> mọi guard nghiệp vụ (400) của hàm gọi → nạp ca.
     */
    private LockedAssignment lockForTransition(Long shiftAssignmentId, ShiftAssignmentStatus target) {
        requireBranch(branchScope.current().branchId());

        AssignmentScope scope = assignments.findScopeById(shiftAssignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("phân ca", shiftAssignmentId));
        branchScope.check(scope.getBranchId());

        StaffSummary staff = staffDirectory.findStaffForUpdate(scope.getStaffId())
                .orElseThrow(() -> new IllegalStateException(
                        "Phân ca " + shiftAssignmentId + " trỏ tới tài khoản không phải nhân viên " + scope.getStaffId()));
        ShiftAssignment assignment = assignments.findByIdForUpdate(shiftAssignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("phân ca", shiftAssignmentId));
        ShiftRequest pending = requests.findPendingByAssignmentIdForUpdate(shiftAssignmentId).orElse(null);

        // Phân ca đã nghỉ / đã vắng / đã hủy báo 409 dù ngày cũng sai.
        transitions.validateTransition(assignment.getStatus(), target);
        ShiftDefinition definition = definitions.findById(assignment.getShiftDefinitionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Phân ca " + shiftAssignmentId + " thiếu ca làm việc " + assignment.getShiftDefinitionId()));
        return new LockedAssignment(staff, assignment, pending, definition);
    }

    /** Dữ liệu đọc dưới khóa của {@link #lockForTransition}; {@code pending} là {@code null} khi phân ca không có yêu cầu chờ. */
    private record LockedAssignment(StaffSummary staff, ShiftAssignment assignment, ShiftRequest pending,
            ShiftDefinition definition) {
    }

    /** Tên chi nhánh <b>của phân ca</b> (không phải chi nhánh hiện tại của nhân viên, có thể đã điều chuyển) cho thông báo. */
    private String branchNameOf(ShiftAssignment saved) {
        return branches.findBranch(saved.getBranchId()).map(BranchSummary::name)
                .orElseThrow(() -> new IllegalStateException(
                        "Phân ca " + saved.getId() + " thiếu chi nhánh " + saved.getBranchId()));
    }

    /**
     * Yêu cầu#5: yêu cầu {@code PENDING} của phân ca không còn hiệu lực → {@code REJECTED}, hệ thống ghi chú
     * {@code note} (khác nhau theo nguyên nhân: hủy hoặc ghi vắng), không có người quyết định. {@code null} = phân ca
     * không có yêu cầu chờ. Đã khóa dòng ở bước trước.
     */
    private void closePendingRequest(ShiftRequest pending, Instant now, String note) {
        if (pending != null) {
            pending.rejectBySystem(note, now);
            requests.saveAndFlush(pending);
        }
    }

    /** BR-NS-03, A9: {@code to} từ {@code from}, tối đa 62 ngày tính cả hai đầu; dùng chung cho #4 và #8. */
    private static void requireValidRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new BusinessRuleViolationException("BR-NS-03", "Ngày kết thúc phải từ ngày bắt đầu trở đi");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new BusinessRuleViolationException("BR-NS-03",
                    "Khoảng ngày tối đa " + MAX_RANGE_DAYS + " ngày");
        }
    }

    /**
     * Ghép theo lô: đúng một lần cho mỗi nguồn (nhân viên, ca, yêu cầu chờ), bất kể số dòng. {@code public} (không
     * package-private) vì {@code ShiftRegistrationService} gọi qua proxy CGLIB của bean này; không có
     * {@code @Transactional} nên tham gia transaction của bên gọi (docs/adr/0138, N3-S6).
     */
    public List<ShiftAssignmentResponse> toResponses(List<ShiftAssignment> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> staffIds = rows.stream().map(ShiftAssignment::getStaffId).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> definitionIds = rows.stream().map(ShiftAssignment::getShiftDefinitionId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Long> assignmentIds = rows.stream().map(ShiftAssignment::getId).toList();

        Map<Long, String> staffNames = new HashMap<>();
        staffDirectory.findStaffByIds(staffIds).forEach(staff -> staffNames.put(staff.accountId(), staff.fullName()));
        Map<Long, ShiftDefinition> definitionById = new HashMap<>();
        definitions.findAllById(definitionIds).forEach(definition -> definitionById.put(definition.getId(), definition));
        Map<Long, Long> pendingByAssignment = new HashMap<>();
        for (PendingRequestRow row : requests.findPendingByAssignmentIds(assignmentIds)) {
            pendingByAssignment.put(row.getAssignmentId(), row.getRequestId());
        }

        List<ShiftAssignmentResponse> responses = new ArrayList<>(rows.size());
        for (ShiftAssignment row : rows) {
            String staffName = staffNames.get(row.getStaffId());
            ShiftDefinition definition = definitionById.get(row.getShiftDefinitionId());
            if (staffName == null || definition == null) {
                // Khóa ngoại + kiểm lúc tạo bảo đảm cả hai luôn có; thiếu là hỏng dữ liệu, không trả null sai hợp đồng.
                throw new IllegalStateException("Phân ca " + row.getId() + " thiếu nhân viên hoặc ca làm việc");
            }
            responses.add(mapper.toResponse(row, staffName, definition, pendingByAssignment.get(row.getId())));
        }
        return responses;
    }

    /** Dùng chung với {@code ShiftRequestService} (SWAP chồng giờ, cùng định nghĩa chồng giờ của #5). */
    static String describe(List<OverlappingShift> overlaps) {
        return overlaps.stream()
                .map(o -> o.getShiftName() + " (" + o.getStartTime().format(HOUR_MINUTE) + "–"
                        + o.getEndTime().format(HOUR_MINUTE) + ")")
                .collect(Collectors.joining(", "));
    }

    /**
     * Ca đã bắt đầu theo giờ Việt Nam: {@code now} không còn <b>trước</b> {@code workDate + startTime}, tức đúng giờ bắt
     * đầu đã tính là đã bắt đầu. Dùng chung cho hủy phân ca (#6, BR-NS-02) và duyệt yêu cầu (#13, BR-NS-06).
     */
    static boolean hasStarted(Instant now, LocalDate workDate, LocalTime startTime) {
        return !LocalDateTime.ofInstant(now, TimeConfig.BUSINESS_ZONE).isBefore(workDate.atTime(startTime));
    }

    /** Lớp phòng thủ thứ hai sau {@code @PreAuthorize}: không có đường "toàn chuỗi" cho phân ca (docs/adr/0138). */
    static Long requireBranch(Long branchId) {
        if (branchId == null) {
            throw new AccessDeniedScopeException("chi nhánh của người gọi", "toàn chuỗi");
        }
        return branchId;
    }
}
