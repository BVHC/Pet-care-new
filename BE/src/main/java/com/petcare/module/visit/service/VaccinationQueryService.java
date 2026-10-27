package com.petcare.module.visit.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.visit.api.VaccinationQueryApi;
import com.petcare.module.visit.repository.VaccinationRepository;
import com.petcare.module.visit.repository.VaccinationRepository.DoseType;
import com.petcare.module.visit.repository.VaccinationRepository.LatestDose;

/**
 * Cài đặt {@link VaccinationQueryApi} (docs/adr/0072; thay {@code VaccinationQueryApiPlaceholder}). Chỉ đọc, không
 * khóa. Class {@code readOnly} <b>tham gia</b> transaction ghi của bên gọi (boarding đặt chỗ, catalog xóa loại) và
 * không đổi flush / readOnly của nó; không {@code catch}, nên lỗi dữ liệu làm rollback cả bên gọi. Không
 * {@code BranchScope}, không kiểm role: API nội bộ, <b>bên gọi phải phân quyền (role, chủ thú, chi nhánh) trước khi
 * gọi</b>. Chỉ tính mũi tiêm tại hệ thống (04 nguyên tắc 9).
 */
@Service
@Transactional(readOnly = true)
public class VaccinationQueryService implements VaccinationQueryApi {

    private final VaccinationRepository vaccinations;
    private final PetQueryApi pets;
    private final CatalogQueryApi catalog;

    public VaccinationQueryService(VaccinationRepository vaccinations, PetQueryApi pets, CatalogQueryApi catalog) {
        this.vaccinations = vaccinations;
        this.pets = pets;
        this.catalog = catalog;
    }

    /**
     * BR-LT-05: loại bắt buộc của loài mà thú chưa từng tiêm, hoặc mũi gần nhất đã quá ngày tái chủng
     * ({@code next_due_date < onDate}; đúng ngày tái chủng vẫn đạt). Sắp theo {@code vaccineTypeId} tăng dần.
     */
    @Override
    public List<BoardingVaccineGap> findBoardingVaccineGaps(Long petId, LocalDate onDate) {
        Objects.requireNonNull(petId, "petId");
        Objects.requireNonNull(onDate, "onDate");
        PetSummary pet = pets.findPet(petId)
                .orElseThrow(() -> new IllegalStateException("Thú cưng #" + petId + " không tồn tại"));
        List<Long> required = catalog.findBoardingRequiredVaccineTypeIds(pet.species());
        if (required.isEmpty()) {
            return List.of();
        }
        Map<Long, LatestDose> latest = vaccinations.findLatestDoses(petId, required).stream()
                .collect(Collectors.toMap(LatestDose::getVaccineTypeId, Function.identity()));
        return required.stream().sorted().distinct().map(typeId -> gapOf(typeId, latest.get(typeId), onDate))
                .filter(Objects::nonNull).toList();
    }

    @Override
    public boolean existsByVaccineType(Long vaccineTypeId) {
        Objects.requireNonNull(vaccineTypeId, "vaccineTypeId");
        return vaccinations.existsByVaccineTypeId(vaccineTypeId);
    }

    @Override
    public Map<Long, Long> findVaccineTypeIds(Collection<Long> vaccinationIds) {
        Objects.requireNonNull(vaccinationIds, "vaccinationIds");
        if (vaccinationIds.isEmpty()) {
            return Map.of();
        }
        return vaccinations.findVaccineTypeIds(vaccinationIds).stream()
                .collect(Collectors.toMap(DoseType::getId, DoseType::getVaccineTypeId));
    }

    private static BoardingVaccineGap gapOf(Long typeId, LatestDose dose, LocalDate onDate) {
        if (dose == null) {
            return new BoardingVaccineGap(typeId, null, null);
        }
        if (dose.getNextDueDate().isBefore(onDate)) {
            return new BoardingVaccineGap(typeId, dose.getAdministeredOn(), dose.getNextDueDate());
        }
        return null;
    }
}
