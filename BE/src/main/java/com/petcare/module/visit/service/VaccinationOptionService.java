package com.petcare.module.visit.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.ProtocolDose;
import com.petcare.module.catalog.api.CatalogQueryApi.VaccineTypeInfo;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.visit.dto.ProtocolDoseOptionResponse;
import com.petcare.module.visit.dto.VaccinationOptionsResponse;
import com.petcare.module.visit.dto.VaccinationRecordResponse;
import com.petcare.module.visit.dto.VaccineProductOptionResponse;
import com.petcare.module.visit.mapper.VaccinationOptionsMapper;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.module.visit.repository.VisitRepository.SummaryRow;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Gợi ý mũi và sản phẩm vaccine — visit-v1 #18 (UC49; BR-KB-04, BR-SP-02, 07, BR-KO-01; docs/adr/0073). Chỉ đọc,
 * không khóa, không audit. Thứ tự: role → lượt (404) → chi nhánh (403) → nhóm {@code GROOMING} (404) → người phụ trách
 * hiện tại (403) → loại vaccine (404) → đúng loài (400 BR-KB-04) → còn dùng (400 BR-SP-07) → đọc dữ liệu. Mọi trạng thái
 * lượt (A19). Một transaction {@code readOnly + REPEATABLE_READ} (khuôn #12) để mọi câu đọc cùng một snapshot.
 */
@Service
public class VaccinationOptionService {

    /** Hợp đồng #18: A07, VET được gán lượt. */
    static final Set<String> ROLES = Set.of("VET");

    private final BranchScope branchScope;
    private final VisitRepository visits;
    private final PetQueryApi pets;
    private final CatalogQueryApi catalog;
    private final StockQueryApi stock;
    private final VaccinationRecordReader doses;
    private final VaccinationOptionsMapper mapper;
    private final Clock clock;

    public VaccinationOptionService(BranchScope branchScope, VisitRepository visits, PetQueryApi pets,
            CatalogQueryApi catalog, StockQueryApi stock, VaccinationRecordReader doses, VaccinationOptionsMapper mapper,
            Clock clock) {
        this.branchScope = branchScope;
        this.visits = visits;
        this.pets = pets;
        this.catalog = catalog;
        this.stock = stock;
        this.doses = doses;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VaccinationOptionsResponse getOptions(Long visitId, Long vaccineTypeId) {
        SecurityPrincipal principal = branchScope.current();
        VisitDispatchService.requireRole(principal, ROLES);
        SummaryRow visit = visits.findSummary(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("lượt tiếp nhận", visitId));
        branchScope.check(visit.branchId());
        if (visit.group() == ServiceGroup.GROOMING) {
            throw new ResourceNotFoundException("lượt khám / tiêm", visitId);
        }
        if (!principal.accountId().equals(visit.assigneeId())) {
            throw new AccessDeniedScopeException("assignee:" + visit.assigneeId(), "account:" + principal.accountId());
        }

        VaccineTypeInfo type = catalog.findVaccineType(vaccineTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("loại vaccine", vaccineTypeId));
        PetSummary pet = pets.findPet(visit.petId())
                .orElseThrow(() -> new IllegalStateException("Thú cưng #" + visit.petId() + " của lượt biến mất"));
        requireUsableType(type, pet);

        List<ProtocolDose> protocol = catalog.findProtocol(pet.species(), type.vaccineTypeId());
        VaccinationRecordResponse lastDose = doses.latestOfType(pet.petId(), type.vaccineTypeId()).orElse(null);
        ProtocolDose suggested = suggest(protocol, lastDose == null ? null : lastDose.doseNumber());
        boolean underMinAge = suggested != null
                && underMinAge(pet.birthDate(), LocalDate.now(clock), suggested.minAgeWeeks());

        return mapper.toOptions(type.vaccineTypeId(),
                protocol.stream().map(p -> mapper.toDoseOption(p.protocolId(), p.doseNumber(), p.intervalDays(),
                        p.minAgeWeeks(), p.requiredForBoarding())).toList(),
                suggested == null ? null : suggested.protocolId(), lastDose,
                productsInStock(visit.branchId(), type.vaccineTypeId()), underMinAge);
    }

    /**
     * Loại vaccine phải đúng loài của thú (400 BR-KB-04) rồi còn sử dụng (400 BR-SP-07). Dùng chung với ghi mũi
     * ({@link VaccinationRecordService}, docs/adr/0074) để hai endpoint không lệch nhau.
     */
    static void requireUsableType(VaccineTypeInfo type, PetSummary pet) {
        if (type.species() != pet.species()) {
            throw new BusinessRuleViolationException("BR-KB-04", "Loại vaccine không áp dụng cho loài của thú");
        }
        if (!type.active()) {
            throw new BusinessRuleViolationException("BR-SP-07", "Loại vaccine đã ngừng sử dụng");
        }
    }

    /**
     * A18: chưa tiêm → mũi nhỏ nhất; đã tiêm → mũi nhỏ nhất lớn hơn số mũi đã chụp; hết mũi lớn hơn → mũi lớn nhất
     * (nhắc lại); phác đồ rỗng → {@code null}. {@code protocol} theo mũi tăng dần ({@code CatalogQueryApi.findProtocol}).
     */
    static ProtocolDose suggest(List<ProtocolDose> protocol, Integer lastDoseNumber) {
        if (protocol.isEmpty()) {
            return null;
        }
        if (lastDoseNumber == null) {
            return protocol.get(0);
        }
        return protocol.stream().filter(p -> p.doseNumber() > lastDoseNumber).findFirst()
                .orElse(protocol.get(protocol.size() - 1));
    }

    /** BR-KB-04 🆕: cảnh báo, không chặn. Không có ngày sinh thì không xét (A20); đúng bằng tuổi tối thiểu là đủ. */
    static boolean underMinAge(LocalDate birthDate, LocalDate today, int minAgeWeeks) {
        return birthDate != null && ChronoUnit.WEEKS.between(birthDate, today) < minAgeWeeks;
    }

    /**
     * Sản phẩm đang kinh doanh của loại, tồn khả dụng > 0 tại chi nhánh của lượt, theo tên rồi id. Lọc {@code active}
     * vì ghi mũi (#19, {@code VisitOrderApi}) từ chối sản phẩm ngừng kinh doanh.
     */
    private List<VaccineProductOptionResponse> productsInStock(Long branchId, Long vaccineTypeId) {
        List<ProductInfo> active = catalog.findProductsByVaccineType(vaccineTypeId).stream()
                .filter(ProductInfo::active).toList();
        if (active.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> available = stock.availableQuantities(branchId,
                active.stream().map(ProductInfo::productId).toList());
        return active.stream()
                .filter(p -> available.getOrDefault(p.productId(), 0) > 0)
                .sorted(Comparator.comparing(ProductInfo::name).thenComparing(ProductInfo::productId))
                .map(p -> mapper.toProductOption(p.productId(), p.name(), available.get(p.productId())))
                .toList();
    }
}
