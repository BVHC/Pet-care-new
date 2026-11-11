package com.petcare.module.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.petcare.module.ai.dto.RecoGroup;
import com.petcare.module.ai.dto.RecommendationResponse;
import com.petcare.module.ai.dto.RecommendedItem;
import com.petcare.module.ai.entity.AiItemProfile;
import com.petcare.module.ai.entity.ProfileCriteria;
import com.petcare.module.ai.entity.ProfileSpecies;
import com.petcare.module.ai.repository.AiItemProfileRepository;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi.StayOfPet;
import com.petcare.module.boarding.api.BoardingQueryApi.StayStatus;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.KennelTypeInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductDisplay;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.RecommendDisplay;
import com.petcare.module.catalog.api.CatalogQueryApi.Recommendable;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.MedicalType;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetProfile;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.api.VisitQueryApi.AgeGroup;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.support.MutableClock;

/**
 * Gợi ý cá nhân hóa UC92 (BR-AI-18…23, 07 §7, docs/adr/0133) với các {@code *QueryApi} giả. Ví dụ tính tay §7.3 là ca bảng;
 * bộ lọc "không bao giờ lọt" được kiểm lại ở module {@code ai} dù catalog đã lọc (BR-AI-18); khóa cache phải có cân nặng và
 * dấu vết lịch sử, và quyền sở hữu kiểm TRƯỚC khi tra cache (module {@code ai} không nghe {@code PetOwnerTransferredEvent}).
 */
class RecommendationServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);
    private static final long ACCOUNT = 70L;
    private static final long CUSTOMER = 7L;
    private static final long PET = 12L;
    private static final BigDecimal MOCHI_KG = new BigDecimal("3.2");

    // ---- cấu hình giả: mọi khóa ai.reco.* ở giá trị mặc định của V35 ---------------------------------------------------

    private static final class Config implements SystemConfigApi {
        final Map<ConfigKey, BigDecimal> values = new HashMap<>();

        Config() {
            values.put(ConfigKey.AI_RECO_WEIGHT_ATTR, new BigDecimal("0.5"));
            values.put(ConfigKey.AI_RECO_WEIGHT_HISTORY, new BigDecimal("0.3"));
            values.put(ConfigKey.AI_RECO_WEIGHT_POPULARITY, new BigDecimal("0.2"));
            values.put(ConfigKey.AI_RECO_MAX_PER_GROUP, new BigDecimal("6"));
            values.put(ConfigKey.AI_RECO_CACHE_MINUTES, BigDecimal.ZERO);
            values.put(ConfigKey.AI_RECO_DEFAULT_GROOM_INTERVAL_DAYS, new BigDecimal("30"));
            values.put(ConfigKey.AI_RECO_DEFAULT_PRODUCT_INTERVAL_DAYS, new BigDecimal("45"));
            values.put(ConfigKey.AI_RECO_SENIOR_AGE_YEARS_DOG, new BigDecimal("7"));
            values.put(ConfigKey.AI_RECO_SENIOR_AGE_YEARS_CAT, new BigDecimal("10"));
            values.put(ConfigKey.AI_RECO_SENIOR_AGE_YEARS_OTHER, new BigDecimal("7"));
        }

        @Override
        public int getInt(ConfigKey key) {
            return values.get(key).intValue();
        }

        @Override
        public BigDecimal getDecimal(ConfigKey key) {
            return values.get(key);
        }

        @Override
        public boolean getBool(ConfigKey key) {
            throw new UnsupportedOperationException(key.name());
        }

        @Override
        public LocalTime getTime(ConfigKey key) {
            throw new UnsupportedOperationException(key.name());
        }
    }

    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final StockQueryApi stock = mock(StockQueryApi.class);
    private final VisitQueryApi visits = mock(VisitQueryApi.class);
    private final BoardingQueryApi boarding = mock(BoardingQueryApi.class);
    private final OrderQueryApi orders = mock(OrderQueryApi.class);
    private final AiItemProfileRepository profiles = mock(AiItemProfileRepository.class);
    private final Config config = new Config();
    private final MutableClock clock = new MutableClock(TODAY.atTime(9, 0).atZone(com.petcare.platform.config.TimeConfig.BUSINESS_ZONE).toInstant());
    private RecommendationService service;

    // ---- dữ liệu của kịch bản (test sửa các trường này rồi gọi lại) -------------------------------------------------------

    private PetProfile pet;
    private final List<ServiceInfo> grooming = new ArrayList<>();
    private final List<KennelTypeInfo> kennels = new ArrayList<>();
    private final List<ProductInfo> products = new ArrayList<>();
    private final Map<Long, ServiceInfo> serviceCatalog = new HashMap<>();
    private final Set<Long> enabled = new HashSet<>();
    private final Set<Long> inStock = new HashSet<>();
    private final Set<Long> priceFrom = new HashSet<>();
    private final Map<Long, ProductDisplay> productDisplay = new HashMap<>();
    private final Map<Long, ProfileCriteria> serviceProfiles = new HashMap<>();
    private final Map<Long, ProfileCriteria> productProfiles = new HashMap<>();
    private Map<Long, LocalDate> lastServices = new HashMap<>();
    private Map<Long, Long> groomCounts = new HashMap<>();
    private List<StayOfPet> stays = new ArrayList<>();
    private Map<Long, Long> stayCounts = new HashMap<>();
    private Map<Long, LocalDate> purchases = new HashMap<>();
    private Map<Long, Long> soldUnits = new HashMap<>();

    @BeforeEach
    void setUp() {
        pet = new PetProfile(PET, CUSTOMER, Species.DOG, "Poodle", TODAY.minusWeeks(20), MOCHI_KG, null);
        when(customers.findCustomerIdByAccountId(ACCOUNT)).thenReturn(Optional.of(CUSTOMER));
        when(pets.findPetProfile(PET)).thenAnswer(i -> Optional.of(pet));
        when(catalog.listRecommendable()).thenAnswer(i -> new Recommendable(grooming, kennels, products));
        when(catalog.findServices(any())).thenAnswer(i -> ((Collection<Long>) i.getArgument(0)).stream()
                .map(serviceCatalog::get).filter(java.util.Objects::nonNull).toList());
        when(catalog.findRecommendDisplay(any(), any())).thenAnswer(i -> new RecommendDisplay(priceFrom, productDisplay));
        when(branches.findServiceIdsEnabledAnywhere()).thenAnswer(i -> enabled);
        when(stock.findProductIdsInStockAnywhere(any())).thenAnswer(i -> inStock);
        when(visits.findLastServiceDates(PET)).thenAnswer(i -> lastServices);
        when(visits.countCompletedByService(any(), any(), any())).thenAnswer(i -> groomCounts);
        when(boarding.findStaysOfPet(PET)).thenAnswer(i -> stays);
        when(boarding.countStaysByKennelType(any(), any(), any())).thenAnswer(i -> stayCounts);
        when(orders.findLastPurchaseDates(eq(CUSTOMER), any())).thenAnswer(i -> purchases);
        when(orders.sumSoldUnitsByProduct(any())).thenAnswer(i -> soldUnits);
        when(profiles.findByServiceIdIn(any())).thenAnswer(i -> ((Collection<Long>) i.getArgument(0)).stream()
                .filter(serviceProfiles::containsKey).map(id -> AiItemProfile.forService(id, serviceProfiles.get(id), 1L)).toList());
        when(profiles.findByProductIdIn(any())).thenAnswer(i -> ((Collection<Long>) i.getArgument(0)).stream()
                .filter(productProfiles::containsKey).map(id -> AiItemProfile.forProduct(id, productProfiles.get(id), 1L)).toList());
        service = new RecommendationService(pets, customers, catalog, branches, stock, visits, boarding, orders, profiles,
                config, clock, new RecommendationCache());
    }

    // ---- dựng dữ liệu ------------------------------------------------------------------------------------------------

    private ServiceInfo groom(long id, String name) {
        ServiceInfo info = new ServiceInfo(id, name, ServiceGroup.GROOMING, null, 100_000, true);
        grooming.add(info);
        serviceCatalog.put(id, info);
        enabled.add(id);
        priceFrom.add(id);
        return info;
    }

    /** Dịch vụ ngoài danh sách gợi ý (khám, tiêm) nhưng thú đã dùng: chỉ có trong tra cứu theo id. */
    private void medical(long id) {
        serviceCatalog.put(id, new ServiceInfo(id, "Tiêm phòng " + id, ServiceGroup.MEDICAL, MedicalType.VACCINE, 200_000, true));
    }

    private KennelTypeInfo kennel(long id, Species species, String maxKg) {
        KennelTypeInfo info = new KennelTypeInfo(id, "Chuồng " + id, species, maxKg == null ? null : new BigDecimal(maxKg), 150_000, true);
        kennels.add(info);
        enabled.add(id);
        return info;
    }

    private ProductInfo goods(long id, String name) {
        ProductInfo info = new ProductInfo(id, name, ProductType.GOODS, false, false, null, 90_000, true);
        products.add(info);
        inStock.add(id);
        productDisplay.put(id, new ProductDisplay(9L, null));
        return info;
    }

    private static ProfileCriteria dogOnly() {
        return new ProfileCriteria(ProfileSpecies.DOG, null, null, null, null, List.of(), null);
    }

    private RecommendationResponse recommend() {
        return service.recommend(ACCOUNT, PET, null, null);
    }

    private static List<Long> ids(RecommendationResponse response) {
        return response.items().stream().map(RecommendedItem::itemId).toList();
    }

    private RecommendedItem item(RecommendationResponse response, long id) {
        return response.items().stream().filter(i -> i.itemId() == id).findFirst().orElseThrow();
    }

    // ---- ví dụ tính tay 07 §7.3 --------------------------------------------------------------------------------------

    private void workedExample() {
        groom(101, "Tắm & vệ sinh chó nhỏ");
        serviceProfiles.put(101L, new ProfileCriteria(ProfileSpecies.DOG, 12, null, new BigDecimal("1"), new BigDecimal("8"),
                List.of("poodle"), 30));
        groom(102, "Cắt tỉa lông chó lớn");
        serviceProfiles.put(102L, new ProfileCriteria(ProfileSpecies.DOG, null, null, new BigDecimal("15"), null, List.of(), null));
        groom(103, "Spa thư giãn");
        groom(104, "Cạo vôi răng");
        lastServices = new HashMap<>(Map.of(101L, TODAY.minusDays(40)));
        groomCounts = new HashMap<>(Map.of(101L, 18L, 102L, 5L, 103L, 15L, 104L, 30L));
    }

    @Test
    void workedExampleGivesExactly92ExcludesTheBigDogCutAndGives37ToTheSpa() {
        workedExample();

        RecommendationResponse response = recommend();

        assertThat(ids(response)).as("92,0 rồi 47,0 rồi 37,0; 102 bị loại vì 3,2 kg < 15 kg").containsExactly(101L, 104L, 103L);
        assertThat(response.items()).extracting(i -> i.score().toString()).containsExactly("92.0", "47.0", "37.0");
        assertThat(response.petId()).isEqualTo(PET);
    }

    @Test
    void reasonsComeFromTemplatesPerContributingComponent() {
        workedExample();

        RecommendedItem bath = item(recommend(), 101);

        assertThat(bath.reasons()).contains("Phù hợp cân nặng 1–8 kg", "Đã 40 ngày kể từ lần dùng gần nhất", "Nhiều chủ chó con chọn");
        assertThat(item(recommend(), 103).reasons()).contains("Phù hợp chung", "Bé từng dùng dịch vụ thẩm mỹ khác");
    }

    @Test
    void popularityCohortIsTheSpeciesAndAgeGroupOfThePetOverTheLast90Days() {
        workedExample();

        recommend();

        verify(visits).countCompletedByService(Species.DOG, AgeGroup.JUNIOR, TODAY.minusDays(90));
        verify(orders).sumSoldUnitsByProduct(TODAY.minusDays(90));
    }

    @Test
    void cMaxIsTakenOverTheCandidatesOfTheGroupOnly() {
        groom(101, "Tắm");
        groom(102, "Gội");
        // Dịch vụ 999 không phải ứng viên (ví dụ tiêm phòng): 1000 lượt không được kéo c_max lên.
        groomCounts = new HashMap<>(Map.of(101L, 10L, 102L, 5L, 999L, 1000L));

        RecommendationResponse response = recommend();

        assertThat(item(response, 101).score()).as("P = 10/10").hasToString("35.0");
        assertThat(item(response, 102).score()).as("P = 5/10").hasToString("25.0");
    }

    @Test
    void noCompletedVisitsAnywhereMeansPopularityZero() {
        groom(101, "Tắm");

        assertThat(item(recommend(), 101).score()).hasToString("15.0");
    }

    // ---- đổi dữ liệu thì đổi gợi ý (07 §7.4, BR-AI-23) -----------------------------------------------------------------

    @Test
    void changingTheWeightChangesTheListAndTheCacheKey() {
        workedExample();
        config.values.put(ConfigKey.AI_RECO_CACHE_MINUTES, BigDecimal.TEN);
        assertThat(ids(recommend())).contains(101L).doesNotContain(102L);

        pet = new PetProfile(PET, CUSTOMER, Species.DOG, "Poodle", TODAY.minusWeeks(20), new BigDecimal("30"), null);
        RecommendationResponse heavy = recommend();

        assertThat(ids(heavy)).as("Mochi 30 kg: mục chó lớn xuất hiện, mục ≤ 8 kg bị loại").contains(102L).doesNotContain(101L);
        verify(visits, times(2)).countCompletedByService(any(), any(), any());
    }

    @Test
    void changingTheSpeciesChangesTheList() {
        workedExample();
        config.values.put(ConfigKey.AI_RECO_CACHE_MINUTES, BigDecimal.TEN);
        recommend();

        // Chỉ đổi loài (giống, ngày sinh, cân nặng giữ nguyên): khóa cache phải đổi vì riêng loài.
        pet = new PetProfile(PET, CUSTOMER, Species.CAT, "Poodle", TODAY.minusWeeks(20), MOCHI_KG, null);

        assertThat(ids(recommend())).as("các mục chỉ dành cho chó bị loại; 104 đông khách hơn 103").containsExactly(104L, 103L);
    }

    // ---- cache -------------------------------------------------------------------------------------------------------

    @Test
    void secondCallWithinTheTtlIsServedFromTheCache() {
        workedExample();
        config.values.put(ConfigKey.AI_RECO_CACHE_MINUTES, BigDecimal.TEN);

        RecommendationResponse first = recommend();
        clock.advance(Duration.ofMinutes(9));
        RecommendationResponse second = recommend();

        assertThat(ids(second)).isEqualTo(ids(first));
        verify(visits, times(1)).countCompletedByService(any(), any(), any());
    }

    @Test
    void theCacheExpiresAfterTheConfiguredMinutes() {
        workedExample();
        config.values.put(ConfigKey.AI_RECO_CACHE_MINUTES, BigDecimal.TEN);

        recommend();
        clock.advance(Duration.ofMinutes(11));
        recommend();

        verify(visits, times(2)).countCompletedByService(any(), any(), any());
    }

    @Test
    void zeroMinutesSwitchesTheCacheOff() {
        workedExample();

        recommend();
        recommend();

        verify(visits, times(2)).countCompletedByService(any(), any(), any());
    }

    @Test
    void groupAndLimitAreAppliedAfterTheCacheSoTheyAreNotPartOfTheKey() {
        workedExample();
        goods(201, "Sữa tắm");
        goods(202, "Lược chải");
        config.values.put(ConfigKey.AI_RECO_CACHE_MINUTES, BigDecimal.TEN);

        RecommendationResponse all = recommend();
        RecommendationResponse oneGroom = service.recommend(ACCOUNT, PET, RecoGroup.GROOMING, 1);
        RecommendationResponse products = service.recommend(ACCOUNT, PET, RecoGroup.PRODUCT, null);

        assertThat(all.items()).extracting(RecommendedItem::group).containsExactly(RecoGroup.GROOMING, RecoGroup.GROOMING,
                RecoGroup.GROOMING, RecoGroup.PRODUCT, RecoGroup.PRODUCT);
        assertThat(ids(oneGroom)).containsExactly(101L);
        assertThat(ids(products)).containsExactly(201L, 202L);
        verify(visits, times(1)).countCompletedByService(any(), any(), any());
    }

    @Test
    void aNewCompletedVisitOrPurchaseChangesTheCacheKey() {
        workedExample();
        goods(201, "Sữa tắm");
        productProfiles.put(201L, dogOnly());
        config.values.put(ConfigKey.AI_RECO_CACHE_MINUTES, BigDecimal.TEN);
        assertThat(item(recommend(), 201).score()).as("A = 1 (khớp loài), chưa mua: H = 0").hasToString("50.0");

        purchases = new HashMap<>(Map.of(201L, TODAY.minusDays(50)));
        RecommendationResponse afterPurchase = recommend();
        lastServices = new HashMap<>(Map.of(101L, TODAY.minusDays(5)));
        RecommendationResponse afterVisit = recommend();

        assertThat(item(afterPurchase, 201).score()).as("đã mua 50 ngày trước, chu kỳ 45: H = 1").hasToString("80.0");
        assertThat(item(afterVisit, 101).reasons()).contains("Đã dùng cách đây 5 ngày");
        verify(orders, times(3)).findLastPurchaseDates(eq(CUSTOMER), any());
        verify(visits, times(3)).findLastServiceDates(PET);
    }

    @Test
    void aNewStayAndAChangeOfBreedOrBirthDateEachChangeTheCacheKey() {
        kennel(301, Species.DOG, "20");
        config.values.put(ConfigKey.AI_RECO_CACHE_MINUTES, BigDecimal.TEN);
        assertThat(item(recommend(), 301).score()).as("chưa ở lần nào: H = 0").hasToString("50.0");

        stays = new ArrayList<>(List.of(new StayOfPet(1L, 301L, StayStatus.CHECKED_OUT, TODAY.minusDays(30), TODAY.minusDays(28))));
        assertThat(item(recommend(), 301).score()).as("lưu trú mới: H = 1").hasToString("80.0");
        pet = new PetProfile(PET, CUSTOMER, Species.DOG, "Corgi", TODAY.minusWeeks(20), MOCHI_KG, null);
        recommend();
        pet = new PetProfile(PET, CUSTOMER, Species.DOG, "Corgi", TODAY.minusWeeks(21), MOCHI_KG, null);
        recommend();

        verify(visits, times(4)).countCompletedByService(any(), any(), any());
    }

    @Test
    void ownershipIsCheckedBeforeTheCacheIsRead() {
        workedExample();
        config.values.put(ConfigKey.AI_RECO_CACHE_MINUTES, BigDecimal.TEN);
        recommend();

        pet = new PetProfile(PET, 8L, Species.DOG, "Poodle", TODAY.minusWeeks(20), MOCHI_KG, null);

        assertThatThrownBy(this::recommend).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- quyền -------------------------------------------------------------------------------------------------------

    @Test
    void anotherCustomersPetAnUnknownPetAndAnAccountWithoutCustomerAreAllNotFound() {
        pet = new PetProfile(PET, 8L, Species.DOG, "Poodle", TODAY.minusWeeks(20), MOCHI_KG, null);
        assertThatThrownBy(this::recommend).isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> service.recommend(ACCOUNT, 404L, null, null)).isInstanceOf(ResourceNotFoundException.class);

        when(customers.findCustomerIdByAccountId(71L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.recommend(71L, PET, null, null)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aDeceasedPetIsRejectedAfterTheOwnershipCheck() {
        pet = new PetProfile(PET, CUSTOMER, Species.DOG, "Poodle", TODAY.minusWeeks(20), MOCHI_KG, TODAY.minusDays(3));
        assertThatThrownBy(this::recommend).isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KH-05");

        pet = new PetProfile(PET, 8L, Species.DOG, "Poodle", TODAY.minusWeeks(20), MOCHI_KG, TODAY.minusDays(3));
        assertThatThrownBy(this::recommend).as("thú mất của người khác vẫn là 404").isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- BR-AI-18, BR-AI-19: lọc ----------------------------------------------------------------------------------------

    @Test
    void neverRecommendsDrugsVaccinesPrescriptionGoodsOrMedicalServicesEvenIfTheCatalogLetsThemThrough() {
        goods(201, "Hạt khô");
        products.add(new ProductInfo(202L, "Thuốc kê đơn", ProductType.DRUG, true, true, null, 50_000, true));
        products.add(new ProductInfo(203L, "Thuốc không kê đơn", ProductType.DRUG, false, true, null, 50_000, true));
        products.add(new ProductInfo(204L, "Vaccine", ProductType.VACCINE, false, true, 5L, 50_000, true));
        products.add(new ProductInfo(205L, "Hàng kê đơn", ProductType.GOODS, true, false, null, 50_000, true));
        inStock.addAll(Set.of(202L, 203L, 204L, 205L));
        groom(101, "Tắm");
        grooming.add(new ServiceInfo(102L, "Khám tổng quát", ServiceGroup.MEDICAL, MedicalType.EXAM, 100_000, true));
        enabled.add(102L);

        assertThat(ids(recommend())).containsExactly(101L, 201L);
    }

    @Test
    void servicesNotEnabledAnywhereAndGoodsOutOfStockAreNotCandidates() {
        groom(101, "Tắm");
        groom(102, "Gội");
        enabled.remove(102L);
        goods(201, "Còn hàng");
        goods(202, "Hết hàng");
        inStock.remove(202L);

        assertThat(ids(recommend())).containsExactly(101L, 201L);
    }

    @Test
    void kennelMustMatchTheSpeciesAndHoldTheWeightWhenItIsKnown() {
        kennel(301, Species.DOG, "5.00");
        kennel(302, Species.DOG, "3.20");
        kennel(303, Species.DOG, "3.00");
        kennel(304, Species.CAT, "10");
        kennel(305, Species.DOG, null);

        assertThat(ids(recommend())).as("3,2 kg: chuồng 3,2 kg đủ (≥), 3 kg thì không; chuồng mèo bị loại")
                .containsExactly(301L, 302L, 305L);
    }

    @Test
    void aKennelNotEnabledAnywhereIsNotACandidate() {
        kennel(301, Species.DOG, "20");
        kennel(302, Species.DOG, "20");
        enabled.remove(302L);

        assertThat(ids(recommend())).containsExactly(301L);
    }

    @Test
    void kennelWeightLimitIsNotEvaluatedWhenThePetHasNoWeight() {
        pet = new PetProfile(PET, CUSTOMER, Species.DOG, "Poodle", TODAY.minusWeeks(20), null, null);
        kennel(301, Species.DOG, "1.00");
        kennel(302, Species.CAT, "10");

        assertThat(ids(recommend())).containsExactly(301L);
    }

    // ---- nhóm tuổi theo loài (07 §7.3) ---------------------------------------------------------------------------------

    @Test
    void catOfEightYearsIsAdultNotSeniorSoTheCatKeyIsUsedNotTheDogKey() {
        pet = new PetProfile(PET, CUSTOMER, Species.CAT, "Mun", TODAY.minusYears(8), MOCHI_KG, null);
        groom(101, "Tắm");

        recommend();

        verify(visits).countCompletedByService(Species.CAT, AgeGroup.ADULT, TODAY.minusDays(90));
        verify(boarding).countStaysByKennelType(Species.CAT, AgeGroup.ADULT, TODAY.minusDays(90));
    }

    @Test
    void otherSpeciesReadsItsOwnSeniorKeyNotTheDogOne() {
        config.values.put(ConfigKey.AI_RECO_SENIOR_AGE_YEARS_OTHER, new BigDecimal("12"));
        pet = new PetProfile(PET, CUSTOMER, Species.OTHER, "Thỏ", TODAY.minusYears(8), MOCHI_KG, null);
        groom(101, "Tắm");

        recommend();

        verify(visits).countCompletedByService(Species.OTHER, AgeGroup.ADULT, TODAY.minusDays(90));
    }

    @Test
    void ageGroupBoundariesFollowSection73() {
        groom(101, "Tắm");

        pet = new PetProfile(PET, CUSTOMER, Species.CAT, "Mun", TODAY.minusDays(363), MOCHI_KG, null);
        recommend();
        pet = new PetProfile(PET, CUSTOMER, Species.CAT, "Mun", TODAY.minusDays(364), MOCHI_KG, null);
        recommend();
        pet = new PetProfile(PET, CUSTOMER, Species.CAT, "Mun", TODAY.minusDays(10 * 364), MOCHI_KG, null);
        recommend();
        pet = new PetProfile(PET, CUSTOMER, Species.CAT, "Mun", null, MOCHI_KG, null);
        recommend();

        verify(visits).countCompletedByService(Species.CAT, AgeGroup.JUNIOR, TODAY.minusDays(90));
        verify(visits).countCompletedByService(Species.CAT, AgeGroup.ADULT, TODAY.minusDays(90));
        verify(visits).countCompletedByService(Species.CAT, AgeGroup.SENIOR, TODAY.minusDays(90));
        verify(visits).countCompletedByService(Species.CAT, AgeGroup.UNKNOWN, TODAY.minusDays(90));
    }

    @Test
    void petWithoutBirthDateAndWeightIsNeitherExcludedNorPenalised() {
        pet = new PetProfile(PET, CUSTOMER, Species.DOG, null, null, null, null);
        workedExample();

        RecommendationResponse response = recommend();

        assertThat(ids(response)).as("không loại mục 102 vì không đánh giá được cân nặng").contains(101L, 102L, 103L, 104L);
        assertThat(item(response, 101).score()).as("A = 1/1 (chỉ loài đánh giá được)").hasToString("92.0");
    }

    // ---- H: lịch sử ----------------------------------------------------------------------------------------------------

    @Test
    void groomingHistoryTable() {
        groom(101, "Đến hạn");
        groom(102, "Chưa đến hạn");
        groom(103, "Chưa dùng, từng dùng thẩm mỹ khác");
        serviceProfiles.put(101L, new ProfileCriteria(null, null, null, null, null, List.of(), 20));
        lastServices = new HashMap<>(Map.of(101L, TODAY.minusDays(20), 102L, TODAY.minusDays(15)));

        RecommendationResponse response = recommend();

        // A = 0,3 cho cả ba; P = 0 (không có lượt nào).
        assertThat(item(response, 101).score()).as("H = 1 (chu kỳ 20 ngày của hồ sơ, đúng 20 ngày)").hasToString("45.0");
        assertThat(item(response, 102).score()).as("H = 0,2 × 15/30 = 0,1").hasToString("18.0");
        assertThat(item(response, 103).score()).as("H = 0,4").hasToString("27.0");
    }

    @Test
    void aPetThatOnlyHadVaccinationVisitsHasNoGroomingHistory() {
        groom(101, "Tắm");
        medical(900);
        lastServices = new HashMap<>(Map.of(900L, TODAY.minusDays(10)));

        assertThat(item(recommend(), 101).score()).as("H = 0, không phải 0,4").hasToString("15.0");
    }

    @Test
    void aDiscontinuedGroomingServiceStillCountsAsGroomingHistory() {
        groom(101, "Tắm");
        serviceCatalog.put(800L, new ServiceInfo(800L, "Dịch vụ đã ngừng", ServiceGroup.GROOMING, null, 100_000, false));
        lastServices = new HashMap<>(Map.of(800L, TODAY.minusDays(10)));

        assertThat(item(recommend(), 101).score()).hasToString("27.0");
    }

    @Test
    void productHistoryTable() {
        goods(201, "Mua đúng hàng, đến hạn");
        goods(202, "Mua đúng hàng, chưa đến hạn");
        goods(203, "Chưa mua, cùng danh mục");
        goods(204, "Chưa mua, khác danh mục");
        productDisplay.put(204L, new ProductDisplay(77L, null));
        purchases = new HashMap<>(Map.of(201L, TODAY.minusDays(45), 202L, TODAY.minusDays(44)));

        RecommendationResponse response = recommend();

        assertThat(item(response, 201).score()).as("H = 1").hasToString("45.0");
        assertThat(item(response, 202).score()).as("H = 0").hasToString("15.0");
        assertThat(item(response, 203).score()).as("H = 0,4: đã mua sản phẩm cùng danh mục").hasToString("27.0");
        assertThat(item(response, 204).score()).as("H = 0: danh mục khác").hasToString("15.0");
    }

    @Test
    void aPurchaseOfAnOutOfStockProductStillCountsForTheSameCategoryRule() {
        goods(201, "Đã mua nhưng hết hàng");
        inStock.remove(201L);
        goods(202, "Còn hàng cùng danh mục");
        purchases = new HashMap<>(Map.of(201L, TODAY.minusDays(100)));

        assertThat(item(recommend(), 202).score()).hasToString("27.0");
    }

    @Test
    void productPopularityIsChainWideAndBoundedByTheCandidates() {
        goods(201, "Bán chạy");
        goods(202, "Bán chậm");
        soldUnits = new HashMap<>(Map.of(201L, 40L, 202L, 10L, 999L, 5000L));

        RecommendationResponse response = recommend();

        assertThat(item(response, 201).score()).hasToString("35.0");
        assertThat(item(response, 202).score()).hasToString("20.0");
    }

    @Test
    void boardingHistoryOnlyCountsStaysThatActuallyHappened() {
        kennel(301, Species.DOG, "20");
        kennel(302, Species.DOG, "20");
        stays = new ArrayList<>(List.of(
                new StayOfPet(1L, 301L, StayStatus.CANCELLED, TODAY.minusDays(9), TODAY.minusDays(8)),
                new StayOfPet(2L, 301L, StayStatus.NO_SHOW, TODAY.minusDays(7), TODAY.minusDays(6)),
                new StayOfPet(3L, 301L, StayStatus.BOOKED, TODAY.plusDays(7), TODAY.plusDays(9))));

        assertThat(item(recommend(), 301).score()).as("chưa ở lần nào: H = 0").hasToString("50.0");

        stays.add(new StayOfPet(4L, 302L, StayStatus.CHECKED_OUT, TODAY.minusDays(30), TODAY.minusDays(28)));
        RecommendationResponse after = recommend();
        assertThat(item(after, 302).score()).as("từng ở đúng loại chuồng này: H = 1").hasToString("80.0");
        assertThat(item(after, 301).score()).as("từng ở loại chuồng khác: H = 0,6").hasToString("68.0");
    }

    // ---- kết quả: sắp xếp, giới hạn, hiển thị ------------------------------------------------------------------------------

    @Test
    void equalScoresAreOrderedByIdAscending() {
        groom(105, "E");
        groom(103, "C");
        groom(104, "D");

        assertThat(ids(recommend())).containsExactly(103L, 104L, 105L);
    }

    @Test
    void eachGroupKeepsTheTopSixByDefaultAndTheLimitOverridesIt() {
        for (long id = 101; id <= 110; id++) {
            groom(id, "Dịch vụ " + id);
        }
        for (long id = 201; id <= 208; id++) {
            goods(id, "Hàng " + id);
        }

        assertThat(recommend().items()).hasSize(12);
        assertThat(service.recommend(ACCOUNT, PET, null, 2).items()).hasSize(4);
        config.values.put(ConfigKey.AI_RECO_MAX_PER_GROUP, new BigDecimal("3"));
        assertThat(recommend().items()).hasSize(6);
    }

    @Test
    void groupsComeInTheOrderGroomingBoardingProduct() {
        goods(201, "Hàng");
        kennel(301, Species.DOG, "20");
        groom(101, "Tắm");

        assertThat(recommend().items()).extracting(RecommendedItem::group)
                .containsExactly(RecoGroup.GROOMING, RecoGroup.BOARDING, RecoGroup.PRODUCT);
    }

    @Test
    void priceFlagUnitAndImageFollowTheCatalogDisplayData() {
        groom(101, "Tắm");
        groom(102, "Cắt móng");
        priceFrom.remove(102L);
        kennel(301, Species.DOG, "20");
        goods(201, "Hạt khô");
        productDisplay.put(201L, new ProductDisplay(9L, "https://img.example/hat.png"));

        RecommendationResponse response = recommend();

        RecommendedItem from = item(response, 101);
        assertThat(from.price()).isEqualTo(100_000L);
        assertThat(from.priceIsFrom()).isTrue();
        assertThat(from.priceUnit()).isNull();
        assertThat(from.imageUrl()).isNull();
        assertThat(from.itemType().name()).isEqualTo("SERVICE");
        assertThat(item(response, 102).priceIsFrom()).as("đọc cột price_is_from, không suy từ nhóm").isFalse();
        RecommendedItem night = item(response, 301);
        assertThat(night.price()).isEqualTo(150_000L);
        assertThat(night.priceUnit()).isEqualTo("đêm");
        RecommendedItem hat = item(response, 201);
        assertThat(hat.priceIsFrom()).isFalse();
        assertThat(hat.imageUrl()).isEqualTo("https://img.example/hat.png");
        assertThat(hat.itemType().name()).isEqualTo("PRODUCT");
        assertThat(response.generatedAt()).isEqualTo(Instant.parse("2026-11-11T02:00:00Z"));
    }

    @Test
    void weightsThatDoNotSumToOneKeepTheScoreAtOrBelow100() {
        groom(101, "Tắm");
        serviceProfiles.put(101L, dogOnly());
        lastServices = new HashMap<>(Map.of(101L, TODAY.minusDays(40)));
        groomCounts = new HashMap<>(Map.of(101L, 10L));
        config.values.put(ConfigKey.AI_RECO_WEIGHT_ATTR, new BigDecimal("1"));
        config.values.put(ConfigKey.AI_RECO_WEIGHT_HISTORY, new BigDecimal("1"));
        config.values.put(ConfigKey.AI_RECO_WEIGHT_POPULARITY, new BigDecimal("1"));

        assertThat(item(recommend(), 101).score()).hasToString("100.0");
    }
}
