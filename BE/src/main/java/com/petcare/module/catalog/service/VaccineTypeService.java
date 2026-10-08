package com.petcare.module.catalog.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.dto.UpdateVaccineTypeRequest;
import com.petcare.module.catalog.dto.VaccineTypeRequest;
import com.petcare.module.catalog.dto.VaccineTypeResponse;
import com.petcare.module.catalog.entity.VaccineType;
import com.petcare.module.catalog.exception.DuplicateCatalogEntryException;
import com.petcare.module.catalog.mapper.CatalogMapper;
import com.petcare.module.catalog.repository.ProductRepository;
import com.petcare.module.catalog.repository.VaccinationProtocolRepository;
import com.petcare.module.catalog.repository.VaccineTypeRepository;
import com.petcare.module.customer.api.Species;
import com.petcare.module.visit.api.VaccinationQueryApi;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

import jakarta.persistence.criteria.Predicate;

/** UC31 — loại vaccine (BR-SP-07). Đã có phác đồ, sản phẩm hoặc mũi tiêm thì không xóa, chỉ ngừng sử dụng. */
@Service
public class VaccineTypeService {

    private final VaccineTypeRepository vaccineTypes;
    private final VaccinationProtocolRepository protocols;
    private final ProductRepository products;
    private final VaccinationQueryApi vaccinations;
    private final CatalogMapper mapper;

    public VaccineTypeService(VaccineTypeRepository vaccineTypes, VaccinationProtocolRepository protocols,
            ProductRepository products, VaccinationQueryApi vaccinations, CatalogMapper mapper) {
        this.vaccineTypes = vaccineTypes;
        this.protocols = protocols;
        this.products = products;
        this.vaccinations = vaccinations;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<VaccineTypeResponse> listVaccineTypes(Species species, Boolean isActive) {
        Specification<VaccineType> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (species != null) {
                predicates.add(cb.equal(root.get("species"), species));
            }
            if (isActive != null) {
                predicates.add(cb.equal(root.get("active"), isActive));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return vaccineTypes.findAll(spec, Sort.by("species", "name")).stream().map(mapper::toResponse).toList();
    }

    @Transactional
    public VaccineTypeResponse createVaccineType(VaccineTypeRequest request) {
        String name = request.name().trim();
        if (vaccineTypes.existsByNameAndSpecies(name, request.species())) {
            throw new DuplicateCatalogEntryException("Loại vaccine đã tồn tại trong loài này");
        }
        return mapper.toResponse(vaccineTypes.save(new VaccineType(name, request.species())));
    }

    @Transactional
    public VaccineTypeResponse updateVaccineType(Long vaccineTypeId, UpdateVaccineTypeRequest request) {
        VaccineType vaccineType = find(vaccineTypeId);
        if (request.name() != null) {
            String name = request.name().trim();
            if (vaccineTypes.existsByNameAndSpeciesAndIdNot(name, vaccineType.getSpecies(), vaccineTypeId)) {
                throw new DuplicateCatalogEntryException("Loại vaccine đã tồn tại trong loài này");
            }
            vaccineType.setName(name);
        }
        if (request.isActive() != null) {
            vaccineType.setActive(request.isActive());
        }
        return mapper.toResponse(vaccineTypes.saveAndFlush(vaccineType));
    }

    @Transactional
    public void deleteVaccineType(Long vaccineTypeId) {
        VaccineType vaccineType = find(vaccineTypeId);
        if (protocols.existsByVaccineTypeId(vaccineTypeId) || products.existsByVaccineTypeId(vaccineTypeId)
                || vaccinations.existsByVaccineType(vaccineTypeId)) {
            throw new BusinessRuleViolationException("BR-SP-07",
                    "Loại vaccine đã có phác đồ, sản phẩm hoặc mũi tiêm nên không xóa được, chỉ ngừng sử dụng");
        }
        vaccineTypes.delete(vaccineType);
        vaccineTypes.flush();
    }

    private VaccineType find(Long vaccineTypeId) {
        return vaccineTypes.findById(vaccineTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("Loại vaccine", vaccineTypeId));
    }
}
