package com.petcare.module.care.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi.StaySummary;
import com.petcare.module.care.api.CareTaskApi.CareTaskType;
import com.petcare.module.care.dto.CancelCareTaskRequest;
import com.petcare.module.care.dto.CareTaskResponse;
import com.petcare.module.care.dto.CompleteCareTaskRequest;
import com.petcare.module.care.entity.CareTask;
import com.petcare.module.care.entity.CareTaskStatus;
import com.petcare.module.care.fsm.CareTaskTransitionHandler;
import com.petcare.module.care.mapper.CareTaskMapper;
import com.petcare.module.care.repository.CareTaskRepository;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.visit.api.VaccinationQueryApi;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;

import jakarta.persistence.criteria.Predicate;

/**
 * Việc gọi điện của lễ tân — care-v1 #1–4 (UC87, BR-TB-05; docs/adr/0080). Chỉ RECEPTIONIST (controller), chỉ chi nhánh
 * của mình ({@link BranchScope}). Tách khỏi {@link CareTaskService} (là {@code CareTaskApi}, {@code MANDATORY}): đây là
 * use case tự mở transaction.
 * <ul>
 * <li>Đọc (#1, #2): readOnly; danh sách REPEATABLE READ để count và trang cùng một snapshot.</li>
 * <li>Ghi (#3, #4): READ COMMITTED. Thứ tự: 404 → 403 chi nhánh (câu vô hướng, chưa nạp entity) → 400 BR-TB-05 → khóa
 * {@code FOR NO KEY UPDATE} → {@code now} → FSM 409 → ghi → flush → ghép. Câu hủy hệ thống chờ khóa này rồi kiểm lại
 * {@code status = 'OPEN'}, nên không hủy đè task lễ tân vừa xử lý.</li>
 * </ul>
 * Care không đọc bảng của module khác: tên khách, tên thú, SĐT, loại vaccine, SĐT khẩn lấy qua {@code *QueryApi}, một
 * lời gọi cho cả trang (riêng SĐT khẩn mỗi đặt chỗ một lời gọi, nợ D050). Không audit, không outbox (BR-QT-15 không liệt
 * kê Care Task).
 */
@Service
public class CareTaskDeskService {

    static final int MAX_PAGE_SIZE = 100;

    /** Tên nghiệp vụ trong message 404, cùng chữ với message 409 của FSM. */
    static final String RESOURCE = CareTaskTransitionHandler.FSM_NAME;

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final CareTaskRepository tasks;
    private final CareTaskTransitionHandler fsm;
    private final CareTaskMapper mapper;
    private final CustomerQueryApi customers;
    private final PetQueryApi pets;
    private final VaccinationQueryApi vaccinations;
    private final CatalogQueryApi catalog;
    private final BoardingQueryApi boarding;
    private final BranchScope scope;
    private final Clock clock;

    public CareTaskDeskService(CareTaskRepository tasks, CareTaskTransitionHandler fsm, CareTaskMapper mapper,
            CustomerQueryApi customers, PetQueryApi pets, VaccinationQueryApi vaccinations, CatalogQueryApi catalog,
            BoardingQueryApi boarding, BranchScope scope, Clock clock) {
        this.tasks = tasks;
        this.fsm = fsm;
        this.mapper = mapper;
        this.customers = customers;
        this.pets = pets;
        this.vaccinations = vaccinations;
        this.catalog = catalog;
        this.boarding = boarding;
        this.scope = scope;
        this.clock = clock;
    }

    /**
     * care-v1 #1. Chi nhánh của lễ tân, {@code status} vắng = {@code OPEN}, sắp {@code dueDate, id} tăng dần
     * (index {@code ix_care_tasks_branch_status_due}). {@code dueFrom > dueTo} cho trang rỗng. {@code page} ≥ 0,
     * {@code size} 1–100 (kẹp như {@code MyNotificationService}).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<CareTaskResponse> list(CareTaskStatus status, CareTaskType type, LocalDate dueFrom,
            LocalDate dueTo, int page, int size) {
        Long branchId = scope.resolve(null);
        if (branchId == null) {
            throw new IllegalStateException("Danh sách việc chăm sóc khách yêu cầu nhân viên thuộc một chi nhánh");
        }
        CareTaskStatus effectiveStatus = status == null ? CareTaskStatus.OPEN : status;
        Specification<CareTask> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("branchId"), branchId));
            predicates.add(cb.equal(root.get("status"), effectiveStatus));
            if (type != null) {
                predicates.add(cb.equal(root.get("taskType"), type));
            }
            if (dueFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("dueDate"), dueFrom));
            }
            if (dueTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("dueDate"), dueTo));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<CareTask> result = tasks.findAll(spec, PageRequest.of(Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE), Sort.by(Sort.Direction.ASC, "dueDate", "id")));
        List<CareTaskResponse> content = toResponses(result.getContent());
        return PageResponse.of(new PageImpl<>(content, result.getPageable(), result.getTotalElements()));
    }

    /** care-v1 #2. 404 trước 403: task không có không lộ chi nhánh; chi nhánh khác bị chặn trước khi ghép dữ liệu. */
    @Transactional(readOnly = true)
    public CareTaskResponse get(Long careTaskId) {
        CareTask task = tasks.findById(careTaskId).orElseThrow(() -> notFound(careTaskId));
        scope.check(task.getBranchId());
        return toResponses(List.of(task)).get(0);
    }

    /** care-v1 #3, Care Task#2 {@code OPEN → DONE}. Thiếu kết quả hoặc ghi chú trống là 400 BR-TB-05. */
    @Transactional
    public CareTaskResponse complete(Long careTaskId, CompleteCareTaskRequest request) {
        authorize(careTaskId);
        String note = strip(request.note());
        if (request.result() == null || note.isEmpty()) {
            throw new BusinessRuleViolationException("BR-TB-05", "Vui lòng chọn kết quả và nhập ghi chú");
        }
        Long actorId = scope.current().accountId();
        CareTask task = tasks.findByIdForUpdate(careTaskId).orElseThrow(() -> notFound(careTaskId));
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        fsm.validateTransition(task.getStatus(), CareTaskStatus.DONE);
        task.complete(request.result(), note, actorId, now);
        return toResponses(List.of(tasks.saveAndFlush(task))).get(0);
    }

    /** care-v1 #4, Care Task#3 {@code OPEN → CANCELLED}. Lý do trống là 400 BR-TB-05. */
    @Transactional
    public CareTaskResponse cancel(Long careTaskId, CancelCareTaskRequest request) {
        authorize(careTaskId);
        String reason = strip(request.reason());
        if (reason.isEmpty()) {
            throw new BusinessRuleViolationException("BR-TB-05", "Vui lòng nhập lý do hủy");
        }
        Long actorId = scope.current().accountId();
        CareTask task = tasks.findByIdForUpdate(careTaskId).orElseThrow(() -> notFound(careTaskId));
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        fsm.validateTransition(task.getStatus(), CareTaskStatus.CANCELLED);
        task.cancel(reason, actorId, now);
        return toResponses(List.of(tasks.saveAndFlush(task))).get(0);
    }

    /** 404 nếu không có; 403 nếu task của chi nhánh khác. Chỉ đọc {@code branch_id}, không nạp entity (xem repository). */
    private void authorize(Long careTaskId) {
        scope.check(tasks.findBranchIdById(careTaskId).orElseThrow(() -> notFound(careTaskId)));
    }

    /**
     * Ghép tên khách, tên thú, SĐT cần gọi và {@code context} cho cả trang: mỗi nguồn một lời gọi theo lô, tập rỗng thì
     * không gọi. Thiếu khách hoặc thú là dữ liệu sai (FK {@code RESTRICT} đảm bảo tồn tại) nên là lỗi hệ thống, không
     * trả null cho trường bắt buộc của contract.
     */
    private List<CareTaskResponse> toResponses(List<CareTask> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        Map<Long, CustomerContact> contacts = customers
                .findContacts(page.stream().map(CareTask::getCustomerId).distinct().toList()).stream()
                .collect(Collectors.toMap(CustomerContact::customerId, Function.identity()));
        Map<Long, PetSummary> petsById = pets
                .findPets(page.stream().map(CareTask::getPetId).distinct().toList()).stream()
                .collect(Collectors.toMap(PetSummary::petId, Function.identity()));
        Map<Long, String> vaccineNames = vaccineTypeNames(page);
        Map<Long, String> emergencyPhones = emergencyPhones(page);
        List<CareTaskResponse> responses = new ArrayList<>(page.size());
        for (CareTask task : page) {
            CustomerContact contact = Objects.requireNonNull(contacts.get(task.getCustomerId()),
                    () -> "Khách #" + task.getCustomerId() + " của việc #" + task.getId() + " không tồn tại");
            PetSummary pet = Objects.requireNonNull(petsById.get(task.getPetId()),
                    () -> "Thú cưng #" + task.getPetId() + " của việc #" + task.getId() + " không tồn tại");
            String phone = task.getTaskType() == CareTaskType.PICKUP_OVERDUE
                    ? emergencyPhones.get(task.getBoardingBookingId())
                    : contact.phone();
            // Map.of() bất biến ném NPE khi tra key null; chỉ hai loại VACCINE_* có vaccinationId.
            String vaccineName = task.getVaccinationId() == null ? null : vaccineNames.get(task.getVaccinationId());
            responses.add(mapper.toResponse(task, contact.fullName(), phone, pet.name(), context(task, vaccineName)));
        }
        return responses;
    }

    /** {@code vaccinationId → tên loại vaccine}; id mũi không có hoặc loại không có tên thì vắng (context bỏ phần tên). */
    private Map<Long, String> vaccineTypeNames(List<CareTask> page) {
        List<Long> vaccinationIds = page.stream().map(CareTask::getVaccinationId).filter(Objects::nonNull).distinct()
                .toList();
        if (vaccinationIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> typeIds = vaccinations.findVaccineTypeIds(vaccinationIds);
        Map<Long, String> names = catalog.findVaccineTypeNames(typeIds.values().stream().distinct().toList());
        Map<Long, String> byVaccination = new HashMap<>();
        typeIds.forEach((vaccinationId, typeId) -> {
            String name = names.get(typeId);
            if (name != null) {
                byVaccination.put(vaccinationId, name);
            }
        });
        return byVaccination;
    }

    /** {@code bookingId → SĐT khẩn lúc nhận thú} (BR-TB-05); một lời gọi mỗi đặt chỗ khác nhau, nợ D050. */
    private Map<Long, String> emergencyPhones(List<CareTask> page) {
        Set<Long> bookingIds = page.stream().map(CareTask::getBoardingBookingId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, String> phones = new HashMap<>();
        for (Long bookingId : bookingIds) {
            boarding.findBooking(bookingId).map(StaySummary::emergencyPhone)
                    .ifPresent(phone -> phones.put(bookingId, phone));
        }
        return phones;
    }

    /** care-v1 {@code CareTask.context}: mô tả ngắn để lễ tân biết gọi vì việc gì. */
    static String context(CareTask task, String vaccineTypeName) {
        return switch (task.getTaskType()) {
            case VACCINE_DUE -> withName("Tái chủng", vaccineTypeName);
            case VACCINE_OVERDUE -> withName("Quá hạn tái chủng", vaccineTypeName);
            case FOLLOW_UP_DUE -> "Tái khám ngày " + DISPLAY_DATE.format(task.getDueDate());
            case PICKUP_OVERDUE -> "Quá hạn đón thú — đặt chỗ #" + task.getBoardingBookingId();
        };
    }

    private static String withName(String base, String name) {
        return name == null ? base : base + " " + name;
    }

    /** Bỏ khoảng trắng đầu / cuối kể cả Unicode (Hibernate {@code @NotBlank} chỉ bỏ ký tự ≤ U+0020). */
    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    private static ResourceNotFoundException notFound(Long careTaskId) {
        return new ResourceNotFoundException(RESOURCE, careTaskId);
    }
}
