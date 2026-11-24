package com.petcare.module.inpatient.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.api.KennelApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.inpatient.dto.AdmissionOrderResponse;
import com.petcare.module.inpatient.dto.AdmissionResponse;
import com.petcare.module.inpatient.dto.AdmissionSummaryResponse;
import com.petcare.module.inpatient.dto.ObservationResponse;
import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.inpatient.entity.AdmissionObservation;
import com.petcare.module.inpatient.entity.AdmissionOrder;
import com.petcare.module.inpatient.entity.AdmissionOrderStatus;
import com.petcare.module.inpatient.entity.AdmissionStatus;
import com.petcare.module.inpatient.mapper.AdmissionMapper;
import com.petcare.module.inpatient.mapper.AdmissionObservationMapper;
import com.petcare.module.inpatient.mapper.AdmissionOrderMapper;
import com.petcare.module.inpatient.repository.AdmissionObservationRepository;
import com.petcare.module.inpatient.repository.AdmissionOrderRepository;
import com.petcare.module.inpatient.repository.AdmissionRepository;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;

/**
 * Đọc ca nội trú — inpatient-v1 #2 (danh sách của chi nhánh) và #3 (chi tiết); BR-LT-17, BR-QT-03; docs/adr/0149 mục
 * 19. {@code readOnly} + REPEATABLE READ: trang, tổng số và dữ liệu ghép (tên thú, tên dịch vụ, mã chuồng) cùng một ảnh
 * chụp. Vai trò A06–A08 do controller chặn; chi nhánh lấy từ {@link BranchScope} (không có tham số chi nhánh).
 * <p>
 * {@code billedDays} / {@code estimatedAmount} tính bằng một {@code now} cho cả request (BR-LT-14). Thiếu thú / dịch vụ /
 * mã chuồng của một dòng là dữ liệu hỏng (không có xóa cứng, {@code kennel_id} của đặt chỗ không bao giờ bị xóa) →
 * {@link IllegalStateException}, không trả null im lặng.
 */
@Service
public class AdmissionReadService {

    /** Kích thước trang tối đa; cũng là trần 100 id của {@code PetQueryApi.findPets} và {@code KennelApi.findCodesBy*}. */
    static final int MAX_PAGE_SIZE = 100;

    private final BranchScope branchScope;
    private final AdmissionRepository admissions;
    private final PetQueryApi pets;
    private final CatalogQueryApi catalog;
    private final KennelApi kennels;
    private final AdmissionMapper mapper;
    private final AdmissionOrderRepository admissionOrders;
    private final AdmissionOrderMapper orderMapper;
    private final AdmissionObservationRepository observations;
    private final AdmissionObservationMapper observationMapper;
    private final StaffDirectoryApi staff;
    private final Clock clock;

    public AdmissionReadService(BranchScope branchScope, AdmissionRepository admissions, PetQueryApi pets,
            CatalogQueryApi catalog, KennelApi kennels, AdmissionMapper mapper,
            AdmissionOrderRepository admissionOrders, AdmissionOrderMapper orderMapper,
            AdmissionObservationRepository observations, AdmissionObservationMapper observationMapper,
            StaffDirectoryApi staff, Clock clock) {
        this.branchScope = branchScope;
        this.admissions = admissions;
        this.pets = pets;
        this.catalog = catalog;
        this.kennels = kennels;
        this.mapper = mapper;
        this.admissionOrders = admissionOrders;
        this.orderMapper = orderMapper;
        this.observations = observations;
        this.observationMapper = observationMapper;
        this.staff = staff;
        this.clock = clock;
    }

    /** inpatient-v1 #2: {@code status} vắng = {@code ADMITTED}; nhập viện sớm nhất trước. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<AdmissionSummaryResponse> listAdmissions(AdmissionStatus status, int page, int size) {
        Long branchId = branchScope.resolve(null);
        Page<Admission> result = admissions.findPageByBranchAndStatus(branchId,
                status == null ? AdmissionStatus.ADMITTED : status, pageOf(page, size));
        List<Admission> rows = result.getContent();
        Instant now = clock.instant();

        Map<Long, String> petNames = pets.findPets(rows.stream().map(Admission::getPetId).distinct().toList())
                .stream().collect(Collectors.toMap(PetSummary::petId, PetSummary::name));
        Map<Long, String> treatmentKennels = kennels.findCodesByKennelIds(rows.stream().map(Admission::getKennelId)
                .filter(Objects::nonNull).distinct().toList());
        Map<Long, String> stayKennels = kennels.findCodesByBookingIds(rows.stream()
                .map(Admission::getBoardingBookingId).filter(Objects::nonNull).distinct().toList());

        List<AdmissionSummaryResponse> content = rows.stream().map(a -> mapper.toSummary(a,
                require(petNames, a.getPetId(), "thú", a), kennelCode(a, treatmentKennels, stayKennels),
                a.billedDays(now), a.estimatedAmount(now))).toList();
        return PageResponse.of(new PageImpl<>(content, result.getPageable(), result.getTotalElements()));
    }

    /** inpatient-v1 #3: 404 → 403 chi nhánh của ca (BR-LT-17). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdmissionResponse getAdmission(Long admissionId) {
        Admission admission = admissions.findById(admissionId)
                .orElseThrow(() -> new ResourceNotFoundException("ca nội trú", admissionId));
        branchScope.check(admission.getBranchId());
        PetSummary pet = pets.findPet(admission.getPetId())
                .orElseThrow(() -> corrupt("thú", admission));
        ServiceInfo service = catalog.findService(admission.getServiceId())
                .orElseThrow(() -> corrupt("dịch vụ", admission));
        Instant now = clock.instant();
        return mapper.toResponse(admission, pet.name(), service.name(), admission.billedDays(now),
                admission.estimatedAmount(now));
    }

    /**
     * inpatient-v1 #6: 404 → 403 chi nhánh của ca (BR-LT-17). Không phân trang; sắp {@code scheduledAt} tăng dần, trùng thì
     * id tăng dần. {@code status} vắng = mọi trạng thái. Chỉ đọc một bảng nên không cần ảnh chụp REPEATABLE READ.
     */
    @Transactional(readOnly = true)
    public List<AdmissionOrderResponse> listOrders(Long admissionId, AdmissionOrderStatus status) {
        Admission admission = admissions.findById(admissionId)
                .orElseThrow(() -> new ResourceNotFoundException("ca nội trú", admissionId));
        branchScope.check(admission.getBranchId());
        List<AdmissionOrder> rows = status == null
                ? admissionOrders.findByAdmissionIdOrderByScheduledAtAscIdAsc(admissionId)
                : admissionOrders.findByAdmissionIdAndStatusOrderByScheduledAtAscIdAsc(admissionId, status);
        return rows.stream().map(orderMapper::toResponse).toList();
    }

    /**
     * inpatient-v1 #10: 404 → 403 chi nhánh của ca (BR-LT-17). Mới nhất trước, trùng thì id giảm dần; đọc được cả ca đã
     * xuất viện. {@code readOnly} + REPEATABLE READ: trang, tổng số và tên người ghi (một câu tra theo lô) cùng một ảnh
     * chụp. Thiếu tên người ghi là dữ liệu hỏng (không có xóa cứng nhân viên) → {@link IllegalStateException}.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<ObservationResponse> listObservations(Long admissionId, int page, int size) {
        Admission admission = admissions.findById(admissionId)
                .orElseThrow(() -> new ResourceNotFoundException("ca nội trú", admissionId));
        branchScope.check(admission.getBranchId());
        Page<AdmissionObservation> result = observations.findByAdmissionIdOrderByCreatedAtDescIdDesc(admissionId,
                pageOf(page, size));
        Map<Long, String> names = staff.findStaffByIds(result.getContent().stream()
                .map(AdmissionObservation::getRecordedBy).distinct().toList()).stream()
                .collect(Collectors.toMap(StaffSummary::accountId, StaffSummary::fullName));
        List<ObservationResponse> content = result.getContent().stream().map(o -> observationMapper.toResponse(o,
                require(names, o.getRecordedBy(), "người ghi theo dõi", admission), o.getCreatedAt())).toList();
        return PageResponse.of(new PageImpl<>(content, result.getPageable(), result.getTotalElements()));
    }

    /** {@code size} 1–100, {@code page} ≥ 0 và ≤ {@code Integer.MAX_VALUE / size} (tránh D075, khuôn workforce). */
    static PageRequest pageOf(int page, int size) {
        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageRequest.of(Math.min(Math.max(page, 0), Integer.MAX_VALUE / clampedSize), clampedSize);
    }

    private static String kennelCode(Admission a, Map<Long, String> treatmentKennels, Map<Long, String> stayKennels) {
        return a.getKennelId() != null
                ? require(treatmentKennels, a.getKennelId(), "chuồng điều trị", a)
                : require(stayKennels, a.getBoardingBookingId(), "chuồng của đặt chỗ", a);
    }

    private static String require(Map<Long, String> values, Long key, String what, Admission a) {
        String value = values.get(key);
        if (value == null) {
            throw corrupt(what, a);
        }
        return value;
    }

    private static IllegalStateException corrupt(String what, Admission a) {
        return new IllegalStateException("Thiếu " + what + " của ca nội trú #" + a.getId());
    }
}
