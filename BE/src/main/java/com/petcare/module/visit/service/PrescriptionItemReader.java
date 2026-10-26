package com.petcare.module.visit.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.visit.dto.PrescriptionItemResponse;
import com.petcare.module.visit.entity.PrescriptionItem;
import com.petcare.module.visit.mapper.MedicalRecordMapper;
import com.petcare.module.visit.repository.PrescriptionItemRepository;

/**
 * Đơn thuốc của một bệnh án dưới dạng DTO (visit-v1 {@code PrescriptionItem}; docs/adr/0076). Khuôn
 * {@link VaccinationRecordReader}: chạy trong transaction của bên gọi, không {@code @Transactional} riêng, không khóa, không
 * {@code catch}; bên gọi đã kiểm role, chi nhánh. Đúng 2 câu bất kể số dòng (đọc đơn thuốc, tra tên sản phẩm theo lô) và
 * không câu catalog nào khi bệnh án chưa có đơn. Catalog không lọc {@code active}: thuốc đã ngừng kinh doanh vẫn có tên
 * trên đơn cũ. Tên là tên hiện tại (catalog không chụp tên).
 */
@Component
class PrescriptionItemReader {

    private final PrescriptionItemRepository items;
    private final CatalogQueryApi catalog;
    private final MedicalRecordMapper mapper;

    PrescriptionItemReader(PrescriptionItemRepository items, CatalogQueryApi catalog, MedicalRecordMapper mapper) {
        this.items = items;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    /** Đơn thuốc của bệnh án (khóa chính = id lượt) theo thứ tự kê, gồm dòng mua ngoài; không bao giờ {@code null}. */
    List<PrescriptionItemResponse> forRecord(Long visitId) {
        List<PrescriptionItem> rows = items.findByMedicalRecordIdOrderByIdAsc(visitId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = catalog
                .findProducts(rows.stream().map(PrescriptionItem::getProductId).distinct().toList()).stream()
                .collect(Collectors.toMap(ProductInfo::productId, ProductInfo::name, (a, b) -> a));
        return rows.stream().map(row -> mapper.toPrescriptionItem(row, require(names, row.getProductId()))).toList();
    }

    /** FK {@code RESTRICT} bảo đảm luôn có; thiếu là dữ liệu hỏng (500), không phải lỗi của người dùng. */
    private static String require(Map<Long, String> names, Long productId) {
        String name = names.get(productId);
        if (name == null) {
            throw new IllegalStateException("Thuốc #" + productId + " của đơn không còn trong danh mục");
        }
        return name;
    }
}
