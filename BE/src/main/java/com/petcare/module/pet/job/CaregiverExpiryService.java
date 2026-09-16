package com.petcare.module.pet.job;

import com.petcare.module.pet.entity.PetCaregiverDelegation;
import com.petcare.module.pet.fsm.CaregiverDelegationTransitionHandler;
import com.petcare.module.pet.repository.PetCaregiverDelegationRepository;
import com.petcare.platform.enums.CaregiverStatus;
import com.petcare.platform.outbox.OutboxEvent;
import com.petcare.platform.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RULE-04-05 (ProcessInvitationExpiry) + RULE-04-07 (ProcessDelegationExpiry).
 * Đọc từng dòng rồi update thay vì bulk UPDATE, vì mỗi dòng phải sinh một outbox
 * event; volume nhỏ như otps nên không cần batch (xem OtpExpiryJob).
 * Job này chỉ DỌN DẸP — nó không phải cơ chế bảo vệ; guard tự kiểm hạn (spec D-08).
 */
@Service
@RequiredArgsConstructor
public class CaregiverExpiryService {

    private final PetCaregiverDelegationRepository delegations;
    private final CaregiverDelegationTransitionHandler transitions;
    private final OutboxEventRepository outbox;

    @Transactional
    public int processInvitationExpiry() {
        return expire(delegations.findExpiredInvitations(LocalDateTime.now()),
                "CaregiverInvitationExpired");
    }

    @Transactional
    public int processDelegationExpiry() {
        return expire(delegations.findExpiredDelegations(LocalDateTime.now()),
                "CaregiverDelegationExpired");
    }

    private int expire(List<PetCaregiverDelegation> rows, String eventType) {
        for (PetCaregiverDelegation d : rows) {
            transitions.validateTransition(d.getStatus(), CaregiverStatus.EXPIRED);
            d.setStatus(CaregiverStatus.EXPIRED);
            OutboxEvent event = new OutboxEvent();
            event.setAggregateType("Pet");
            event.setAggregateId(d.getPetId().toString());
            event.setEventType(eventType);
            event.setPayload("{\"petId\":\"" + d.getPetId() + "\",\"delegationId\":\"" + d.getId() + "\"}");
            outbox.save(event);
        }
        return rows.size();
    }
}
