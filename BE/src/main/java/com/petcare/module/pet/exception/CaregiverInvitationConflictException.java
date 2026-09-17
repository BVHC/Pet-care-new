package com.petcare.module.pet.exception;

import com.petcare.platform.exception.BusinessRuleViolationException;

import java.util.UUID;

/**
 * Đã tồn tại lời mời (INVITED) hoặc ủy quyền (ACTIVE) cho cặp (pet, email).
 *
 * Lý do tạo exception riêng (docs/convention/backend/04-exception-handling.md §4.2,
 * TIÊU CHÍ 2): cần HTTP 409 thay vì 400 mặc định của class cha — contract
 * docs/api/customer-pet-v1.md §C3 ghi 409, và docs/api/00-method.md §1.4 chốt
 * "conflict -> 409".
 *
 * RULE-ID trích dẫn là RULE-04-05 (vòng đời lời mời); ràng buộc "không trùng" là
 * derived từ contract §C3, docs không có câu rule literal cho nó.
 */
public class CaregiverInvitationConflictException extends BusinessRuleViolationException {

    public CaregiverInvitationConflictException(UUID petId, String caregiverEmail) {
        super("RULE-04-05", String.format(
                "Đã tồn tại lời mời hoặc ủy quyền còn hiệu lực cho %s trên pet %s (RULE-04-05)",
                caregiverEmail, petId));
    }
}
