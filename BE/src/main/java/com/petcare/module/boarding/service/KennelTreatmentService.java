package com.petcare.module.boarding.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.api.KennelApi;
import com.petcare.module.boarding.entity.Kennel;
import com.petcare.module.boarding.entity.KennelStatus;
import com.petcare.module.boarding.fsm.KennelTransitionHandler;
import com.petcare.module.boarding.repository.KennelRepository;
import com.petcare.module.boarding.repository.KennelRepository.IdCode;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * {@link KennelApi}: chuồng điều trị của nội trú (docs/09 §C, docs/adr/0124 mục 5, docs/adr/0148). Chuồng#6 và #7 dùng
 * lại {@link Kennel#occupy()} / {@link Kennel#release()} và whitelist của {@link KennelTransitionHandler}; khác lưu
 * trú ở chỗ không kiểm loại chuồng, cân nặng hay sức chứa đặt chỗ. Khóa dòng chuồng trước khi đọc, ghi bằng
 * {@code saveAndFlush}; không audit (BR-QT-15 không gồm lưu trú).
 */
@Service
public class KennelTreatmentService implements KennelApi {

    private final KennelRepository kennels;
    private final KennelTransitionHandler transitions;

    public KennelTreatmentService(KennelRepository kennels, KennelTransitionHandler transitions) {
        this.kennels = kennels;
        this.transitions = transitions;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void occupyForTreatment(Long kennelId, Long branchId) {
        Kennel kennel = kennels.findByIdForUpdate(kennelId)
                .orElseThrow(() -> new ResourceNotFoundException("chuồng", kennelId));
        if (!kennel.getBranchId().equals(branchId)) {
            throw new BusinessRuleViolationException("BR-LT-13", "Chuồng phải thuộc chi nhánh của lượt khám");
        }
        if (kennel.getStatus() != KennelStatus.AVAILABLE) {
            throw new BusinessRuleViolationException("BR-LT-13", "Chuồng không còn trống");
        }
        transitions.validateTransition(kennel.getStatus(), KennelStatus.OCCUPIED);
        kennel.occupy();
        kennels.saveAndFlush(kennel);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, String> findCodesByKennelIds(Collection<Long> kennelIds) {
        return kennelIds == null || kennelIds.isEmpty() ? Map.of() : toMap(kennels.findCodesByIdIn(kennelIds));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, String> findCodesByBookingIds(Collection<Long> bookingIds) {
        return bookingIds == null || bookingIds.isEmpty() ? Map.of()
                : toMap(kennels.findCodesByBookingIdIn(bookingIds));
    }

    private static Map<Long, String> toMap(List<IdCode> rows) {
        return rows.stream().collect(Collectors.toMap(IdCode::getId, IdCode::getCode));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Long kennelId) {
        Kennel kennel = kennels.findByIdForUpdate(kennelId)
                .orElseThrow(() -> new ResourceNotFoundException("chuồng", kennelId));
        // Whitelist cho phép MAINTENANCE → AVAILABLE (Chuồng#5, hết bảo trì); chỉ Chuồng#7 mới được giải phóng ở đây.
        if (kennel.getStatus() != KennelStatus.OCCUPIED) {
            throw new InvalidStateTransitionException("chuồng (chỉ giải phóng được chuồng đang có thú)",
                    kennel.getStatus().name(), KennelStatus.AVAILABLE.name());
        }
        transitions.validateTransition(kennel.getStatus(), KennelStatus.AVAILABLE);
        kennel.release();
        kennels.saveAndFlush(kennel);
    }
}
