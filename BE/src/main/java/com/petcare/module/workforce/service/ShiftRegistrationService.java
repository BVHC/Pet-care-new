package com.petcare.module.workforce.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.workforce.dto.PutShiftRegistrationRequest;
import com.petcare.module.workforce.dto.ShiftAssignmentResponse;
import com.petcare.module.workforce.dto.ShiftRegistrationItem;
import com.petcare.module.workforce.dto.ShiftRegistrationResponse;
import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.entity.ShiftAssignmentSource;
import com.petcare.module.workforce.entity.ShiftAssignmentStatus;
import com.petcare.module.workforce.entity.ShiftDefinition;
import com.petcare.module.workforce.fsm.ShiftAssignmentTransitionHandler;
import com.petcare.module.workforce.mapper.ShiftRegistrationMapper;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.module.workforce.repository.ShiftDefinitionRepository;
import com.petcare.module.workforce.repository.ShiftRequestRepository;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;

/**
 * Đăng ký ca theo tuần của nhân viên (UC35, workforce-v1 #15–17; BR-NS-11, BR-NS-12; docs/adr/0149 mục "Đăng ký tuần").
 * <p>
 * <b>#17 đọc</b> — {@code readOnly} + REPEATABLE_READ để danh sách, tên ca / nhân viên và {@code pendingRequestId} cùng một
 * ảnh chụp; không ghi, không audit, không outbox (BR-NS-10 không liệt kê đọc). {@code staffId} luôn là người gọi (không có
 * tham số định danh), mọi chi nhánh (BR-NS-03). Thứ tự: {@link #requireWeekStart} (400, trước mọi truy vấn và mọi phép
 * cộng ngày) → phân ca {@code SCHEDULED} của tuần → ghép tên theo lô → định mức [CFG] → hạn sửa.
 * <p>
 * <b>#15 ghi (một transaction, READ COMMITTED)</b> — thứ tự cố định (ADR-0123 mục 8): {@code weekStart} (400, chưa khóa) →
 * khóa {@code accounts} của <b>chính người gọi</b> và <b>đọc lại</b> (không còn A06–A08 / không {@code active} / không có
 * chi nhánh → 403) → {@code clock.instant()} sau khóa → hạn 00:00 thứ Hai (400) → ca trong lô {@code FOR SHARE} theo
 * {@code id} tăng dần, lọc chi nhánh trong câu → phân ca {@code SCHEDULED} của tuần {@code FOR NO KEY UPDATE} → kiểm <b>cả
 * lô</b> (mọi lỗi mục gom một message {@code BR-NS-11}) → định mức (chỉ khi lô hợp lệ, {@code BR-NS-12}) → phần khác biệt
 * → ghi: hủy ({@code Phân ca#7}) → {@code Yêu cầu#5} → tạo ({@code Phân ca#1}, {@code SELF}) → đọc response. Mọi kiểm tra
 * chạy trước lần ghi đầu nên một lỗi thì không lưu gì; không {@code catch}, không audit, không thông báo (BR-NS-08, 10).
 * Phải READ COMMITTED: lần gửi chờ khóa {@code accounts} phải thấy phân ca lần trước vừa commit (cùng lý do với #5).
 */
@Service
public class ShiftRegistrationService {

    /** Năm tối thiểu / tối đa của {@code weekStart}: chặn tràn {@code plusDays} và "date out of range" ở PostgreSQL (500). */
    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 2100;

    /** Lý do hệ thống của Phân ca#7 (03): đúng chữ hợp đồng, ≤ 300. */
    static final String SELF_EDIT_REASON = "Nhân viên sửa đăng ký tuần";

    /** Ghi chú hệ thống của Yêu cầu#5 khi phân ca bị bỏ khỏi đăng ký tuần (lý do thật nằm ở phân ca). */
    static final String PENDING_REQUEST_EDITED_NOTE = "Phân ca đã bị bỏ khi nhân viên sửa đăng ký tuần";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    private final BranchScope branchScope;
    private final StaffDirectoryApi staffDirectory;
    private final ShiftAssignmentRepository assignments;
    private final ShiftDefinitionRepository definitions;
    private final ShiftRequestRepository requests;
    private final ShiftAssignmentService shiftAssignments;
    private final ShiftAssignmentTransitionHandler transitions;
    private final ShiftRegistrationMapper mapper;
    private final SystemConfigApi configs;
    private final Clock clock;

    public ShiftRegistrationService(BranchScope branchScope, StaffDirectoryApi staffDirectory,
            ShiftAssignmentRepository assignments, ShiftDefinitionRepository definitions,
            ShiftRequestRepository requests, ShiftAssignmentService shiftAssignments,
            ShiftAssignmentTransitionHandler transitions, ShiftRegistrationMapper mapper, SystemConfigApi configs,
            Clock clock) {
        this.branchScope = branchScope;
        this.staffDirectory = staffDirectory;
        this.assignments = assignments;
        this.definitions = definitions;
        this.requests = requests;
        this.shiftAssignments = shiftAssignments;
        this.transitions = transitions;
        this.mapper = mapper;
        this.configs = configs;
        this.clock = clock;
    }

    /**
     * workforce-v1 #17 — đăng ký ca của <b>chính người gọi</b> trong tuần {@code weekStart}. Không kiểm hạn đăng ký: xem
     * được cả tuần đã qua hạn ({@code editableUntil} chỉ là thông tin cho client).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ShiftRegistrationResponse getMyShiftRegistration(LocalDate weekStart) {
        Long staffId = branchScope.current().accountId();
        requireWeekStart(weekStart);
        List<ShiftAssignment> rows = assignments.findScheduledInWeek(staffId, weekStart, weekStart.plusDays(6));
        List<ShiftAssignmentResponse> list = shiftAssignments.toResponses(rows);
        return mapper.toResponse(weekStart, configs.getInt(ConfigKey.WORKFORCE_WEEKLY_REQUIRED_SHIFTS),
                editableUntil(weekStart), list);
    }

    /**
     * workforce-v1 #15 — nhân viên thay <b>cả tuần</b> các phân ca {@code SELF} của chính mình (BR-NS-11). Mục bỏ đi →
     * {@code CANCELLED} (Phân ca#7), yêu cầu {@code PENDING} của nó → {@code REJECTED} (Yêu cầu#5), mục mới → phân ca
     * {@code SELF} (Phân ca#1), mục giữ nguyên không đổi (gửi lại cùng nội dung không ghi gì). Phân ca {@code MANAGER}
     * không bị đụng nhưng được tính vào định mức và vào kiểm chồng giờ.
     */
    @Transactional
    public ShiftRegistrationResponse putMyShiftRegistration(LocalDate weekStart, PutShiftRegistrationRequest request) {
        Long staffId = branchScope.current().accountId();
        requireWeekStart(weekStart);

        // 1. Khóa accounts của chính người gọi rồi đọc lại: vai trò / trạng thái / chi nhánh có thể đã đổi trong lúc chờ
        //    khóa (khóa, vô hiệu hóa, điều chuyển cũng khóa dòng này). Chi nhánh lấy từ bản đọc dưới khóa.
        StaffSummary staff = staffDirectory.findStaffForUpdate(staffId)
                .filter(found -> ShiftAssignmentService.SCHEDULABLE_ROLES.contains(found.role()) && found.active()
                        && found.branchId() != null)
                .orElseThrow(() -> new AccessDeniedScopeException("nhân viên đang hoạt động của một chi nhánh",
                        "tài khoản hiện tại"));
        Long branchId = staff.branchId();

        // 2. Hạn: đọc đồng hồ sau khóa. Đúng 00:00 thứ Hai là đã quá hạn (cùng quy ước với hasStarted).
        Instant now = clock.instant();
        LocalDate weekEnd = weekStart.plusDays(6);
        if (!now.isBefore(editableUntil(weekStart))) {
            throw new BusinessRuleViolationException("BR-NS-11", "Đã quá hạn đăng ký tuần " + DAY.format(weekStart)
                    + " (hạn là 00:00 thứ Hai); chỉ còn xin nghỉ hoặc đổi ca");
        }

        // 3. Khóa các ca trong lô FOR SHARE (id tăng dần, lọc chi nhánh trong câu) rồi mới đọc is_active.
        List<ShiftRegistrationItem> items = request.items();
        Set<Long> requestedIds = new TreeSet<>();
        items.forEach(item -> requestedIds.add(item.shiftDefinitionId()));
        Map<Long, ShiftDefinition> definitionById = new HashMap<>();
        if (!requestedIds.isEmpty()) {
            definitions.lockForShareInBranch(requestedIds, branchId)
                    .forEach(definition -> definitionById.put(definition.getId(), definition));
        }

        // 4. Khóa phân ca SCHEDULED của tuần (cả SELF và MANAGER) rồi nạp giờ của các ca còn thiếu (không khóa: ca đã
        //    có phân ca thì không sửa giờ được, BR-NS-01).
        List<ShiftAssignment> current = assignments.lockScheduledInWeek(staffId, weekStart, weekEnd);
        Set<Long> missingIds = new TreeSet<>();
        current.forEach(assignment -> {
            if (!definitionById.containsKey(assignment.getShiftDefinitionId())) {
                missingIds.add(assignment.getShiftDefinitionId());
            }
        });
        Map<Long, ShiftDefinition> currentDefinitions = new HashMap<>();
        if (!missingIds.isEmpty()) {
            definitions.findAllById(missingIds).forEach(definition -> currentDefinitions.put(definition.getId(), definition));
        }

        // 5. Kiểm cả lô: gom mọi lỗi mục vào một message BR-NS-11.
        List<ShiftAssignment> managerAssignments = current.stream()
                .filter(assignment -> assignment.getSource() == ShiftAssignmentSource.MANAGER).toList();
        List<String> errors = checkBatch(items, weekStart, weekEnd, definitionById, currentDefinitions,
                managerAssignments);
        if (!errors.isEmpty()) {
            throw new BusinessRuleViolationException("BR-NS-11", "Đăng ký tuần không hợp lệ: " + String.join("; ", errors));
        }

        // 6. Định mức (BR-NS-12) chỉ có nghĩa trên tập hợp lệ; đếm cả SELF mới và MANAGER. [CFG] đọc mỗi lần (BR-QT-13).
        Set<Key> wanted = new LinkedHashSet<>();
        items.forEach(item -> wanted.add(new Key(item.workDate(), item.shiftDefinitionId())));
        int required = configs.getInt(ConfigKey.WORKFORCE_WEEKLY_REQUIRED_SHIFTS);
        int total = wanted.size() + managerAssignments.size();
        if (total < required) {
            throw new BusinessRuleViolationException("BR-NS-12", "Tuần này có " + total + " ca (gồm "
                    + managerAssignments.size() + " ca do quản lý xếp), cần tối thiểu " + required + " ca, còn thiếu "
                    + (required - total));
        }

        // 7. Phần khác biệt. Chỉ phân ca SELF bị hủy; mục giữ nguyên không đổi.
        Map<Key, ShiftAssignment> currentSelf = new LinkedHashMap<>();
        for (ShiftAssignment assignment : current) {
            if (assignment.getSource() == ShiftAssignmentSource.SELF) {
                currentSelf.put(new Key(assignment.getWorkDate(), assignment.getShiftDefinitionId()), assignment);
            }
        }
        List<ShiftAssignment> toCancel = currentSelf.entrySet().stream()
                .filter(entry -> !wanted.contains(entry.getKey())).map(Map.Entry::getValue).toList();
        List<ShiftAssignment> toCreate = wanted.stream().filter(key -> !currentSelf.containsKey(key))
                .map(key -> ShiftAssignment.schedule(branchId, staffId, key.shiftDefinitionId(), key.workDate(),
                        ShiftAssignmentSource.SELF))
                .toList();

        // 8. Ghi: hủy (Phân ca#7) → từ chối yêu cầu chờ (Yêu cầu#5) → tạo (Phân ca#1). Lỗi ở bất kỳ bước nào rút cả ba.
        if (!toCancel.isEmpty()) {
            for (ShiftAssignment assignment : toCancel) {
                transitions.validateTransition(assignment.getStatus(), ShiftAssignmentStatus.CANCELLED);
                assignment.cancel(SELF_EDIT_REASON, staffId, now);
            }
            assignments.saveAllAndFlush(toCancel);
            requests.rejectPendingBySystem(toCancel.stream().map(ShiftAssignment::getId).toList(),
                    PENDING_REQUEST_EDITED_NOTE, now);
        }
        if (!toCreate.isEmpty()) {
            transitions.validateInitial(ShiftAssignmentStatus.SCHEDULED);
            assignments.saveAllAndFlush(toCreate);
        }

        // 9. Response: cùng đường với #17, đọc sau khi đã flush (A12: chỉ phân ca SCHEDULED, cả hai nguồn).
        List<ShiftAssignmentResponse> list = shiftAssignments
                .toResponses(assignments.findScheduledInWeek(staffId, weekStart, weekEnd));
        return mapper.toResponse(weekStart, required, editableUntil(weekStart), list);
    }

    /**
     * Kiểm cả lô, trả mọi lỗi mục (không dừng ở lỗi đầu). "Mục N" tính từ 1 theo thứ tự gửi. Lỗi từng mục (ngoài tuần, ca
     * không thuộc chi nhánh / không tồn tại, ngừng dùng, trùng cặp) loại mục khỏi bước chồng giờ; chồng giờ kiểm giữa các
     * mục với nhau và giữa mục với phân ca {@code MANAGER} (kể cả trùng đúng cặp). Phân ca {@code SELF} hiện có không tham
     * gia vì sẽ bị thay.
     */
    private List<String> checkBatch(List<ShiftRegistrationItem> items, LocalDate weekStart, LocalDate weekEnd,
            Map<Long, ShiftDefinition> locked, Map<Long, ShiftDefinition> others, List<ShiftAssignment> managers) {
        List<String> errors = new ArrayList<>();
        Map<Key, Integer> firstIndex = new HashMap<>();
        List<Candidate> valid = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ShiftRegistrationItem item = items.get(i);
            int number = i + 1;
            boolean inWeek = !item.workDate().isBefore(weekStart) && !item.workDate().isAfter(weekEnd);
            if (!inWeek) {
                errors.add("Mục " + number + ": ngày " + DAY.format(item.workDate()) + " nằm ngoài tuần "
                        + DAY.format(weekStart) + " – " + DAY.format(weekEnd));
            }
            ShiftDefinition definition = locked.get(item.shiftDefinitionId());
            if (definition == null) {
                errors.add("Mục " + number + ": ca #" + item.shiftDefinitionId()
                        + " không tồn tại hoặc không thuộc chi nhánh của bạn");
            } else if (!definition.isActive()) {
                errors.add("Mục " + number + ": ca '" + definition.getName() + "' đã ngừng dùng");
            }
            if (!inWeek || definition == null || !definition.isActive()) {
                continue;
            }
            Key key = new Key(item.workDate(), item.shiftDefinitionId());
            Integer first = firstIndex.putIfAbsent(key, number);
            if (first != null) {
                errors.add("Mục " + number + ": trùng với mục " + first + " (cùng ngày, cùng ca)");
                continue;
            }
            valid.add(new Candidate(number, item.workDate(), definition));
        }
        for (int a = 0; a < valid.size(); a++) {
            Candidate left = valid.get(a);
            for (int b = a + 1; b < valid.size(); b++) {
                Candidate right = valid.get(b);
                if (left.workDate().equals(right.workDate()) && overlaps(left.definition(), right.definition())) {
                    errors.add("Mục " + left.number() + " và mục " + right.number() + " chồng giờ ngày "
                            + DAY.format(left.workDate()) + ": " + describe(left.definition()) + " / "
                            + describe(right.definition()));
                }
            }
            for (ShiftAssignment manager : managers) {
                ShiftDefinition managed = locked.containsKey(manager.getShiftDefinitionId())
                        ? locked.get(manager.getShiftDefinitionId()) : others.get(manager.getShiftDefinitionId());
                if (managed == null) {
                    throw new IllegalStateException("Phân ca " + manager.getId() + " thiếu ca làm việc "
                            + manager.getShiftDefinitionId());
                }
                if (left.workDate().equals(manager.getWorkDate()) && overlaps(left.definition(), managed)) {
                    errors.add("Mục " + left.number() + ": chồng giờ với phân ca do quản lý xếp ngày "
                            + DAY.format(left.workDate()) + ": " + describe(managed));
                }
            }
        }
        return errors;
    }

    /** Giao nhau thật sự (BR-NS-02): {@code startA < endB và startB < endA}; hai ca liền kề không chồng. */
    private static boolean overlaps(ShiftDefinition a, ShiftDefinition b) {
        LocalTime startA = a.getStartTime();
        LocalTime startB = b.getStartTime();
        return startA.isBefore(b.getEndTime()) && startB.isBefore(a.getEndTime());
    }

    private static String describe(ShiftDefinition definition) {
        return definition.getName() + " (" + definition.getStartTime().format(HOUR_MINUTE) + "–"
                + definition.getEndTime().format(HOUR_MINUTE) + ")";
    }

    /** Khóa so sánh của một phân ca trong tuần: (ngày, ca). */
    private record Key(LocalDate workDate, Long shiftDefinitionId) {
    }

    /** Mục đã qua kiểm từng mục, dùng cho bước chồng giờ. */
    private record Candidate(int number, LocalDate workDate, ShiftDefinition definition) {
    }

    /**
     * BR-NS-11: {@code weekStart} là thứ Hai. Rồi giới hạn năm (giả định của docs/adr/0149, không có trong docs/02): năm
     * ngoài {@code [2000, 2100]} cũng là 400 {@code BR-NS-11} thay vì để {@code plusDays(6)} tràn hoặc PostgreSQL báo
     * date out of range (500). Dùng chung cho #15 và #16.
     */
    static void requireWeekStart(LocalDate weekStart) {
        if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new BusinessRuleViolationException("BR-NS-11", "Ngày bắt đầu tuần phải là thứ Hai");
        }
        if (weekStart.getYear() < MIN_YEAR || weekStart.getYear() > MAX_YEAR) {
            throw new BusinessRuleViolationException("BR-NS-11",
                    "Chỉ hỗ trợ tuần từ năm " + MIN_YEAR + " đến năm " + MAX_YEAR);
        }
    }

    /** Hạn sửa đăng ký tuần: 00:00 thứ Hai của {@code weekStart} giờ Việt Nam (BR-NS-11); không phụ thuộc đồng hồ. */
    static Instant editableUntil(LocalDate weekStart) {
        return weekStart.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
    }
}
