package com.petcare.module.catalog.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.catalog.dto.CreateServiceRequest;
import com.petcare.module.catalog.dto.KennelTypeSpec;
import com.petcare.module.catalog.dto.ServiceResponse;
import com.petcare.module.catalog.dto.UpdateServiceRequest;
import com.petcare.module.catalog.entity.KennelType;
import com.petcare.module.catalog.entity.Service;
import com.petcare.module.catalog.exception.DuplicateCatalogEntryException;
import com.petcare.module.catalog.mapper.CatalogMapper;
import com.petcare.module.catalog.repository.KennelTypeRepository;
import com.petcare.module.catalog.repository.ServiceRepository;
import com.petcare.module.customer.api.Species;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * UC30 — dịch vụ, gồm loại chuồng (BR-SP-04) và loại Khám/Tiêm (BR-SP-06); đổi giá ghi audit (BR-QT-15). Nhóm dịch
 * vụ không đổi sau khi tạo (catalog-v1 A1).
 */
@org.springframework.stereotype.Service
public class ServiceCatalogService {

    static final BigDecimal MAX_KENNEL_WEIGHT_KG = new BigDecimal("9999.99");

    private final ServiceRepository services;
    private final KennelTypeRepository kennelTypes;
    private final AuditRecorder auditRecorder;
    private final CatalogMapper mapper;

    public ServiceCatalogService(ServiceRepository services, KennelTypeRepository kennelTypes,
            AuditRecorder auditRecorder, CatalogMapper mapper) {
        this.services = services;
        this.kennelTypes = kennelTypes;
        this.auditRecorder = auditRecorder;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<ServiceResponse> listServices(ServiceGroup group, Boolean isActive) {
        Specification<Service> spec = (root, query, cb) -> cb.and(
                group == null ? cb.conjunction() : cb.equal(root.get("group"), group),
                isActive == null ? cb.conjunction() : cb.equal(root.get("active"), isActive));
        List<Service> found = services.findAll(spec, Sort.by("name"));
        Map<Long, KennelType> kennels = kennelTypes
                .findAllById(found.stream().filter(s -> s.getGroup() == ServiceGroup.BOARDING).map(Service::getId)
                        .toList())
                .stream().collect(Collectors.toMap(KennelType::getServiceId, Function.identity()));
        return found.stream().map(s -> mapper.toResponse(s, kennels.get(s.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public ServiceResponse getService(Long serviceId) {
        Service service = find(serviceId);
        return mapper.toResponse(service, kennelOf(service));
    }

    @Transactional
    public ServiceResponse createService(CreateServiceRequest request) {
        String name = request.name().trim();
        if (services.existsByName(name)) {
            throw new DuplicateCatalogEntryException("Tên dịch vụ đã tồn tại");
        }
        ServiceGroup group = request.group();
        if (group == ServiceGroup.MEDICAL && request.medicalType() == null) {
            throw new BusinessRuleViolationException("BR-SP-06", "Dịch vụ nhóm Khám/Tiêm phải chọn loại Khám hoặc Tiêm");
        }
        if (group != ServiceGroup.MEDICAL && request.medicalType() != null) {
            throw new BusinessRuleViolationException("BR-SP-06", "Chỉ dịch vụ nhóm Khám/Tiêm mới có loại");
        }
        if (group != ServiceGroup.BOARDING && request.kennelType() != null) {
            throw new BusinessRuleViolationException("BR-SP-04", "Chỉ loại chuồng (nhóm Lưu trú) mới có thông số chuồng");
        }
        if (group == ServiceGroup.BOARDING) {
            checkBoardingPrice(request.price());
            checkKennelSpec(request.kennelType() == null ? null : request.kennelType().species(),
                    request.kennelType() == null ? null : request.kennelType().maxWeightKg());
        }

        Service service = services.save(new Service(name, group, request.medicalType(), request.price(),
                request.priceIsFrom() == null || request.priceIsFrom(), request.description()));
        KennelType kennel = null;
        if (group == ServiceGroup.BOARDING) {
            kennel = kennelTypes.save(new KennelType(service.getId(), request.kennelType().species(),
                    request.kennelType().maxWeightKg()));
        }
        return mapper.toResponse(service, kennel);
    }

    /** Sửa, đổi giá hoặc ngừng dịch vụ. Giá đêm đã snapshot ở đặt chỗ lưu trú không đổi (BR-LT-02). */
    @Transactional
    public ServiceResponse updateService(Long serviceId, UpdateServiceRequest request) {
        Service service = find(serviceId);
        ServiceGroup group = service.getGroup();
        long oldPrice = service.getPrice();

        if (request.name() != null) {
            String name = request.name().trim();
            if (services.existsByNameAndIdNot(name, serviceId)) {
                throw new DuplicateCatalogEntryException("Tên dịch vụ đã tồn tại");
            }
            service.setName(name);
        }
        if (request.medicalType() != null) {
            if (group != ServiceGroup.MEDICAL) {
                throw new BusinessRuleViolationException("BR-SP-06", "Chỉ dịch vụ nhóm Khám/Tiêm mới có loại");
            }
            service.setMedicalType(request.medicalType());
        }
        if (request.price() != null) {
            if (group == ServiceGroup.BOARDING) {
                checkBoardingPrice(request.price());
            }
            service.setPrice(request.price());
        }
        if (request.priceIsFrom() != null) {
            service.setPriceIsFrom(request.priceIsFrom());
        }
        if (request.description() != null) {
            service.setDescription(request.description());
        }
        if (request.isActive() != null) {
            service.setActive(request.isActive());
        }

        KennelType kennel = kennelOf(service);
        if (request.kennelType() != null) {
            if (group != ServiceGroup.BOARDING) {
                throw new BusinessRuleViolationException("BR-SP-04",
                        "Chỉ loại chuồng (nhóm Lưu trú) mới có thông số chuồng");
            }
            KennelTypeSpec spec = request.kennelType();
            Species species = spec.species() != null ? spec.species() : kennel.getSpecies();
            BigDecimal weight = spec.maxWeightKg() != null ? spec.maxWeightKg() : kennel.getMaxWeightKg();
            checkKennelSpec(species, weight);
            kennel.setSpecies(species);
            kennel.setMaxWeightKg(weight);
            kennel = kennelTypes.save(kennel);
        }

        Service saved = services.saveAndFlush(service);
        if (saved.getPrice() != oldPrice) {
            auditRecorder.record(AuditEntry.of(CatalogAuditActions.PRICE_CHANGED)
                    .entity("services", saved.getId())
                    .before(new PriceAuditSnapshot(oldPrice))
                    .after(new PriceAuditSnapshot(saved.getPrice())));
        }
        return mapper.toResponse(saved, kennel);
    }

    private Service find(Long serviceId) {
        return services.findById(serviceId).orElseThrow(() -> new ResourceNotFoundException("Dịch vụ", serviceId));
    }

    private KennelType kennelOf(Service service) {
        if (service.getGroup() != ServiceGroup.BOARDING) {
            return null;
        }
        return kennelTypes.findById(service.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Loại chuồng", service.getId()));
    }

    private static void checkBoardingPrice(long price) {
        if (price <= 0) {
            throw new BusinessRuleViolationException("BR-SP-04", "Giá theo đêm của loại chuồng phải lớn hơn 0");
        }
    }

    private static void checkKennelSpec(Species species, BigDecimal maxWeightKg) {
        if (species == null) {
            throw new BusinessRuleViolationException("BR-SP-04", "Loại chuồng phải có loài phù hợp");
        }
        if (maxWeightKg == null || maxWeightKg.signum() <= 0 || maxWeightKg.compareTo(MAX_KENNEL_WEIGHT_KG) > 0
                || maxWeightKg.stripTrailingZeros().scale() > 2) {
            throw new BusinessRuleViolationException("BR-SP-04",
                    "Cân nặng tối đa phải lớn hơn 0, không quá 9999,99 kg và có tối đa 2 chữ số thập phân");
        }
    }
}
