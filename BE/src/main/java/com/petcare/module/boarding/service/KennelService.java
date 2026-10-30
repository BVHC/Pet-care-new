package com.petcare.module.boarding.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.dto.AffectedBookingBriefResponse;
import com.petcare.module.boarding.dto.CreateKennelRequest;
import com.petcare.module.boarding.dto.KennelResponse;
import com.petcare.module.boarding.dto.MaintenanceResultResponse;
import com.petcare.module.boarding.dto.UpdateKennelRequest;
import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.Kennel;
import com.petcare.module.boarding.entity.KennelStatus;
import com.petcare.module.boarding.exception.BoardingConflictException;
import com.petcare.module.boarding.fsm.KennelTransitionHandler;
import com.petcare.module.boarding.repository.BoardingBookingRepository;
import com.petcare.module.boarding.repository.BoardingBookingRepository.KennelBooking;
import com.petcare.module.boarding.repository.KennelRepository;
import com.petcare.module.branch.api.BranchApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.KennelTypeInfo;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * Chuồng (UC54, boarding-v1 #1–6; docs/adr/0065). Mọi thao tác ghi: phân quyền theo chi nhánh của chuồng (đọc không
 * khóa) → khóa dòng {@code branches} ({@code BranchApi.lockForBooking}, cùng khóa với đặt chỗ nên sức chứa không đổi
 * giữa chừng) → khóa dòng chuồng → guard → ghi → {@code saveAndFlush}. Không audit (BR-QT-15 không gồm lưu trú).
 */
@Service
public class KennelService {

    static final int PET_BATCH = 100;

    private final BranchScope branchScope;
    private final KennelRepository kennels;
    private final BoardingBookingRepository bookings;
    private final BranchApi branchLocks;
    private final BranchQueryApi branches;
    private final CatalogQueryApi catalog;
    private final BoardingCapacity capacity;
    private final PetQueryApi pets;
    private final KennelTransitionHandler transitions;
    private final Clock clock;

    public KennelService(BranchScope branchScope, KennelRepository kennels, BoardingBookingRepository bookings,
            BranchApi branchLocks, BranchQueryApi branches, CatalogQueryApi catalog, BoardingCapacity capacity,
            PetQueryApi pets, KennelTransitionHandler transitions, Clock clock) {
        this.branchScope = branchScope;
        this.kennels = kennels;
        this.bookings = bookings;
        this.branchLocks = branchLocks;
        this.branches = branches;
        this.catalog = catalog;
        this.capacity = capacity;
        this.pets = pets;
        this.transitions = transitions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<KennelResponse> listKennels(Long kennelTypeId, KennelStatus status) {
        Long branchId = branchScope.resolve(null);
        List<Kennel> rows = kennels.findByBranchIdOrderByCodeAsc(branchId).stream()
                .filter(k -> kennelTypeId == null || k.getKennelTypeId().equals(kennelTypeId))
                .filter(k -> status == null || k.getStatus() == status).toList();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> typeNames = catalog.findServices(
                rows.stream().map(Kennel::getKennelTypeId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(CatalogQueryApi.ServiceInfo::serviceId, CatalogQueryApi.ServiceInfo::name));
        Map<Long, Long> current = bookings.findCurrentByKennelIds(rows.stream().map(Kennel::getId).toList()).stream()
                .collect(Collectors.toMap(KennelBooking::getKennelId, KennelBooking::getBookingId));
        return rows.stream().map(k -> toResponse(k, typeNames.get(k.getKennelTypeId()), current.get(k.getId())))
                .toList();
    }

    @Transactional
    public KennelResponse createKennel(CreateKennelRequest request) {
        Long branchId = branchScope.current().branchId();
        KennelTypeInfo type = catalog.findKennelType(request.kennelTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("loại chuồng", request.kennelTypeId()));
        branchLocks.lockForBooking(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("chi nhánh", branchId));
        if (!type.active() || !branches.isServiceEnabled(branchId, type.serviceId())) {
            throw new BusinessRuleViolationException("BR-LT-01",
                    "Loại chuồng không phải dịch vụ lưu trú đang bật tại chi nhánh");
        }
        String code = request.code().strip();
        if (kennels.existsByBranchIdAndCode(branchId, code)) {
            throw new BusinessRuleViolationException("BR-LT-01", "Mã chuồng đã tồn tại trong chi nhánh");
        }
        transitions.validateInitial(KennelStatus.AVAILABLE);
        Kennel saved = kennels.saveAndFlush(Kennel.create(branchId, type.serviceId(), code, blankToNull(request.note())));
        return toResponse(saved, type.name(), null);
    }

    @Transactional
    public KennelResponse updateKennel(Long kennelId, UpdateKennelRequest request) {
        Kennel kennel = lockKennel(kennelId);
        if (request.code() != null) {
            String code = request.code().strip();
            if (!code.equals(kennel.getCode()) && kennels.existsByBranchIdAndCodeAndIdNot(kennel.getBranchId(), code,
                    kennelId)) {
                throw new BusinessRuleViolationException("BR-LT-01", "Mã chuồng đã tồn tại trong chi nhánh");
            }
            kennel.changeCode(code);
        }
        if (request.note() != null) {
            kennel.changeNote(blankToNull(request.note()));
        }
        return toResponse(kennels.saveAndFlush(kennel));
    }

    @Transactional
    public void deleteKennel(Long kennelId) {
        Kennel kennel = lockKennel(kennelId);
        if (kennel.getStatus() == KennelStatus.OCCUPIED) {
            throw new BoardingConflictException("Chuồng đang có thú, không xóa được");
        }
        if (kennels.hasBookingHistory(kennelId)) {
            throw new BoardingConflictException("Chuồng đã có lịch sử lưu trú — chuyển bảo trì thay vì xóa");
        }
        kennels.delete(kennel);
        kennels.flush();
    }

    @Transactional
    public MaintenanceResultResponse startMaintenance(Long kennelId) {
        Kennel kennel = lockKennel(kennelId);
        transitions.validateTransition(kennel.getStatus(), KennelStatus.MAINTENANCE);
        kennel.startMaintenance();
        Kennel saved = kennels.saveAndFlush(kennel);
        return new MaintenanceResultResponse(toResponse(saved), shortageBookings(saved));
    }

    @Transactional
    public KennelResponse endMaintenance(Long kennelId) {
        Kennel kennel = lockKennel(kennelId);
        transitions.validateTransition(kennel.getStatus(), KennelStatus.AVAILABLE);
        kennel.endMaintenance();
        return toResponse(kennels.saveAndFlush(kennel));
    }

    /** Phân quyền trên chi nhánh của chuồng (đọc không khóa) → khóa {@code branches} → khóa chuồng. */
    private Kennel lockKennel(Long kennelId) {
        Long branchId = kennels.findBranchIdById(kennelId)
                .orElseThrow(() -> new ResourceNotFoundException("chuồng", kennelId));
        branchScope.check(branchId);
        branchLocks.lockForBooking(branchId).orElseThrow(() -> new ResourceNotFoundException("chi nhánh", branchId));
        return kennels.findByIdForUpdate(kennelId).orElseThrow(() -> new ResourceNotFoundException("chuồng", kennelId));
    }

    /**
     * Đặt chỗ BOOKED của cùng loại chuồng có ít nhất một đêm (từ hôm nay) mà số chiếm chỗ vượt sức chứa mới (BR-LT-01,
     * D11); không tự hủy. Chạy sau flush nên {@code countCapacity} đã thấy chuồng bảo trì.
     */
    private List<AffectedBookingBriefResponse> shortageBookings(Kennel kennel) {
        LocalDate today = LocalDate.now(clock);
        List<BoardingBooking> affected = capacity.shortageBookings(kennel.getBranchId(), kennel.getKennelTypeId(),
                today, null);
        if (affected.isEmpty()) {
            return List.of();
        }
        Map<Long, String> petNames = new HashMap<>();
        List<Long> petIds = affected.stream().map(BoardingBooking::getPetId).distinct().toList();
        for (int i = 0; i < petIds.size(); i += PET_BATCH) {
            for (PetSummary pet : pets.findPets(petIds.subList(i, Math.min(petIds.size(), i + PET_BATCH)))) {
                petNames.put(pet.petId(), pet.name());
            }
        }
        List<AffectedBookingBriefResponse> result = new ArrayList<>();
        for (BoardingBooking b : affected) {
            result.add(new AffectedBookingBriefResponse(b.getId(), b.getCode(), b.getCheckInDate(), b.getCheckOutDate(),
                    Objects.requireNonNull(petNames.get(b.getPetId()), "Thiếu thú #" + b.getPetId())));
        }
        return result;
    }

    private KennelResponse toResponse(Kennel kennel) {
        String typeName = catalog.findKennelType(kennel.getKennelTypeId()).map(KennelTypeInfo::name)
                .orElseThrow(() -> new IllegalStateException("Chuồng #" + kennel.getId()
                        + " trỏ tới loại chuồng không có: " + kennel.getKennelTypeId()));
        Long current = bookings.findCurrentByKennelIds(List.of(kennel.getId())).stream()
                .map(KennelBooking::getBookingId).findFirst().orElse(null);
        return toResponse(kennel, typeName, current);
    }

    private static KennelResponse toResponse(Kennel kennel, String typeName, Long currentBookingId) {
        return new KennelResponse(kennel.getId(), kennel.getBranchId(), kennel.getKennelTypeId(), typeName,
                kennel.getCode(), kennel.getStatus(), kennel.getNote(), currentBookingId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
