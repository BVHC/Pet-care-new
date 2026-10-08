package com.petcare.module.catalog.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.catalog.dto.CreateProductRequest;
import com.petcare.module.catalog.dto.ProductResponse;
import com.petcare.module.catalog.dto.UpdateProductRequest;
import com.petcare.module.catalog.entity.Product;
import com.petcare.module.catalog.exception.DuplicateCatalogEntryException;
import com.petcare.module.catalog.mapper.CatalogMapper;
import com.petcare.module.catalog.repository.ProductCategoryRepository;
import com.petcare.module.catalog.repository.ProductRepository;
import com.petcare.module.catalog.repository.VaccineTypeRepository;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;

import jakarta.persistence.criteria.Predicate;

/** UC29 — sản phẩm (BR-SP-01, BR-SP-05, BR-SP-07); đổi giá ghi audit (BR-QT-15). */
@Service
public class ProductService {

    static final int MAX_PAGE_SIZE = 100;

    private final ProductRepository products;
    private final ProductCategoryRepository categories;
    private final VaccineTypeRepository vaccineTypes;
    private final StockQueryApi stock;
    private final AuditRecorder auditRecorder;
    private final CatalogMapper mapper;

    public ProductService(ProductRepository products, ProductCategoryRepository categories,
            VaccineTypeRepository vaccineTypes, StockQueryApi stock, AuditRecorder auditRecorder,
            CatalogMapper mapper) {
        this.products = products;
        this.categories = categories;
        this.vaccineTypes = vaccineTypes;
        this.stock = stock;
        this.auditRecorder = auditRecorder;
        this.mapper = mapper;
    }

    /** {@code retailOnly} bỏ thuốc kê đơn, cho POS (BR-SP-01, catalog-v1 A4). */
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> listProducts(String q, Long categoryId, ProductType productType,
            Long vaccineTypeId, Boolean isActive, boolean retailOnly, int page, int size) {
        Specification<Product> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String pattern = "%" + escapeLike(q.trim().toLowerCase()) + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("name")), pattern, '\\'),
                        cb.like(cb.lower(root.get("sku")), pattern, '\\')));
            }
            if (categoryId != null) {
                predicates.add(cb.equal(root.get("categoryId"), categoryId));
            }
            if (productType != null) {
                predicates.add(cb.equal(root.get("productType"), productType));
            }
            if (vaccineTypeId != null) {
                predicates.add(cb.equal(root.get("vaccineTypeId"), vaccineTypeId));
            }
            if (isActive != null) {
                predicates.add(cb.equal(root.get("active"), isActive));
            }
            if (retailOnly) {
                predicates.add(cb.isFalse(root.get("prescription")));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<ProductResponse> result = products
                .findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                        Sort.by("name").and(Sort.by("id"))))
                .map(mapper::toResponse);
        return PageResponse.of(result);
    }

    @Transactional(readOnly = true)
    public ProductResponse getProduct(Long productId) {
        return mapper.toResponse(find(productId));
    }

    @Transactional
    public ProductResponse createProduct(CreateProductRequest request) {
        String sku = request.sku().trim();
        if (products.existsBySku(sku)) {
            throw new DuplicateCatalogEntryException("SKU đã tồn tại");
        }
        requireCategory(request.categoryId());
        checkProductRules(request.productType(), request.isPrescription(), request.tracksExpiry(),
                request.vaccineTypeId());
        Product product = new Product(request.categoryId(), sku, request.name().trim(), request.productType(),
                request.isPrescription(), request.tracksExpiry(), request.vaccineTypeId(), request.unit().trim(),
                request.price(), request.description(), request.imageUrl());
        return mapper.toResponse(products.save(product));
    }

    /** Sửa, đổi giá hoặc ngừng kinh doanh. Dòng Order đã có giữ giá snapshot (BR-BH-03). */
    @Transactional
    public ProductResponse updateProduct(Long productId, UpdateProductRequest request) {
        Product product = find(productId);
        long oldPrice = product.getPrice();
        boolean hadTrackedExpiry = product.isTracksExpiry();

        if (request.categoryId() != null && !request.categoryId().equals(product.getCategoryId())) {
            requireCategory(request.categoryId());
            product.setCategoryId(request.categoryId());
        }
        if (request.name() != null) {
            product.setName(request.name().trim());
        }
        if (request.isPrescription() != null) {
            product.setPrescription(request.isPrescription());
        }
        if (request.tracksExpiry() != null) {
            product.setTracksExpiry(request.tracksExpiry());
        }
        if (request.vaccineTypeId() != null) {
            product.setVaccineTypeId(request.vaccineTypeId());
        }
        if (request.unit() != null) {
            product.setUnit(request.unit().trim());
        }
        if (request.price() != null) {
            product.setPrice(request.price());
        }
        if (request.description() != null) {
            product.setDescription(request.description());
        }
        if (request.imageUrl() != null) {
            product.setImageUrl(request.imageUrl());
        }
        if (request.isActive() != null) {
            product.setActive(request.isActive());
        }

        checkProductRules(product.getProductType(), product.isPrescription(), product.isTracksExpiry(),
                product.getVaccineTypeId());
        if (hadTrackedExpiry && !product.isTracksExpiry() && stock.hasStockAnywhere(productId)) {
            throw new BusinessRuleViolationException("BR-SP-05",
                    "Không tắt được quản lý hạn dùng khi sản phẩm còn tồn ở chi nhánh");
        }

        Product saved = products.saveAndFlush(product);
        if (saved.getPrice() != oldPrice) {
            auditRecorder.record(AuditEntry.of(CatalogAuditActions.PRICE_CHANGED)
                    .entity("products", saved.getId())
                    .before(new PriceAuditSnapshot(oldPrice))
                    .after(new PriceAuditSnapshot(saved.getPrice())));
        }
        return mapper.toResponse(saved);
    }

    private Product find(Long productId) {
        return products.findById(productId).orElseThrow(() -> new ResourceNotFoundException("Sản phẩm", productId));
    }

    private void requireCategory(Long categoryId) {
        if (!categories.existsById(categoryId)) {
            throw new ResourceNotFoundException("Danh mục sản phẩm", categoryId);
        }
    }

    /** Thứ tự guard: BR-SP-01, BR-SP-05, BR-SP-07 (khớp CHECK trong V1). */
    private void checkProductRules(ProductType type, boolean prescription, boolean tracksExpiry, Long vaccineTypeId) {
        if (prescription && type != ProductType.DRUG) {
            throw new BusinessRuleViolationException("BR-SP-01", "Chỉ thuốc mới được đánh dấu thuốc kê đơn");
        }
        if ((prescription || type == ProductType.VACCINE) && !tracksExpiry) {
            throw new BusinessRuleViolationException("BR-SP-05",
                    "Thuốc kê đơn và vaccine bắt buộc quản lý hạn dùng");
        }
        if (type == ProductType.VACCINE) {
            if (vaccineTypeId == null) {
                throw new BusinessRuleViolationException("BR-SP-07", "Sản phẩm vaccine phải gắn loại vaccine");
            }
            if (!vaccineTypes.existsById(vaccineTypeId)) {
                throw new BusinessRuleViolationException("BR-SP-07", "Loại vaccine không tồn tại");
            }
        } else if (vaccineTypeId != null) {
            throw new BusinessRuleViolationException("BR-SP-07", "Chỉ sản phẩm vaccine mới gắn loại vaccine");
        }
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
