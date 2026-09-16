package com.petcare.module.pet.exception;

import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.GlobalExceptionHandler;
import com.petcare.platform.model.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CaregiverExceptionMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unauthorizedDelegatedAction_is403WithContractErrorCode() {
        ResponseEntity<ErrorResponse> res = handler.handleUnauthorizedDelegatedAction(
                new UnauthorizedDelegatedActionException("PET_OWNER_OR_ACTIVE_CAREGIVER", "NOT_DELEGATED"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().errorCode()).isEqualTo("UNAUTHORIZED_DELEGATED_ACTION");
    }

    @Test
    void invitationConflict_is409() {
        ResponseEntity<ErrorResponse> res = handler.handleCaregiverInvitationConflict(
                new CaregiverInvitationConflictException(UUID.randomUUID(), "cg@example.com"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().errorCode()).isEqualTo("CAREGIVER_INVITATION_CONFLICT");
    }

    /** Subclass phải giữ nguyên quan hệ kế thừa để test Pet cũ không vỡ. */
    @Test
    void subclassesKeepBaseTypes() {
        assertThat(new UnauthorizedDelegatedActionException("a", "b"))
                .isInstanceOf(AccessDeniedScopeException.class);
        assertThat(new CaregiverInvitationConflictException(UUID.randomUUID(), "cg@example.com"))
                .isInstanceOf(BusinessRuleViolationException.class);
    }
}
