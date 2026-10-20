package com.petcare.module.visit.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.mapper.VisitQueryMapper;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.module.visit.repository.VisitRepository.AppointmentVisitRow;

/**
 * Cài đặt {@link VisitQueryApi} (docs/adr/0058; thay {@code VisitQueryApiPlaceholder}, trả nợ D016 phía visit và D028).
 * Chỉ đọc, mỗi method đúng một câu SQL. Class {@code readOnly} <b>tham gia</b> transaction ghi của bên gọi (identity
 * dưới khóa tài khoản, customer dưới khóa thú, sales dưới khóa Order) và không đổi flush / readOnly của nó. Không
 * {@code BranchScope}, không kiểm role: API nội bộ, bên gọi đã phân quyền và quyết định nghiệp vụ (BR-QT-08, BR-QT-11,
 * BR-KH-03, 05, 06, 08, BR-BH-02).
 */
@Service
@Transactional(readOnly = true)
public class VisitQueryService implements VisitQueryApi {

    private final VisitRepository visits;
    private final VisitQueryMapper mapper;

    public VisitQueryService(VisitRepository visits, VisitQueryMapper mapper) {
        this.visits = visits;
        this.mapper = mapper;
    }

    @Override
    public Optional<VisitSummary> findVisit(Long visitId) {
        Objects.requireNonNull(visitId, "visitId");
        return visits.findSummary(visitId).map(mapper::toSummary);
    }

    @Override
    public List<AssignedVisit> findUnfinishedVisitsAssignedTo(Long accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return visits.findUnfinishedAssignedTo(accountId).stream().map(mapper::toAssigned).toList();
    }

    @Override
    public boolean hasOpenVisit(Long petId) {
        Objects.requireNonNull(petId, "petId");
        return visits.existsOpenByPetId(petId);
    }

    @Override
    public boolean existsByPet(Long petId) {
        Objects.requireNonNull(petId, "petId");
        return visits.existsAnyByPetId(petId);
    }

    @Override
    public List<Long> findVisitIdsByPet(Long petId) {
        Objects.requireNonNull(petId, "petId");
        return visits.findIdsByPetId(petId);
    }

    @Override
    public boolean hasMedicalHistory(Long petId) {
        Objects.requireNonNull(petId, "petId");
        return visits.hasMedicalHistory(petId);
    }

    @Override
    public Map<Long, Long> findVisitIdsByAppointmentIds(Collection<Long> appointmentIds) {
        Objects.requireNonNull(appointmentIds, "appointmentIds");
        if (appointmentIds.isEmpty()) {
            return Map.of();
        }
        return visits.findByAppointmentIds(appointmentIds).stream()
                .collect(Collectors.toUnmodifiableMap(AppointmentVisitRow::appointmentId, AppointmentVisitRow::visitId));
    }
}
