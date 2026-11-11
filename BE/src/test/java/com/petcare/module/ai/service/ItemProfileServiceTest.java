package com.petcare.module.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.petcare.module.ai.dto.ItemProfile;
import com.petcare.module.ai.dto.ItemProfileRow;
import com.petcare.module.ai.dto.ItemType;
import com.petcare.module.ai.dto.RecoGroup;
import com.petcare.module.ai.entity.AiItemProfile;
import com.petcare.module.ai.entity.ProfileCriteria;
import com.petcare.module.ai.entity.ProfileSpecies;
import com.petcare.module.ai.exception.ProfileValidationException;
import com.petcare.module.ai.repository.AiItemProfileRepository;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.KennelTypeInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.Recommendable;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.MedicalType;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.Species;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Hồ sơ gợi ý UC94 (BR-AI-17, 18, 20; docs/adr/0133). Chỉ dịch vụ thẩm mỹ và sản phẩm {@code GOODS} không kê đơn được có hồ
 * sơ; PUT ghi đè toàn bộ; mọi trường rỗng nghĩa là xóa; mỗi lần ghi thật có audit và xóa bộ nhớ đệm gợi ý.
 */
class ItemProfileServiceTest {

    private static final long ACTOR = 5L;

    private final AiItemProfileRepository profiles = mock(AiItemProfileRepository.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final RecommendationCache cache = mock(RecommendationCache.class);
    private ItemProfileService service;

    private final List<ServiceInfo> grooming = new ArrayList<>();
    private final List<KennelTypeInfo> kennels = new ArrayList<>();
    private final List<ProductInfo> products = new ArrayList<>();
    private final Map<Long, AiItemProfile> serviceRows = new HashMap<>();
    private final Map<Long, AiItemProfile> productRows = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(scope.current()).thenReturn(principal);
        when(principal.accountId()).thenReturn(ACTOR);
        when(profiles.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(catalog.listRecommendable()).thenAnswer(i -> new Recommendable(grooming, kennels, products));
        when(profiles.findByServiceIdIn(any())).thenAnswer(i -> ((java.util.Collection<Long>) i.getArgument(0)).stream()
                .filter(serviceRows::containsKey).map(serviceRows::get).toList());
        when(profiles.findByProductIdIn(any())).thenAnswer(i -> ((java.util.Collection<Long>) i.getArgument(0)).stream()
                .filter(productRows::containsKey).map(productRows::get).toList());
        when(profiles.findByServiceId(any())).thenAnswer(i -> Optional.ofNullable(serviceRows.get(i.<Long>getArgument(0))));
        when(profiles.findByProductId(any())).thenAnswer(i -> Optional.ofNullable(productRows.get(i.<Long>getArgument(0))));
        service = new ItemProfileService(profiles, catalog, audit, scope, cache);
    }

    // ---- dữ liệu ----------------------------------------------------------------------------------------------------

    private ServiceInfo groom(long id, String name) {
        ServiceInfo info = new ServiceInfo(id, name, ServiceGroup.GROOMING, null, 100_000, true);
        grooming.add(info);
        when(catalog.findService(id)).thenReturn(Optional.of(info));
        return info;
    }

    private ProductInfo goods(long id, String name) {
        ProductInfo info = new ProductInfo(id, name, ProductType.GOODS, false, false, null, 90_000, true);
        products.add(info);
        when(catalog.findProduct(id)).thenReturn(Optional.of(info));
        return info;
    }

    private static ItemProfile body(ProfileSpecies species, Integer minAge, Integer maxAge, String minKg, String maxKg,
            List<String> breeds, Integer repeat) {
        return new ItemProfile(species, minAge, maxAge, minKg == null ? null : new BigDecimal(minKg),
                maxKg == null ? null : new BigDecimal(maxKg), breeds, repeat);
    }

    private static ItemProfile emptyBody() {
        return body(null, null, null, null, null, List.of(), null);
    }

    private static ItemProfile dogSmall() {
        return body(ProfileSpecies.DOG, 12, null, "1", "8", List.of("poodle"), 30);
    }

    private AuditEntry auditedEntry() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }

    // ---- PUT: ghi ----------------------------------------------------------------------------------------------------

    @Test
    void savesANewServiceProfileAndReturnsTheRow() {
        groom(101, "Tắm & vệ sinh chó nhỏ");

        ItemProfileRow row = service.save(ItemType.SERVICE, 101L, dogSmall());

        ArgumentCaptor<AiItemProfile> saved = ArgumentCaptor.forClass(AiItemProfile.class);
        verify(profiles).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getServiceId()).isEqualTo(101L);
        assertThat(saved.getValue().getProductId()).isNull();
        assertThat(saved.getValue().getUpdatedBy()).isEqualTo(ACTOR);
        assertThat(saved.getValue().criteria()).isEqualTo(new ProfileCriteria(ProfileSpecies.DOG, 12, null,
                new BigDecimal("1"), new BigDecimal("8"), List.of("poodle"), 30));
        assertThat(row.hasProfile()).isTrue();
        assertThat(row.itemType()).isEqualTo(ItemType.SERVICE);
        assertThat(row.group()).isEqualTo(RecoGroup.GROOMING);
        assertThat(row.name()).isEqualTo("Tắm & vệ sinh chó nhỏ");
        assertThat(row.profile().minWeightKg()).isEqualByComparingTo("1");
    }

    @Test
    void savesAProductProfile() {
        goods(201, "Hạt khô");

        ItemProfileRow row = service.save(ItemType.PRODUCT, 201L, body(ProfileSpecies.CAT, null, null, null, null, List.of(), 60));

        ArgumentCaptor<AiItemProfile> saved = ArgumentCaptor.forClass(AiItemProfile.class);
        verify(profiles).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getProductId()).isEqualTo(201L);
        assertThat(saved.getValue().getServiceId()).isNull();
        assertThat(row.group()).isEqualTo(RecoGroup.PRODUCT);
        assertThat(row.itemType()).isEqualTo(ItemType.PRODUCT);
    }

    @Test
    void putReplacesTheWholeProfileSoAnOmittedFieldIsCleared() {
        groom(101, "Tắm");
        AiItemProfile existing = AiItemProfile.forService(101L, new ProfileCriteria(ProfileSpecies.DOG, 12, 52,
                new BigDecimal("1"), new BigDecimal("8"), List.of("poodle"), 30), 1L);
        serviceRows.put(101L, existing);

        service.save(ItemType.SERVICE, 101L, body(null, null, null, "2", null, List.of(), null));

        assertThat(existing.criteria()).isEqualTo(new ProfileCriteria(null, null, null, new BigDecimal("2"), null, List.of(), null));
        assertThat(existing.getUpdatedBy()).isEqualTo(ACTOR);
        verify(profiles).saveAndFlush(existing);
    }

    @Test
    void breedKeywordsAreStoredLowercaseWithoutAccentsTrimmedAndDeduplicated() {
        groom(101, "Tắm");

        ItemProfileRow row = service.save(ItemType.SERVICE, 101L,
                body(null, null, null, null, null, List.of("  Poodle ", "ĐỐM", "poodle", " ", ""), null));

        assertThat(row.profile().breedKeywords()).containsExactly("poodle", "dom");
    }

    @Test
    void anInactiveButEligibleItemMayHaveAProfile() {
        when(catalog.findService(102L)).thenReturn(Optional.of(new ServiceInfo(102L, "Đã ngừng", ServiceGroup.GROOMING, null, 1, false)));

        assertThat(service.save(ItemType.SERVICE, 102L, dogSmall()).hasProfile()).isTrue();
    }

    @Test
    void savingWritesAnAuditEntryWithBeforeAndAfterAndClearsTheRecommendationCache() {
        groom(101, "Tắm");
        AiItemProfile existing = AiItemProfile.forService(101L, dogSmall().toCriteria(), 1L);
        serviceRows.put(101L, existing);

        service.save(ItemType.SERVICE, 101L, body(ProfileSpecies.CAT, null, null, null, null, List.of(), null));

        AuditEntry entry = auditedEntry();
        assertThat(entry.action()).isEqualTo("AI_ITEM_PROFILE_SAVED");
        assertThat(entry.entityType()).isEqualTo("ai_item_profiles");
        assertThat((Map<String, Object>) entry.before()).containsEntry("species", "DOG").containsEntry("itemType", "SERVICE");
        assertThat((Map<String, Object>) entry.after()).containsEntry("species", "CAT").containsEntry("itemId", 101L);
        verify(cache).clear();
    }

    @Test
    void anUnchangedPutWritesNothingAndKeepsTheCache() {
        groom(101, "Tắm");
        serviceRows.put(101L, AiItemProfile.forService(101L, dogSmall().toCriteria(), 1L));

        ItemProfileRow row = service.save(ItemType.SERVICE, 101L, dogSmall());

        assertThat(row.hasProfile()).isTrue();
        verify(profiles, never()).saveAndFlush(any());
        verifyNoInteractions(audit, cache);
    }

    @Test
    void anUnchangedPutComparesWeightsByValueNotByScale() {
        groom(101, "Tắm");
        serviceRows.put(101L, AiItemProfile.forService(101L, new ProfileCriteria(ProfileSpecies.DOG, 12, null,
                new BigDecimal("1.00"), new BigDecimal("8.00"), List.of("poodle"), 30), 1L));

        service.save(ItemType.SERVICE, 101L, dogSmall());

        verify(profiles, never()).saveAndFlush(any());
        verifyNoInteractions(audit, cache);
    }

    // ---- PUT: xóa khi rỗng -------------------------------------------------------------------------------------------

    @Test
    void anAllEmptyBodyDeletesTheExistingRowAndTheItemGoesBackToMissing() {
        groom(101, "Tắm");
        AiItemProfile existing = AiItemProfile.forService(101L, dogSmall().toCriteria(), 1L);
        serviceRows.put(101L, existing);

        ItemProfileRow row = service.save(ItemType.SERVICE, 101L, emptyBody());

        verify(profiles).delete(existing);
        verify(profiles, never()).saveAndFlush(any());
        assertThat(row.hasProfile()).isFalse();
        assertThat(row.profile()).isNull();
        AuditEntry entry = auditedEntry();
        assertThat(entry.action()).isEqualTo("AI_ITEM_PROFILE_CLEARED");
        assertThat((Map<String, Object>) entry.before()).containsEntry("species", "DOG");
        assertThat(entry.after()).isNull();
        verify(cache).clear();
    }

    @Test
    void anAllEmptyBodyWhenThereIsNoRowIsANoOp() {
        goods(201, "Hạt khô");

        ItemProfileRow row = service.save(ItemType.PRODUCT, 201L, emptyBody());

        assertThat(row.hasProfile()).isFalse();
        verify(profiles, never()).saveAndFlush(any());
        verify(profiles, never()).delete(any());
        verifyNoInteractions(audit, cache);
    }

    // ---- BR-AI-18 ----------------------------------------------------------------------------------------------------

    @Test
    void medicalAndBoardingServicesCannotHaveAProfile() {
        when(catalog.findService(1L)).thenReturn(Optional.of(new ServiceInfo(1L, "Khám", ServiceGroup.MEDICAL, MedicalType.EXAM, 1, true)));
        when(catalog.findService(2L)).thenReturn(Optional.of(new ServiceInfo(2L, "Chuồng", ServiceGroup.BOARDING, null, 1, true)));

        for (long id : new long[] {1L, 2L}) {
            assertThatThrownBy(() -> service.save(ItemType.SERVICE, id, dogSmall()))
                    .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-AI-18");
        }
        verify(profiles, never()).saveAndFlush(any());
    }

    @Test
    void drugsVaccinesAndPrescriptionGoodsCannotHaveAProfile() {
        when(catalog.findProduct(1L)).thenReturn(Optional.of(new ProductInfo(1L, "Thuốc", ProductType.DRUG, false, true, null, 1, true)));
        when(catalog.findProduct(2L)).thenReturn(Optional.of(new ProductInfo(2L, "Vaccine", ProductType.VACCINE, false, true, 5L, 1, true)));
        when(catalog.findProduct(3L)).thenReturn(Optional.of(new ProductInfo(3L, "Hàng kê đơn", ProductType.GOODS, true, false, null, 1, true)));

        for (long id : new long[] {1L, 2L, 3L}) {
            assertThatThrownBy(() -> service.save(ItemType.PRODUCT, id, dogSmall()))
                    .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-AI-18");
        }
        verify(profiles, never()).saveAndFlush(any());
    }

    @Test
    void anUnknownServiceOrProductIsNotFound() {
        when(catalog.findService(404L)).thenReturn(Optional.empty());
        when(catalog.findProduct(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(ItemType.SERVICE, 404L, dogSmall())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.save(ItemType.PRODUCT, 404L, dogSmall())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void minGreaterThanMaxIsRejectedForAgeAndWeightAndNothingIsWritten() {
        groom(101, "Tắm");

        assertThatThrownBy(() -> service.save(ItemType.SERVICE, 101L, body(null, 20, 10, null, null, List.of(), null)))
                .isInstanceOf(ProfileValidationException.class);
        assertThatThrownBy(() -> service.save(ItemType.SERVICE, 101L, body(null, null, null, "8", "7.99", List.of(), null)))
                .isInstanceOf(ProfileValidationException.class);
        assertThat(service.save(ItemType.SERVICE, 101L, body(null, 10, 10, "8", "8", List.of(), null)).hasProfile())
                .as("min = max hợp lệ").isTrue();
        verify(audit).record(any());
    }

    // ---- GET: danh sách ----------------------------------------------------------------------------------------------

    private void catalogWithEverything() {
        groom(101, "Tắm & vệ sinh chó nhỏ");
        groom(102, "Cắt tỉa lông");
        goods(201, "Hạt khô cho chó");
        goods(202, "Đồ chơi");
        kennels.add(new KennelTypeInfo(301L, "Chuồng lớn", Species.DOG, new BigDecimal("30"), 200_000, true));
        grooming.add(new ServiceInfo(901L, "Khám tổng quát", ServiceGroup.MEDICAL, MedicalType.EXAM, 1, true));
        products.add(new ProductInfo(902L, "Thuốc kê đơn", ProductType.DRUG, true, true, null, 1, true));
        products.add(new ProductInfo(903L, "Hàng kê đơn", ProductType.GOODS, true, false, null, 1, true));
        serviceRows.put(101L, AiItemProfile.forService(101L, dogSmall().toCriteria(), 1L));
        productRows.put(202L, AiItemProfile.forProduct(202L, new ProfileCriteria(ProfileSpecies.CAT, null, null, null, null, List.of(), null), 1L));
    }

    private static List<Long> ids(PageResponse<ItemProfileRow> page) {
        return page.content().stream().map(ItemProfileRow::itemId).toList();
    }

    @Test
    void listsOnlyGroomingServicesAndOverTheCounterGoodsServicesFirst() {
        catalogWithEverything();

        PageResponse<ItemProfileRow> page = service.list(null, null, null, 0, 20);

        assertThat(ids(page)).as("không có loại chuồng, dịch vụ khám, thuốc, hàng kê đơn").containsExactly(101L, 102L, 201L, 202L);
        assertThat(page.content()).extracting(ItemProfileRow::hasProfile).containsExactly(true, false, false, true);
        assertThat(page.content().get(0).profile().species()).isEqualTo(ProfileSpecies.DOG);
        assertThat(page.content().get(1).profile()).isNull();
        assertThat(page.totalElements()).isEqualTo(4);
    }

    @Test
    void filtersByItemTypeAndByMissingOnly() {
        catalogWithEverything();

        assertThat(ids(service.list(ItemType.PRODUCT, null, null, 0, 20))).containsExactly(201L, 202L);
        assertThat(ids(service.list(ItemType.SERVICE, null, null, 0, 20))).containsExactly(101L, 102L);
        assertThat(ids(service.list(null, true, null, 0, 20))).containsExactly(102L, 201L);
        assertThat(ids(service.list(null, false, null, 0, 20))).as("missingOnly=false không lọc").hasSize(4);
    }

    @Test
    void anEmptyRowCountsAsMissing() {
        catalogWithEverything();
        serviceRows.put(102L, AiItemProfile.forService(102L, new ProfileCriteria(null, null, null, null, null, List.of(), null), 1L));

        assertThat(ids(service.list(null, true, null, 0, 20))).containsExactly(102L, 201L);
    }

    @Test
    void searchByNameIgnoresCaseAndAccents() {
        catalogWithEverything();

        assertThat(ids(service.list(null, null, "THU", 0, 20))).isEmpty();
        assertThat(ids(service.list(null, null, "hat kho", 0, 20))).containsExactly(201L);
        assertThat(ids(service.list(null, null, "  TẮM ", 0, 20))).containsExactly(101L);
        assertThat(ids(service.list(null, null, "đồ chơi", 0, 20))).containsExactly(202L);
    }

    @Test
    void pagesTheResultAndClampsThePageSize() {
        catalogWithEverything();

        PageResponse<ItemProfileRow> first = service.list(null, null, null, 0, 3);
        PageResponse<ItemProfileRow> second = service.list(null, null, null, 1, 3);

        assertThat(ids(first)).containsExactly(101L, 102L, 201L);
        assertThat(ids(second)).containsExactly(202L);
        assertThat(first.totalElements()).isEqualTo(4);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(service.list(null, null, null, 0, 0).size()).as("size kẹp tối thiểu 1").isEqualTo(1);
        assertThat(service.list(null, null, null, 0, 5000).size()).as("size kẹp tối đa 100").isEqualTo(100);
        assertThat(service.list(null, null, null, -3, 20).page()).isZero();
    }
}
