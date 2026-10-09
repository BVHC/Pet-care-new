package com.petcare.module.catalog.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.catalog.entity.Product;
import com.petcare.module.catalog.entity.VaccinationProtocol;
import com.petcare.module.catalog.repository.KennelTypeRepository;
import com.petcare.module.catalog.repository.ProductRepository;
import com.petcare.module.catalog.repository.ServiceRepository;
import com.petcare.module.catalog.repository.VaccinationProtocolRepository;
import com.petcare.module.customer.api.Species;

/** Cài đặt {@link CatalogQueryApi} (SP): chỉ đọc, trả record trong {@code api/} cho appointment, visit, boarding. */
@Service
@Transactional(readOnly = true)
public class CatalogQueryService implements CatalogQueryApi {

    private final ServiceRepository services;
    private final KennelTypeRepository kennelTypes;
    private final ProductRepository products;
    private final VaccinationProtocolRepository protocols;

    public CatalogQueryService(ServiceRepository services, KennelTypeRepository kennelTypes,
            ProductRepository products, VaccinationProtocolRepository protocols) {
        this.services = services;
        this.kennelTypes = kennelTypes;
        this.products = products;
        this.protocols = protocols;
    }

    @Override
    public Optional<ServiceInfo> findService(Long serviceId) {
        return services.findById(serviceId).map(CatalogQueryService::toInfo);
    }

    @Override
    public List<ServiceInfo> findServices(Collection<Long> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return List.of();
        }
        return services.findAllById(serviceIds).stream().map(CatalogQueryService::toInfo).toList();
    }

    @Override
    public List<ServiceInfo> listActiveServices() {
        return services.findAll((root, query, cb) -> cb.isTrue(root.get("active")), Sort.by("name")).stream()
                .map(CatalogQueryService::toInfo).toList();
    }

    @Override
    public Optional<ProductInfo> findProduct(Long productId) {
        return products.findById(productId).map(CatalogQueryService::toInfo);
    }

    /** Chỉ dịch vụ nhóm {@code BOARDING} có loại chuồng; giá đêm là giá của dịch vụ (BR-SP-04). */
    @Override
    public Optional<KennelTypeInfo> findKennelType(Long serviceId) {
        return services.findById(serviceId)
                .filter(s -> s.getGroup() == ServiceGroup.BOARDING)
                .flatMap(s -> kennelTypes.findById(serviceId)
                        .map(k -> new KennelTypeInfo(s.getId(), s.getName(), k.getSpecies(), k.getMaxWeightKg(),
                                s.getPrice(), s.isActive())));
    }

    @Override
    public Optional<ProtocolDose> findProtocolDose(Long protocolId) {
        return protocols.findById(protocolId).map(CatalogQueryService::toDose);
    }

    @Override
    public List<ProtocolDose> findProtocol(Species species, Long vaccineTypeId) {
        return protocols.findBySpeciesAndVaccineTypeIdAndActiveTrueOrderByDoseNumberAsc(species, vaccineTypeId)
                .stream().map(CatalogQueryService::toDose).toList();
    }

    @Override
    public List<Long> findBoardingRequiredVaccineTypeIds(Species species) {
        return protocols.findBySpeciesAndRequiredForBoardingTrueAndActiveTrue(species).stream()
                .map(VaccinationProtocol::getVaccineTypeId).distinct().sorted().toList();
    }

    private static ServiceInfo toInfo(com.petcare.module.catalog.entity.Service s) {
        return new ServiceInfo(s.getId(), s.getName(), s.getGroup(), s.getMedicalType(), s.getPrice(), s.isActive());
    }

    private static ProductInfo toInfo(Product p) {
        return new ProductInfo(p.getId(), p.getName(), p.getProductType(), p.isPrescription(), p.isTracksExpiry(),
                p.getVaccineTypeId(), p.getPrice(), p.isActive());
    }

    private static ProtocolDose toDose(VaccinationProtocol p) {
        return new ProtocolDose(p.getId(), p.getSpecies(), p.getVaccineTypeId(), p.getDoseNumber(),
                p.getIntervalDays(), p.getMinAgeWeeks(), p.isRequiredForBoarding(), p.isActive());
    }
}
