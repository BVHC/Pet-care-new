package com.petcare.module.catalog.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.dto.CreateProtocolRequest;
import com.petcare.module.catalog.dto.UpdateProtocolRequest;
import com.petcare.module.catalog.dto.VaccinationProtocolResponse;
import com.petcare.module.catalog.entity.VaccinationProtocol;
import com.petcare.module.catalog.entity.VaccineType;
import com.petcare.module.catalog.exception.DuplicateCatalogEntryException;
import com.petcare.module.catalog.mapper.CatalogMapper;
import com.petcare.module.catalog.repository.VaccinationProtocolRepository;
import com.petcare.module.catalog.repository.VaccineTypeRepository;
import com.petcare.module.customer.api.Species;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

import jakarta.persistence.criteria.Predicate;

/**
 * UC31 — phác đồ tiêm chủng (BR-SP-02). BR-SP-03 không cần mã riêng: ngày tái chủng đã tính được lưu ở mũi tiêm
 * (erd §4), nên sửa dòng phác đồ chỉ ảnh hưởng mũi tiêm sau đó.
 */
@Service
public class VaccinationProtocolService {

    /** {@code dose_number} và {@code min_age_weeks} là SMALLINT. */
    static final int MAX_SMALLINT = Short.MAX_VALUE;

    private final VaccinationProtocolRepository protocols;
    private final VaccineTypeRepository vaccineTypes;
    private final CatalogMapper mapper;

    public VaccinationProtocolService(VaccinationProtocolRepository protocols, VaccineTypeRepository vaccineTypes,
            CatalogMapper mapper) {
        this.protocols = protocols;
        this.vaccineTypes = vaccineTypes;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<VaccinationProtocolResponse> listProtocols(Species species, Long vaccineTypeId, Boolean isActive) {
        Specification<VaccinationProtocol> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (species != null) {
                predicates.add(cb.equal(root.get("species"), species));
            }
            if (vaccineTypeId != null) {
                predicates.add(cb.equal(root.get("vaccineTypeId"), vaccineTypeId));
            }
            if (isActive != null) {
                predicates.add(cb.equal(root.get("active"), isActive));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return protocols.findAll(spec, Sort.by("species", "vaccineTypeId", "doseNumber")).stream()
                .map(mapper::toResponse).toList();
    }

    @Transactional
    public VaccinationProtocolResponse createProtocol(CreateProtocolRequest request) {
        VaccineType vaccineType = vaccineTypes.findById(request.vaccineTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Loại vaccine", request.vaccineTypeId()));
        if (vaccineType.getSpecies() != request.species()) {
            throw new BusinessRuleViolationException("BR-SP-02", "Loài của phác đồ phải trùng loài của loại vaccine");
        }
        checkDoseNumber(request.doseNumber());
        checkIntervalDays(request.intervalDays());
        checkMinAgeWeeks(request.minAgeWeeks());
        if (protocols.existsBySpeciesAndVaccineTypeIdAndDoseNumber(request.species(), request.vaccineTypeId(),
                request.doseNumber())) {
            throw new DuplicateCatalogEntryException("Phác đồ đã có mũi thứ " + request.doseNumber()
                    + " của loại vaccine này");
        }
        return mapper.toResponse(protocols.save(new VaccinationProtocol(request.species(), request.vaccineTypeId(),
                request.doseNumber(), request.intervalDays(), request.minAgeWeeks(),
                request.requiredForBoarding())));
    }

    /** Loài, loại vaccine, mũi thứ không đổi được (catalog-v1 A3). */
    @Transactional
    public VaccinationProtocolResponse updateProtocol(Long protocolId, UpdateProtocolRequest request) {
        VaccinationProtocol protocol = protocols.findById(protocolId)
                .orElseThrow(() -> new ResourceNotFoundException("Phác đồ tiêm chủng", protocolId));
        if (request.intervalDays() != null) {
            checkIntervalDays(request.intervalDays());
            protocol.setIntervalDays(request.intervalDays());
        }
        if (request.minAgeWeeks() != null) {
            checkMinAgeWeeks(request.minAgeWeeks());
            protocol.setMinAgeWeeks(request.minAgeWeeks());
        }
        if (request.requiredForBoarding() != null) {
            protocol.setRequiredForBoarding(request.requiredForBoarding());
        }
        if (request.isActive() != null) {
            protocol.setActive(request.isActive());
        }
        return mapper.toResponse(protocols.saveAndFlush(protocol));
    }

    private static void checkDoseNumber(int doseNumber) {
        if (doseNumber < 1 || doseNumber > MAX_SMALLINT) {
            throw new BusinessRuleViolationException("BR-SP-02", "Mũi thứ mấy phải từ 1 đến " + MAX_SMALLINT);
        }
    }

    private static void checkIntervalDays(int intervalDays) {
        if (intervalDays <= 0) {
            throw new BusinessRuleViolationException("BR-SP-02", "Khoảng cách đến mũi kế tiếp phải lớn hơn 0 ngày");
        }
    }

    private static void checkMinAgeWeeks(int minAgeWeeks) {
        if (minAgeWeeks < 0 || minAgeWeeks > MAX_SMALLINT) {
            throw new BusinessRuleViolationException("BR-SP-02", "Tuổi tối thiểu phải từ 0 đến " + MAX_SMALLINT + " tuần");
        }
    }
}
