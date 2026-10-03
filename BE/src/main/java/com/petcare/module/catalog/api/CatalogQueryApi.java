package com.petcare.module.catalog.api;

import com.petcare.module.customer.api.Species;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Owner: catalog (SP) · BE-2. Chỉ đọc danh mục sản phẩm, dịch vụ, loại chuồng, loại vaccine, phác đồ. */
public interface CatalogQueryApi {

    record ServiceInfo(Long serviceId, String name, ServiceGroup group, MedicalType medicalType,
                       long price, boolean active) {}

    record ProductInfo(Long productId, String name, ProductType type, boolean prescription,
                       boolean tracksExpiry, Long vaccineTypeId, long price, boolean active) {}

    /** Loại chuồng là Service nhóm BOARDING; giá đêm = giá của Service (BR-SP-04). */
    record KennelTypeInfo(Long serviceId, String name, Species species, BigDecimal maxWeightKg,
                          long nightlyPrice, boolean active) {}

    record ProtocolDose(Long protocolId, Species species, Long vaccineTypeId, int doseNumber,
                        int intervalDays, int minAgeWeeks, boolean requiredForBoarding, boolean active) {}

    Optional<ServiceInfo> findService(Long serviceId);

    List<ServiceInfo> findServices(Collection<Long> serviceIds);

    Optional<ProductInfo> findProduct(Long productId);

    Optional<KennelTypeInfo> findKennelType(Long serviceId);

    Optional<ProtocolDose> findProtocolDose(Long protocolId);

    /** Các dòng phác đồ đang dùng của một loài × loại vaccine, theo mũi tăng dần (BR-SP-02, BR-KB-04). */
    List<ProtocolDose> findProtocol(Species species, Long vaccineTypeId);

    /** Loại vaccine có ít nhất 1 dòng phác đồ cờ bắt buộc khi lưu trú của loài (BR-LT-05). */
    List<Long> findBoardingRequiredVaccineTypeIds(Species species);
}
