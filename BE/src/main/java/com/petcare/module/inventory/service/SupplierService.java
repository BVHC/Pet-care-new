package com.petcare.module.inventory.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.inventory.dto.SupplierRequest;
import com.petcare.module.inventory.dto.SupplierResponse;
import com.petcare.module.inventory.dto.UpdateSupplierRequest;
import com.petcare.module.inventory.entity.Supplier;
import com.petcare.module.inventory.mapper.SupplierMapper;
import com.petcare.module.inventory.repository.SupplierRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

import jakarta.persistence.criteria.Predicate;

/**
 * UC73 — nhà cung cấp (inventory-v1 #1–4; BR-KO-02; docs/adr/0042). Dùng chung toàn chuỗi (không {@code BranchScope});
 * BRANCH_MANAGER nào cũng quản lý được (contract A1). Không audit (convention 08 §8.3 không liệt kê).
 * <ul>
 *   <li>Sửa: khóa dòng rồi mới đọc; {@code null} giữ, địa chỉ rỗng xóa; tên và SĐT không xóa được.</li>
 *   <li>Xóa: {@code FOR UPDATE} dòng nhà cung cấp (xung đột với khóa FK của phiếu nhập đang chèn) → đã có <b>bất kỳ</b>
 *       phiếu nhập nào (nháp, đã xác nhận, đã hủy) → 400 BR-KO-02 → xóa cứng.</li>
 * </ul>
 */
@Service
public class SupplierService {

    private final SupplierRepository suppliers;
    private final SupplierMapper mapper;

    public SupplierService(SupplierRepository suppliers, SupplierMapper mapper) {
        this.suppliers = suppliers;
        this.mapper = mapper;
    }

    /** Sắp {@code name, id}; {@code q} khớp chứa trong tên hoặc SĐT, không phân biệt hoa thường. */
    @Transactional(readOnly = true)
    public List<SupplierResponse> listSuppliers(Boolean isActive, String q) {
        String needle = q == null || q.isBlank() ? null : q.strip().toLowerCase(Locale.ROOT);
        Specification<Supplier> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (isActive != null) {
                predicates.add(cb.equal(root.get("active"), isActive));
            }
            if (needle != null) {
                String pattern = "%" + needle.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("name")), pattern, '!'),
                        cb.like(root.get("phone"), pattern, '!')));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return mapper.toResponses(suppliers.findAll(spec, Sort.by("name", "id")));
    }

    @Transactional
    public SupplierResponse createSupplier(SupplierRequest request) {
        Supplier saved = suppliers.saveAndFlush(Supplier.create(request.name().strip(), request.phone(),
                Values.optional(request.address(), null)));
        return mapper.toResponse(saved);
    }

    @Transactional
    public SupplierResponse updateSupplier(Long supplierId, UpdateSupplierRequest request) {
        Supplier supplier = suppliers.findByIdForUpdate(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("nhà cung cấp", supplierId));
        supplier.update(Values.required(request.name(), supplier.getName()),
                request.phone() != null ? request.phone() : supplier.getPhone(),
                Values.optional(request.address(), supplier.getAddress()),
                request.isActive() != null ? request.isActive() : supplier.isActive());
        suppliers.flush();
        return mapper.toResponse(supplier);
    }

    @Transactional
    public void deleteSupplier(Long supplierId) {
        suppliers.lockForDelete(supplierId).orElseThrow(() -> new ResourceNotFoundException("nhà cung cấp", supplierId));
        if (suppliers.hasReceipts(supplierId)) {
            throw new BusinessRuleViolationException("BR-KO-02",
                    "Nhà cung cấp đã có phiếu nhập nên không xóa được; hãy chuyển sang ngừng hợp tác");
        }
        suppliers.deleteById(supplierId);
        suppliers.flush();
    }
}
