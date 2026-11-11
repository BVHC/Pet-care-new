package com.petcare.module.ai.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.ai.dto.ItemProfile;
import com.petcare.module.ai.dto.ItemProfileRow;
import com.petcare.module.ai.dto.ItemType;
import com.petcare.module.ai.dto.RecoGroup;
import com.petcare.module.ai.entity.AiItemProfile;
import com.petcare.module.ai.entity.ProfileCriteria;
import com.petcare.module.ai.exception.ProfileValidationException;
import com.petcare.module.ai.repository.AiItemProfileRepository;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;

/**
 * Hồ sơ gợi ý của dịch vụ thẩm mỹ và sản phẩm {@code GOODS} (UC94, BR-AI-17, 18, 20, docs/adr/0133). Danh sách lấy từ
 * {@code CatalogQueryApi.listRecommendable} (loại chuồng dùng {@code kennel_types} nên không có ở đây). PUT ghi đè TOÀN BỘ hồ
 * sơ; mọi trường rỗng là "không có hồ sơ" (BR-AI-20) nên xóa dòng, để {@code missingOnly} và {@code hasProfile} luôn nhất quán.
 * Ghi giống hệt hiện có thì không ghi, không audit (không phải một thay đổi); ghi thật thì audit cùng transaction và xóa bộ
 * nhớ đệm gợi ý SAU commit (xóa trước commit thì một request khác kịp nạp lại dữ liệu cũ).
 */
@Service
public class ItemProfileService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AiItemProfileRepository profiles;
    private final CatalogQueryApi catalog;
    private final AuditRecorder auditRecorder;
    private final BranchScope scope;
    private final RecommendationCache cache;

    public ItemProfileService(AiItemProfileRepository profiles, CatalogQueryApi catalog, AuditRecorder auditRecorder,
            BranchScope scope, RecommendationCache cache) {
        this.profiles = profiles;
        this.catalog = catalog;
        this.auditRecorder = auditRecorder;
        this.scope = scope;
        this.cache = cache;
    }

    // ---------------------------------------------------------------- đọc

    /** ai-v1 #12. Dịch vụ rồi sản phẩm, mỗi nhóm theo thứ tự của catalog (tên rồi id). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<ItemProfileRow> list(ItemType itemType, Boolean missingOnly, String q, int page, int size) {
        CatalogQueryApi.Recommendable source = catalog.listRecommendable();
        List<ServiceInfo> services = source.groomingServices().stream().filter(s -> s.group() == ServiceGroup.GROOMING).toList();
        List<ProductInfo> goods = source.products().stream()
                .filter(p -> p.type() == ProductType.GOODS && !p.prescription()).toList();
        Map<Long, ProfileCriteria> serviceProfiles = services.isEmpty() ? Map.of()
                : criteriaOf(profiles.findByServiceIdIn(services.stream().map(ServiceInfo::serviceId).toList()),
                        AiItemProfile::getServiceId);
        Map<Long, ProfileCriteria> productProfiles = goods.isEmpty() ? Map.of()
                : criteriaOf(profiles.findByProductIdIn(goods.stream().map(ProductInfo::productId).toList()),
                        AiItemProfile::getProductId);

        List<ItemProfileRow> rows = new ArrayList<>();
        services.forEach(s -> rows.add(row(ItemType.SERVICE, s.serviceId(), s.name(), RecoGroup.GROOMING,
                serviceProfiles.get(s.serviceId()))));
        goods.forEach(p -> rows.add(row(ItemType.PRODUCT, p.productId(), p.name(), RecoGroup.PRODUCT,
                productProfiles.get(p.productId()))));

        String needle = q == null ? "" : PlainText.normalize(q.trim());
        List<ItemProfileRow> matching = rows.stream()
                .filter(r -> itemType == null || r.itemType() == itemType)
                .filter(r -> !Boolean.TRUE.equals(missingOnly) || !r.hasProfile())
                .filter(r -> needle.isEmpty() || PlainText.normalize(r.name()).contains(needle)).toList();

        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);
        int from = (int) Math.min((long) pageNumber * pageSize, matching.size());
        int to = Math.min(from + pageSize, matching.size());
        return PageResponse.of(new PageImpl<>(matching.subList(from, to), PageRequest.of(pageNumber, pageSize),
                matching.size()));
    }

    // ---------------------------------------------------------------- ghi

    /** ai-v1 #13: 404 → BR-AI-18 (400) → luật chéo trường (400) → ghi. */
    @Transactional
    public ItemProfileRow save(ItemType itemType, Long itemId, ItemProfile body) {
        Item item = resolve(itemType, itemId);
        ProfileCriteria criteria = normalized(body);
        Optional<AiItemProfile> existing = itemType == ItemType.SERVICE ? profiles.findByServiceId(itemId)
                : profiles.findByProductId(itemId);
        ProfileCriteria before = existing.map(AiItemProfile::criteria).filter(c -> !c.isEmpty()).orElse(null);

        if (criteria.isEmpty()) {
            existing.ifPresent(row -> {
                profiles.delete(row);
                audit(AiAuditActions.ITEM_PROFILE_CLEARED, row.getId(), itemType, itemId, before, null);
                clearRecommendationsAfterCommit();
            });
            return row(itemType, itemId, item.name(), item.group(), null);
        }
        if (before != null && before.sameAs(criteria)) {
            return row(itemType, itemId, item.name(), item.group(), before);
        }
        Long actor = scope.current().accountId();
        AiItemProfile saved;
        if (existing.isPresent()) {
            saved = existing.get();
            saved.apply(criteria, actor);
        } else {
            saved = itemType == ItemType.SERVICE ? AiItemProfile.forService(itemId, criteria, actor)
                    : AiItemProfile.forProduct(itemId, criteria, actor);
        }
        profiles.saveAndFlush(saved);
        audit(AiAuditActions.ITEM_PROFILE_SAVED, saved.getId(), itemType, itemId, before, criteria);
        clearRecommendationsAfterCommit();
        return row(itemType, itemId, item.name(), item.group(), criteria);
    }

    // ---------------------------------------------------------------- phụ trợ

    private record Item(String name, RecoGroup group) {
    }

    /** Chỉ dịch vụ thẩm mỹ và sản phẩm {@code GOODS} không kê đơn được có hồ sơ (BR-AI-18); đang kinh doanh hay không thì không cần. */
    private Item resolve(ItemType itemType, Long itemId) {
        if (itemType == ItemType.SERVICE) {
            ServiceInfo service = catalog.findService(itemId).orElseThrow(() -> new ResourceNotFoundException("dịch vụ", itemId));
            if (service.group() == ServiceGroup.MEDICAL) {
                throw new BusinessRuleViolationException("BR-AI-18", "Dịch vụ khám, tiêm không có hồ sơ gợi ý");
            }
            if (service.group() == ServiceGroup.BOARDING) {
                throw new BusinessRuleViolationException("BR-AI-18",
                        "Loại chuồng dùng loài và cân nặng tối đa của danh mục, không có hồ sơ gợi ý");
            }
            return new Item(service.name(), RecoGroup.GROOMING);
        }
        ProductInfo product = catalog.findProduct(itemId).orElseThrow(() -> new ResourceNotFoundException("sản phẩm", itemId));
        if (product.type() != ProductType.GOODS || product.prescription()) {
            throw new BusinessRuleViolationException("BR-AI-18", "Thuốc, vaccine và sản phẩm kê đơn không có hồ sơ gợi ý");
        }
        return new Item(product.name(), RecoGroup.PRODUCT);
    }

    /**
     * Từ khóa giống chuẩn hóa như lúc so khớp (chữ thường không dấu, bỏ trống, bỏ trùng); {@code min > max} là 400. Cân nặng
     * giữ nguyên scale: {@code @Digits} đã giới hạn hai chữ số thập phân và {@link ProfileCriteria#sameAs} so theo giá trị.
     */
    private static ProfileCriteria normalized(ItemProfile body) {
        ProfileCriteria raw = body.toCriteria();
        if (raw.minAgeWeeks() != null && raw.maxAgeWeeks() != null && raw.minAgeWeeks() > raw.maxAgeWeeks()) {
            throw new ProfileValidationException("Tuổi tối thiểu không được lớn hơn tuổi tối đa");
        }
        if (raw.minWeightKg() != null && raw.maxWeightKg() != null && raw.minWeightKg().compareTo(raw.maxWeightKg()) > 0) {
            throw new ProfileValidationException("Cân nặng tối thiểu không được lớn hơn cân nặng tối đa");
        }
        List<String> keywords = raw.breedKeywords().stream().filter(k -> k != null).map(k -> PlainText.normalize(k).trim())
                .filter(k -> !k.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new)).stream().toList();
        return new ProfileCriteria(raw.species(), raw.minAgeWeeks(), raw.maxAgeWeeks(), raw.minWeightKg(),
                raw.maxWeightKg(), keywords, raw.repeatIntervalDays());
    }

    private static ItemProfileRow row(ItemType type, Long id, String name, RecoGroup group, ProfileCriteria criteria) {
        boolean has = criteria != null && !criteria.isEmpty();
        return new ItemProfileRow(type, id, name, group, has, has ? ItemProfile.from(criteria) : null);
    }

    private static Map<Long, ProfileCriteria> criteriaOf(Collection<AiItemProfile> rows, Function<AiItemProfile, Long> idOf) {
        return rows.stream().collect(Collectors.toMap(idOf, AiItemProfile::criteria, (a, b) -> a));
    }

    /** Ảnh chụp để audit (BR-AI-17): luôn là Map, không bao giờ là entity (docs/adr/0001). */
    private static Map<String, Object> snapshot(ItemType type, Long itemId, ProfileCriteria c) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("itemType", type.name());
        snapshot.put("itemId", itemId);
        snapshot.put("species", c.species() == null ? null : c.species().name());
        snapshot.put("minAgeWeeks", c.minAgeWeeks());
        snapshot.put("maxAgeWeeks", c.maxAgeWeeks());
        snapshot.put("minWeightKg", c.minWeightKg() == null ? null : c.minWeightKg().toPlainString());
        snapshot.put("maxWeightKg", c.maxWeightKg() == null ? null : c.maxWeightKg().toPlainString());
        snapshot.put("breedKeywords", c.breedKeywords());
        snapshot.put("repeatIntervalDays", c.repeatIntervalDays());
        return snapshot;
    }

    private void audit(String action, Long profileId, ItemType type, Long itemId, ProfileCriteria before, ProfileCriteria after) {
        AuditEntry entry = AuditEntry.of(action).entity(AiAuditActions.PROFILE_ENTITY_TYPE, profileId);
        if (before != null) {
            entry = entry.before(snapshot(type, itemId, before));
        }
        if (after != null) {
            entry = entry.after(snapshot(type, itemId, after));
        }
        auditRecorder.record(entry);
    }

    private void clearRecommendationsAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cache.clear();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.clear();
            }
        });
    }
}
