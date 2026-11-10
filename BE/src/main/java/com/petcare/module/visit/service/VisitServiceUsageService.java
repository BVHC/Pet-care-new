package com.petcare.module.visit.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.visit.api.VisitQueryApi.AgeGroup;
import com.petcare.module.visit.repository.VisitUsageRepository;
import com.petcare.module.visit.repository.VisitUsageRepository.CompletedVisit;
import com.petcare.platform.config.TimeConfig;

/**
 * I-6 (docs/07 §11): dịch vụ đã dùng của thú và số lượt hoàn tất theo dịch vụ. {@code visits} không có {@code service_id}:
 * dịch vụ của lượt là các dòng {@code SERVICE} của Order nguồn VISIT, lấy theo lô qua {@code VisitOrderApi} (không JOIN
 * bảng của sales). Loài, ngày sinh của thú lấy qua {@code PetQueryApi}. Chỉ đọc, không khóa.
 */
@Service
@Transactional(readOnly = true)
public class VisitServiceUsageService {

    /** {@code PetQueryApi.findPets} và {@code findServiceIdsByVisitIds}: bên gọi truyền tối đa 100 id mỗi lần. */
    private static final int BATCH = 100;

    private final VisitUsageRepository usage;
    private final PetQueryApi pets;
    private final VisitOrderApi visitOrders;
    private final SystemConfigApi config;
    private final Clock clock;

    public VisitServiceUsageService(VisitUsageRepository usage, PetQueryApi pets, VisitOrderApi visitOrders,
            SystemConfigApi config, Clock clock) {
        this.usage = usage;
        this.pets = pets;
        this.visitOrders = visitOrders;
        this.config = config;
        this.clock = clock;
    }

    /** {@code serviceId → ngày hoàn tất gần nhất} (giờ Việt Nam) trong các Visit {@code COMPLETED} của thú. */
    public Map<Long, LocalDate> findLastServiceDates(Long petId) {
        Map<Long, LocalDate> completedOn = usage.completedOfPet(petId).stream().collect(Collectors.toMap(
                CompletedVisit::visitId, v -> v.completedAt().atZone(TimeConfig.BUSINESS_ZONE).toLocalDate()));
        Map<Long, LocalDate> result = new LinkedHashMap<>();
        serviceIdsOf(completedOn.keySet()).forEach((visitId, serviceIds) -> serviceIds.forEach(serviceId ->
                result.merge(serviceId, completedOn.get(visitId), (a, b) -> a.isAfter(b) ? a : b)));
        return result;
    }

    /**
     * {@code serviceId → số lượt} Visit {@code COMPLETED} từ 00:00 ngày {@code since} của thú thuộc {@code species} và
     * {@code ageGroup} (tuổi tính tại ngày gọi). Một lượt có hai dòng cùng dịch vụ vẫn chỉ tính một lượt cho dịch vụ đó.
     */
    public Map<Long, Long> countCompletedByService(Species species, AgeGroup ageGroup, LocalDate since) {
        List<CompletedVisit> done = usage.completedSince(since.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant());
        Set<Long> petsInGroup = petIdsInGroup(done.stream().map(CompletedVisit::petId).distinct().toList(), species,
                ageGroup);
        List<Long> visitIds = done.stream().filter(v -> petsInGroup.contains(v.petId())).map(CompletedVisit::visitId)
                .toList();
        Map<Long, Long> result = new LinkedHashMap<>();
        serviceIdsOf(visitIds).values().forEach(serviceIds ->
                serviceIds.stream().distinct().forEach(serviceId -> result.merge(serviceId, 1L, Long::sum)));
        return result;
    }

    private Map<Long, List<Long>> serviceIdsOf(Collection<Long> visitIds) {
        List<Long> ids = List.copyOf(visitIds);
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        for (int from = 0; from < ids.size(); from += BATCH) {
            result.putAll(visitOrders.findServiceIdsByVisitIds(ids.subList(from, Math.min(from + BATCH, ids.size()))));
        }
        return result;
    }

    private Set<Long> petIdsInGroup(List<Long> petIds, Species species, AgeGroup ageGroup) {
        LocalDate today = LocalDate.now(clock);
        int seniorAgeYears = config.getInt(switch (species) {
            case DOG -> ConfigKey.AI_RECO_SENIOR_AGE_YEARS_DOG;
            case CAT -> ConfigKey.AI_RECO_SENIOR_AGE_YEARS_CAT;
            case OTHER -> ConfigKey.AI_RECO_SENIOR_AGE_YEARS_OTHER;
        });
        Set<Long> result = new HashSet<>();
        for (int from = 0; from < petIds.size(); from += BATCH) {
            pets.findPets(petIds.subList(from, Math.min(from + BATCH, petIds.size()))).stream()
                    .filter(p -> p.species() == species && AgeGroup.of(p.birthDate(), today, seniorAgeYears) == ageGroup)
                    .forEach(p -> result.add(p.petId()));
        }
        return result;
    }
}
