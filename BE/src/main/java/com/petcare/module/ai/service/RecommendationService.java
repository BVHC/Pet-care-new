package com.petcare.module.ai.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.ai.dto.ItemType;
import com.petcare.module.ai.dto.RecoGroup;
import com.petcare.module.ai.dto.RecommendationResponse;
import com.petcare.module.ai.dto.RecommendedItem;
import com.petcare.module.ai.entity.AiItemProfile;
import com.petcare.module.ai.entity.ProfileCriteria;
import com.petcare.module.ai.repository.AiItemProfileRepository;
import com.petcare.module.ai.service.RecoAttributes.Match;
import com.petcare.module.ai.service.RecommendationCache.Ranked;
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

/**
 * Gợi ý cá nhân hóa cho thú của khách (UC92, BR-AI-18…23, 07 §7, docs/adr/0133). Không gọi LLM: điểm là
 * {@code 100 × (wA·A + wH·H + wP·P)} tính bằng BigDecimal, lý do sinh từ mẫu (BR-AI-22).
 * <p>
 * Thứ tự: quyền sở hữu và thú còn sống (mỗi lần, trước cả bộ nhớ đệm vì module {@code ai} không nghe
 * {@code PetOwnerTransferredEvent}) → đọc lịch sử của thú và khách (không cache, để khóa đổi khi có lượt khám, lưu trú hay
 * đơn mới) → tra cache theo khóa {@code petId + băm(đặc điểm thú, lịch sử)} → nếu trượt thì mới đọc ứng viên, tồn kho,
 * hồ sơ, độ phổ biến và xếp hạng. {@code group} và {@code limit} áp sau cache nên không nằm trong khóa.
 */
@Service
public class RecommendationService {

    private static final int POPULARITY_DAYS = 90;
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal DUE = BigDecimal.ONE;
    private static final BigDecimal USED_OTHER_GROOMING = new BigDecimal("0.4");
    private static final BigDecimal SAME_CATEGORY = new BigDecimal("0.4");
    private static final BigDecimal STAYED_ELSEWHERE = new BigDecimal("0.6");
    private static final BigDecimal NOT_DUE_FACTOR = new BigDecimal("0.2");
    private static final Set<StayStatus> REAL_STAYS = Set.of(StayStatus.CHECKED_IN, StayStatus.OVERDUE, StayStatus.CHECKED_OUT);
    private static final Comparator<RecommendedItem> RANKING = Comparator.comparing(RecommendedItem::score).reversed()
            .thenComparing(RecommendedItem::itemId);

    private final PetQueryApi pets;
    private final CustomerQueryApi customers;
    private final CatalogQueryApi catalog;
    private final BranchQueryApi branches;
    private final StockQueryApi stock;
    private final VisitQueryApi visits;
    private final BoardingQueryApi boarding;
    private final OrderQueryApi orders;
    private final AiItemProfileRepository profiles;
    private final SystemConfigApi config;
    private final Clock clock;
    private final RecommendationCache cache;

    public RecommendationService(PetQueryApi pets, CustomerQueryApi customers, CatalogQueryApi catalog,
            BranchQueryApi branches, StockQueryApi stock, VisitQueryApi visits, BoardingQueryApi boarding,
            OrderQueryApi orders, AiItemProfileRepository profiles, SystemConfigApi config, Clock clock,
            RecommendationCache cache) {
        this.pets = pets;
        this.customers = customers;
        this.catalog = catalog;
        this.branches = branches;
        this.stock = stock;
        this.visits = visits;
        this.boarding = boarding;
        this.orders = orders;
        this.profiles = profiles;
        this.config = config;
        this.clock = clock;
        this.cache = cache;
    }

    /** ai-v1 #4. {@code group} null = cả ba nhóm; {@code limit} null = [CFG] {@code ai.reco.max_per_group} mỗi nhóm. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecommendationResponse recommend(Long accountId, Long petId, RecoGroup group, Integer limit) {
        PetProfile pet = ownedLivingPet(accountId, petId);
        Long customerId = pet.customerId();
        LocalDate today = LocalDate.now(clock);
        Instant now = clock.instant();

        Recommendable source = catalog.listRecommendable();
        // Catalog đã lọc (I-3) nhưng BR-AI-18 là "không bao giờ": lọc lại ở đây để một lỗi của catalog không thành thuốc trong gợi ý.
        List<ServiceInfo> grooming = source.groomingServices().stream()
                .filter(s -> s.group() == ServiceGroup.GROOMING && s.active()).toList();
        List<KennelTypeInfo> kennels = source.kennelTypes().stream().filter(KennelTypeInfo::active).toList();
        List<ProductInfo> goods = source.products().stream()
                .filter(p -> p.type() == ProductType.GOODS && !p.prescription() && p.active()).toList();
        List<Long> goodsIds = goods.stream().map(ProductInfo::productId).toList();

        Map<Long, LocalDate> lastServices = visits.findLastServiceDates(petId);
        List<StayOfPet> stays = boarding.findStaysOfPet(petId);
        Map<Long, LocalDate> purchases = goodsIds.isEmpty() ? Map.of() : orders.findLastPurchaseDates(customerId, goodsIds);

        int cacheMinutes = config.getInt(ConfigKey.AI_RECO_CACHE_MINUTES);
        String key = petId + ":" + Sha256.hex(trace(pet, lastServices, stays, purchases));
        Ranked ranked = cacheMinutes > 0 ? cache.get(key, now).orElse(null) : null;
        if (ranked == null) {
            ranked = rank(pet, today, now, grooming, kennels, goods, lastServices, stays, purchases);
            if (cacheMinutes > 0) {
                cache.put(key, ranked, now, now.plus(cacheMinutes, ChronoUnit.MINUTES));
            }
        }
        return respond(petId, ranked, group, limit == null ? config.getInt(ConfigKey.AI_RECO_MAX_PER_GROUP) : limit);
    }

    // ---------------------------------------------------------------- quyền

    /** Chủ hiện tại và còn sống; kiểm chủ trước kiểm "đã mất" để thú mất của người khác vẫn là 404 (ADR-0006). */
    private PetProfile ownedLivingPet(Long accountId, Long petId) {
        Long customerId = customers.findCustomerIdByAccountId(accountId).orElse(null);
        PetProfile pet = pets.findPetProfile(petId).filter(p -> customerId != null && customerId.equals(p.customerId()))
                .orElseThrow(() -> new ResourceNotFoundException("thú cưng", petId));
        if (pet.deceased()) {
            throw new BusinessRuleViolationException("BR-KH-05", "Thú cưng đã mất, không gợi ý được");
        }
        return pet;
    }

    // ---------------------------------------------------------------- khóa cache

    /**
     * Dấu vết của mọi thứ làm gợi ý đổi theo thú và khách: đặc điểm thú (BR-AI-23) cùng lịch sử dùng dịch vụ, lưu trú và mua
     * hàng. Thiếu một trong số này thì lượt khám hoặc đơn mới vẫn nhận gợi ý cũ tới hết hạn.
     */
    private static String trace(PetProfile pet, Map<Long, LocalDate> lastServices, List<StayOfPet> stays,
            Map<Long, LocalDate> purchases) {
        String weight = pet.currentWeightKg() == null ? "" : pet.currentWeightKg().stripTrailingZeros().toPlainString();
        String stayTrace = stays.stream().map(s -> s.bookingId() + "/" + s.kennelTypeId() + "/" + s.status() + "/"
                + s.checkInDate() + "/" + s.checkOutDate()).sorted().collect(Collectors.joining(","));
        return String.join("|", String.valueOf(pet.species()), String.valueOf(pet.breed()), String.valueOf(pet.birthDate()),
                weight, new TreeMap<>(lastServices).toString(), stayTrace, new TreeMap<>(purchases).toString());
    }

    // ---------------------------------------------------------------- xếp hạng

    private Ranked rank(PetProfile pet, LocalDate today, Instant now, List<ServiceInfo> grooming,
            List<KennelTypeInfo> kennels, List<ProductInfo> goods, Map<Long, LocalDate> lastServices,
            List<StayOfPet> stays, Map<Long, LocalDate> purchases) {
        Species species = pet.species();
        AgeGroup ageGroup = AgeGroup.of(pet.birthDate(), today, seniorAgeYears(species));
        PetFacts facts = new PetFacts(species, pet.breed(),
                pet.birthDate() == null ? null : ChronoUnit.WEEKS.between(pet.birthDate(), today), pet.currentWeightKg());
        RecoWeights weights = RecoWeights.normalized(config.getDecimal(ConfigKey.AI_RECO_WEIGHT_ATTR),
                config.getDecimal(ConfigKey.AI_RECO_WEIGHT_HISTORY), config.getDecimal(ConfigKey.AI_RECO_WEIGHT_POPULARITY));

        // BR-AI-19: ứng viên.
        Set<Long> enabled = branches.findServiceIdsEnabledAnywhere();
        List<Long> goodsIds = goods.stream().map(ProductInfo::productId).toList();
        Set<Long> inStock = goodsIds.isEmpty() ? Set.of() : stock.findProductIdsInStockAnywhere(goodsIds);
        List<ServiceInfo> groomCandidates = grooming.stream().filter(s -> enabled.contains(s.serviceId())).toList();
        List<KennelTypeInfo> kennelCandidates = kennels.stream().filter(k -> enabled.contains(k.serviceId()))
                .filter(k -> k.species() == species)
                .filter(k -> pet.currentWeightKg() == null || k.maxWeightKg() == null
                        || k.maxWeightKg().compareTo(pet.currentWeightKg()) >= 0)
                .toList();
        List<ProductInfo> productCandidates = goods.stream().filter(p -> inStock.contains(p.productId())).toList();

        List<Long> serviceIds = Stream.concat(groomCandidates.stream().map(ServiceInfo::serviceId),
                kennelCandidates.stream().map(KennelTypeInfo::serviceId)).toList();
        RecommendDisplay display = catalog.findRecommendDisplay(serviceIds, goodsIds);

        Map<Long, ProfileCriteria> serviceProfiles = groomCandidates.isEmpty() ? Map.of()
                : criteriaOf(profiles.findByServiceIdIn(groomCandidates.stream().map(ServiceInfo::serviceId).toList()),
                        AiItemProfile::getServiceId);
        Map<Long, ProfileCriteria> productProfiles = productCandidates.isEmpty() ? Map.of()
                : criteriaOf(profiles.findByProductIdIn(productCandidates.stream().map(ProductInfo::productId).toList()),
                        AiItemProfile::getProductId);

        // P: cùng cohort (loài, nhóm tuổi) trong 90 ngày; sản phẩm toàn chuỗi (A4).
        LocalDate since = today.minusDays(POPULARITY_DAYS);
        Map<Long, Long> groomCounts = visits.countCompletedByService(species, ageGroup, since);
        Map<Long, Long> stayCounts = boarding.countStaysByKennelType(species, ageGroup, since);
        Map<Long, Long> soldUnits = orders.sumSoldUnitsByProduct(since);

        String cohort = cohortWords(species, ageGroup);
        Map<RecoGroup, List<RecommendedItem>> byGroup = new EnumMap<>(RecoGroup.class);
        byGroup.put(RecoGroup.GROOMING, rankGrooming(groomCandidates, serviceProfiles, facts, weights, today, lastServices,
                groomCounts, display, cohort));
        byGroup.put(RecoGroup.BOARDING, rankBoarding(kennelCandidates, facts, weights, stays, stayCounts, display, cohort));
        byGroup.put(RecoGroup.PRODUCT, rankProducts(productCandidates, productProfiles, facts, weights, today, purchases,
                soldUnits, display));
        return new Ranked(now, byGroup);
    }

    private List<RecommendedItem> rankGrooming(List<ServiceInfo> candidates, Map<Long, ProfileCriteria> profileOf,
            PetFacts facts, RecoWeights weights, LocalDate today, Map<Long, LocalDate> lastServices,
            Map<Long, Long> counts, RecommendDisplay display, String cohort) {
        // "Từng dùng dịch vụ thẩm mỹ khác": lịch sử có cả khám, tiêm nên phải phân loại theo nhóm của dịch vụ.
        boolean usedAnyGrooming = !lastServices.isEmpty() && catalog.findServices(lastServices.keySet()).stream()
                .anyMatch(s -> s.group() == ServiceGroup.GROOMING);
        long max = candidates.stream().mapToLong(s -> counts.getOrDefault(s.serviceId(), 0L)).max().orElse(0);
        int defaultInterval = config.getInt(ConfigKey.AI_RECO_DEFAULT_GROOM_INTERVAL_DAYS);
        List<RecommendedItem> items = new ArrayList<>();
        for (ServiceInfo s : candidates) {
            ProfileCriteria criteria = profileOf.get(s.serviceId());
            Optional<Match> match = RecoAttributes.evaluate(criteria, facts);
            if (match.isEmpty()) {
                continue;
            }
            List<String> reasons = new ArrayList<>(match.get().reasons());
            BigDecimal h = BigDecimal.ZERO;
            LocalDate last = lastServices.get(s.serviceId());
            if (last != null) {
                long days = Math.max(0, ChronoUnit.DAYS.between(last, today));
                int interval = interval(criteria, defaultInterval);
                if (days >= interval) {
                    h = DUE;
                    reasons.add("Đã " + days + " ngày kể từ lần dùng gần nhất");
                } else {
                    h = NOT_DUE_FACTOR.multiply(BigDecimal.valueOf(days)).divide(BigDecimal.valueOf(interval), MC);
                    reasons.add("Đã dùng cách đây " + days + " ngày");
                }
            } else if (usedAnyGrooming) {
                h = USED_OTHER_GROOMING;
                reasons.add("Bé từng dùng dịch vụ thẩm mỹ khác");
            }
            BigDecimal p = popularity(counts.getOrDefault(s.serviceId(), 0L), max);
            if (p.signum() > 0) {
                reasons.add("Nhiều chủ " + cohort + " chọn");
            }
            items.add(new RecommendedItem(RecoGroup.GROOMING, ItemType.SERVICE, s.serviceId(), s.name(), s.price(),
                    display.priceFromServiceIds().contains(s.serviceId()), null, null,
                    weights.score(match.get().a(), h, p), List.copyOf(reasons)));
        }
        return sorted(items);
    }

    private List<RecommendedItem> rankBoarding(List<KennelTypeInfo> candidates, PetFacts facts, RecoWeights weights,
            List<StayOfPet> stays, Map<Long, Long> counts, RecommendDisplay display, String cohort) {
        List<StayOfPet> real = stays.stream().filter(s -> REAL_STAYS.contains(s.status())).toList();
        long max = candidates.stream().mapToLong(k -> counts.getOrDefault(k.serviceId(), 0L)).max().orElse(0);
        List<RecommendedItem> items = new ArrayList<>();
        for (KennelTypeInfo k : candidates) {
            // Loài và cân nặng tối đa đã được BR-AI-19 kiểm khi chọn ứng viên: mọi tiêu chí đánh giá được đều khớp, A = 1.
            List<String> reasons = new ArrayList<>(List.of("Phù hợp với " + speciesWord(k.species())));
            if (k.maxWeightKg() != null && facts.weightKg() != null) {
                reasons.add("Chuồng dành cho thú tới " + k.maxWeightKg().stripTrailingZeros().toPlainString().replace('.', ',')
                        + " kg");
            }
            BigDecimal h = BigDecimal.ZERO;
            if (real.stream().anyMatch(s -> k.serviceId().equals(s.kennelTypeId()))) {
                h = DUE;
                reasons.add("Bé từng ở loại chuồng này");
            } else if (!real.isEmpty()) {
                h = STAYED_ELSEWHERE;
                reasons.add("Bé từng lưu trú tại cửa hàng");
            }
            BigDecimal p = popularity(counts.getOrDefault(k.serviceId(), 0L), max);
            if (p.signum() > 0) {
                reasons.add("Nhiều chủ " + cohort + " chọn");
            }
            items.add(new RecommendedItem(RecoGroup.BOARDING, ItemType.SERVICE, k.serviceId(), k.name(), k.nightlyPrice(),
                    display.priceFromServiceIds().contains(k.serviceId()), "đêm", null,
                    weights.score(BigDecimal.ONE, h, p), List.copyOf(reasons)));
        }
        return sorted(items);
    }

    private List<RecommendedItem> rankProducts(List<ProductInfo> candidates, Map<Long, ProfileCriteria> profileOf,
            PetFacts facts, RecoWeights weights, LocalDate today, Map<Long, LocalDate> purchases, Map<Long, Long> sold,
            RecommendDisplay display) {
        // Danh mục đã mua tính trên MỌI hàng gợi được, kể cả hàng đang hết: khách từng mua nó vẫn là tín hiệu cùng danh mục.
        Set<Long> boughtCategories = purchases.keySet().stream().map(display.products()::get)
                .filter(java.util.Objects::nonNull).map(ProductDisplay::categoryId).collect(Collectors.toCollection(HashSet::new));
        long max = candidates.stream().mapToLong(p -> sold.getOrDefault(p.productId(), 0L)).max().orElse(0);
        int defaultInterval = config.getInt(ConfigKey.AI_RECO_DEFAULT_PRODUCT_INTERVAL_DAYS);
        List<RecommendedItem> items = new ArrayList<>();
        for (ProductInfo p : candidates) {
            ProfileCriteria criteria = profileOf.get(p.productId());
            Optional<Match> match = RecoAttributes.evaluate(criteria, facts);
            if (match.isEmpty()) {
                continue;
            }
            List<String> reasons = new ArrayList<>(match.get().reasons());
            BigDecimal h = BigDecimal.ZERO;
            LocalDate last = purchases.get(p.productId());
            ProductDisplay shown = display.products().get(p.productId());
            if (last != null) {
                long days = Math.max(0, ChronoUnit.DAYS.between(last, today));
                if (days >= interval(criteria, defaultInterval)) {
                    h = DUE;
                    reasons.add("Đã " + days + " ngày kể từ lần mua gần nhất, đến lúc mua lại");
                }
            } else if (shown != null && boughtCategories.contains(shown.categoryId())) {
                h = SAME_CATEGORY;
                reasons.add("Bạn từng mua sản phẩm cùng danh mục");
            }
            BigDecimal pop = popularity(sold.getOrDefault(p.productId(), 0L), max);
            if (pop.signum() > 0) {
                reasons.add("Được nhiều khách mua gần đây");
            }
            items.add(new RecommendedItem(RecoGroup.PRODUCT, ItemType.PRODUCT, p.productId(), p.name(), p.price(), false,
                    null, shown == null ? null : shown.imageUrl(), weights.score(match.get().a(), h, pop),
                    List.copyOf(reasons)));
        }
        return sorted(items);
    }

    // ---------------------------------------------------------------- phụ trợ

    private static List<RecommendedItem> sorted(List<RecommendedItem> items) {
        return items.stream().sorted(RANKING).toList();
    }

    private static int interval(ProfileCriteria criteria, int defaultDays) {
        return criteria != null && criteria.repeatIntervalDays() != null ? criteria.repeatIntervalDays() : defaultDays;
    }

    /** {@code P = c / c_max} trong cùng nhóm; {@code c_max = 0} thì {@code P = 0} (07 §7.3). */
    private static BigDecimal popularity(long count, long max) {
        return max <= 0 ? BigDecimal.ZERO : BigDecimal.valueOf(count).divide(BigDecimal.valueOf(max), MC);
    }

    private static Map<Long, ProfileCriteria> criteriaOf(Collection<AiItemProfile> rows, Function<AiItemProfile, Long> idOf) {
        return rows.stream().collect(Collectors.toMap(idOf, AiItemProfile::criteria, (a, b) -> a));
    }

    private int seniorAgeYears(Species species) {
        return config.getInt(switch (species) {
            case DOG -> ConfigKey.AI_RECO_SENIOR_AGE_YEARS_DOG;
            case CAT -> ConfigKey.AI_RECO_SENIOR_AGE_YEARS_CAT;
            case OTHER -> ConfigKey.AI_RECO_SENIOR_AGE_YEARS_OTHER;
        });
    }

    private static String speciesWord(Species species) {
        return switch (species) {
            case DOG -> "chó";
            case CAT -> "mèo";
            case OTHER -> "thú cưng loài khác";
        };
    }

    /** "chó con", "mèo lớn tuổi"...: cohort của cột P, đúng như ví dụ "Nhiều chủ chó con chọn" (07 §7.3). */
    private static String cohortWords(Species species, AgeGroup ageGroup) {
        String word = species == Species.OTHER ? "thú cưng" : speciesWord(species);
        return switch (ageGroup) {
            case JUNIOR -> word + " con";
            case ADULT -> word + " trưởng thành";
            case SENIOR -> word + " lớn tuổi";
            case UNKNOWN -> word;
        };
    }

    private static RecommendationResponse respond(Long petId, Ranked ranked, RecoGroup group, int perGroup) {
        List<RecommendedItem> items = new ArrayList<>();
        for (RecoGroup g : RecoGroup.values()) {
            if (group == null || group == g) {
                items.addAll(ranked.byGroup().getOrDefault(g, List.of()).stream().limit(perGroup).toList());
            }
        }
        return new RecommendationResponse(petId, ranked.generatedAt(), items);
    }
}
