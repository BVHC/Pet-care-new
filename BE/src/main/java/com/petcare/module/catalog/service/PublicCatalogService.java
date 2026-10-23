package com.petcare.module.catalog.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.catalog.dto.KennelTypeSpec;
import com.petcare.module.catalog.dto.ProductCategoryResponse;
import com.petcare.module.catalog.dto.PublicProductResponse;
import com.petcare.module.catalog.dto.PublicServiceResponse;
import com.petcare.module.catalog.entity.KennelType;
import com.petcare.module.catalog.entity.Product;
import com.petcare.module.catalog.entity.ProductCategory;
import com.petcare.module.catalog.mapper.CatalogMapper;
import com.petcare.module.catalog.repository.KennelTypeRepository;
import com.petcare.module.catalog.repository.ProductCategoryRepository;
import com.petcare.module.catalog.repository.ProductRepository;
import com.petcare.module.catalog.repository.ServiceRepository;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;

/**
 * Dịch vụ và sản phẩm công khai (catalog-v1 #19–22; UC15, UC16; BR-CK-01, 02, 03, BR-SP-01). Chỉ đọc. Dịch vụ phải đang
 * kinh doanh và bật ở ít nhất một chi nhánh ACTIVE; sản phẩm phải đang kinh doanh, không phải thuốc kê đơn và thuộc
 * danh mục đang dùng (giả định A1 của T32). Chi nhánh còn hàng ghép theo lô cho cả trang (một câu SQL mỗi nguồn), chỉ
 * gồm chi nhánh ACTIVE, không bao giờ trả số lượng.
 */
@Service
@Transactional(readOnly = true)
public class PublicCatalogService {

    static final int MAX_PAGE_SIZE = 100;

    private final ServiceRepository services;
    private final KennelTypeRepository kennelTypes;
    private final ProductRepository products;
    private final ProductCategoryRepository categories;
    private final CatalogMapper mapper;
    private final BranchQueryApi branches;
    private final StockQueryApi stock;

    public PublicCatalogService(ServiceRepository services, KennelTypeRepository kennelTypes,
            ProductRepository products, ProductCategoryRepository categories, CatalogMapper mapper,
            BranchQueryApi branches, StockQueryApi stock) {
        this.services = services;
        this.kennelTypes = kennelTypes;
        this.products = products;
        this.categories = categories;
        this.mapper = mapper;
        this.branches = branches;
        this.stock = stock;
    }

    public List<PublicServiceResponse> listServices(ServiceGroup group, Long branchId) {
        Specification<com.petcare.module.catalog.entity.Service> spec = (root, query, cb) -> cb.and(
                cb.isTrue(root.get("active")),
                group == null ? cb.conjunction() : cb.equal(root.get("group"), group));
        List<com.petcare.module.catalog.entity.Service> active = services.findAll(spec, Sort.by("name", "id"));
        if (active.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Long>> enabledAt = branches.findActiveBranchIdsEnablingServices(
                active.stream().map(com.petcare.module.catalog.entity.Service::getId).toList());
        Map<Long, KennelType> kennels = kennelTypes.findAllById(active.stream()
                .filter(s -> s.getGroup() == ServiceGroup.BOARDING)
                .map(com.petcare.module.catalog.entity.Service::getId).toList()).stream()
                .collect(Collectors.toMap(KennelType::getServiceId, k -> k));
        List<PublicServiceResponse> result = new ArrayList<>();
        for (com.petcare.module.catalog.entity.Service service : active) {
            List<Long> branchIds = enabledAt.getOrDefault(service.getId(), List.of());
            if (branchIds.isEmpty() || (branchId != null && !branchIds.contains(branchId))) {
                continue;
            }
            KennelType kennel = kennels.get(service.getId());
            KennelTypeSpec spec2 = kennel == null ? null : mapper.toSpec(kennel);
            result.add(new PublicServiceResponse(service.getId(), service.getName(), service.getGroup(),
                    service.getPrice(), service.isPriceIsFrom(), service.getDescription(), spec2, branchIds));
        }
        return result;
    }

    public List<ProductCategoryResponse> listCategories() {
        return categories.findAll((root, query, cb) -> cb.isTrue(root.get("active")), Sort.by("name", "id"))
                .stream().map(mapper::toResponse).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<PublicProductResponse> listProducts(String q, Long categoryId, Long branchId, int page,
            int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by("name", "id"));
        Map<Long, String> activeCategories = categories.findAll((root, query, cb) -> cb.isTrue(root.get("active")))
                .stream().collect(Collectors.toMap(ProductCategory::getId, ProductCategory::getName));
        Set<Long> stockedHere = null;
        if (branchId != null) {
            boolean activeBranch = branches.findBranch(branchId)
                    .map(b -> b.status() == BranchStatus.ACTIVE).orElse(false);
            stockedHere = activeBranch ? stock.productIdsWithAvailableStock(branchId) : Set.of();
        }
        if (activeCategories.isEmpty() || (stockedHere != null && stockedHere.isEmpty())) {
            return PageResponse.of(new PageImpl<>(List.of(), pageable, 0));
        }
        Set<Long> allowedProducts = stockedHere;
        Specification<Product> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isTrue(root.get("active")));
            predicates.add(cb.isFalse(root.get("prescription")));
            predicates.add(root.get("categoryId").in(activeCategories.keySet()));
            if (q != null && !q.isBlank()) {
                String pattern = "%" + escapeLike(q.trim().toLowerCase()) + "%";
                predicates.add(cb.like(cb.lower(root.get("name")), pattern, '\\'));
            }
            if (categoryId != null) {
                predicates.add(cb.equal(root.get("categoryId"), categoryId));
            }
            if (allowedProducts != null) {
                predicates.add(root.get("id").in(allowedProducts));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        Page<Product> result = products.findAll(spec, pageable);
        Map<Long, List<Long>> stocked = stockedBranches(result.getContent());
        return PageResponse.of(result.map(p -> toPublic(p, activeCategories.get(p.getCategoryId()),
                stocked.getOrDefault(p.getId(), List.of()))));
    }

    public PublicProductResponse getProduct(Long productId) {
        Product product = products.findById(productId).filter(p -> p.isActive() && !p.isPrescription())
                .orElseThrow(() -> new ResourceNotFoundException("sản phẩm", productId));
        ProductCategory category = categories.findById(product.getCategoryId()).filter(ProductCategory::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("sản phẩm", productId));
        return toPublic(product, category.getName(),
                stockedBranches(List.of(product)).getOrDefault(product.getId(), List.of()));
    }

    /** Chi nhánh còn hàng của các sản phẩm, đã bỏ chi nhánh không ACTIVE. */
    private Map<Long, List<Long>> stockedBranches(List<Product> page) {
        if (page.isEmpty()) {
            return Map.of();
        }
        Set<Long> activeBranchIds = new HashSet<>();
        for (BranchSummary b : branches.listActiveBranches()) {
            activeBranchIds.add(b.branchId());
        }
        Map<Long, List<Long>> raw = stock.branchIdsWithAvailableStock(page.stream().map(Product::getId).toList());
        return raw.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                e -> e.getValue().stream().filter(activeBranchIds::contains).toList()));
    }

    private static PublicProductResponse toPublic(Product p, String categoryName, List<Long> inStockBranchIds) {
        return new PublicProductResponse(p.getId(), p.getName(), p.getCategoryId(), categoryName, p.getPrice(),
                p.getUnit(), p.getDescription(), p.getImageUrl(), inStockBranchIds);
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
