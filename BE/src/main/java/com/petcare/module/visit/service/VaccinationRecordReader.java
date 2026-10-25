package com.petcare.module.visit.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.visit.dto.VaccinationRecordResponse;
import com.petcare.module.visit.entity.Vaccination;
import com.petcare.module.visit.mapper.MedicalRecordMapper;
import com.petcare.module.visit.repository.VaccinationRepository;

/**
 * Mũi tiêm của một lượt dưới dạng DTO (visit-v1 {@code VaccinationRecord}; docs/adr/0072). Chạy trong transaction của
 * bên gọi, không {@code @Transactional} riêng, không khóa, không {@code catch}. Chỉ mũi của chính lượt ({@code pet_id +
 * visit_id}); bên gọi đã kiểm role, chi nhánh và người phụ trách. Tên loại vaccine / sản phẩm tra theo lô, tối đa 2 câu
 * catalog mỗi lần và không câu nào khi lượt chưa có mũi; catalog không lọc {@code active} nên loại / sản phẩm đã ngừng
 * vẫn có tên.
 */
@Component
class VaccinationRecordReader {

    private final VaccinationRepository vaccinations;
    private final CatalogQueryApi catalog;
    private final MedicalRecordMapper mapper;

    VaccinationRecordReader(VaccinationRepository vaccinations, CatalogQueryApi catalog, MedicalRecordMapper mapper) {
        this.vaccinations = vaccinations;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    /** Mũi tiêm của lượt theo thứ tự ghi; không bao giờ {@code null}. */
    List<VaccinationRecordResponse> forVisit(Long petId, Long visitId) {
        return toRecords(vaccinations.findByPetIdAndVisitIdOrderByIdAsc(petId, visitId));
    }

    /** Mũi gần nhất của thú cùng loại vaccine, ở mọi chi nhánh (visit-v1 #18, docs/adr/0073); chưa tiêm thì rỗng. */
    Optional<VaccinationRecordResponse> latestOfType(Long petId, Long vaccineTypeId) {
        return vaccinations.findFirstByPetIdAndVaccineTypeIdOrderByAdministeredOnDescIdDesc(petId, vaccineTypeId)
                .map(row -> toRecords(List.of(row)).get(0));
    }

    private List<VaccinationRecordResponse> toRecords(List<Vaccination> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> typeNames = catalog
                .findVaccineTypeNames(rows.stream().map(Vaccination::getVaccineTypeId).distinct().toList());
        Map<Long, String> productNames = catalog
                .findProducts(rows.stream().map(Vaccination::getProductId).distinct().toList()).stream()
                .collect(Collectors.toMap(ProductInfo::productId, ProductInfo::name, (a, b) -> a));
        return rows.stream().map(row -> mapper.toVaccinationRecord(row,
                require(typeNames, row.getVaccineTypeId(), "Loại vaccine"),
                require(productNames, row.getProductId(), "Sản phẩm"))).toList();
    }

    /** FK {@code RESTRICT} bảo đảm luôn có; thiếu là dữ liệu hỏng (500), không phải lỗi của người dùng. */
    private static String require(Map<Long, String> names, Long id, String what) {
        String name = names.get(id);
        if (name == null) {
            throw new IllegalStateException(what + " #" + id + " của mũi tiêm không còn trong danh mục");
        }
        return name;
    }
}
