package com.petcare.module.pet.controller;

import com.petcare.module.notification.service.NotificationService;
import com.petcare.module.pet.dto.CaregiverDelegationResponse;
import com.petcare.module.pet.dto.CaregiverInvitationResponse;
import com.petcare.module.pet.dto.InviteCaregiverRequest;
import com.petcare.module.pet.dto.RevokeCaregiverRequest;
import com.petcare.module.pet.service.CaregiverDelegationService;
import com.petcare.module.pet.service.CaregiverInvitationOutcome;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.security.UserPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
@RestController
@RequiredArgsConstructor
public class CaregiverDelegationController {

    private final CaregiverDelegationService svc;
    private final NotificationService notificationService;

    @PostMapping("/api/pets/{id}/caregiver-invitations")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CaregiverInvitationResponse>> invite(
            @AuthenticationPrincipal UserPrincipal me, @PathVariable UUID id,
            @Valid @RequestBody InviteCaregiverRequest req) {
        CaregiverInvitationOutcome outcome = svc.inviteCaregiver(me.getUserId(), id, req);
        // Dispatch NGOÀI transaction đã commit — lỗi SMTP tạm thời không rollback
        // việc tạo delegation (javadoc NotificationService, khuôn AuthController).
        if (outcome.notificationTaskId() != null) {
            notificationService.dispatch(outcome.notificationTaskId(), outcome.toAddress());
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(outcome.response(), "success"));
    }

    @PostMapping("/api/caregiver-invitations/{token}/accept")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CaregiverDelegationResponse>> accept(
            @AuthenticationPrincipal UserPrincipal me, @PathVariable String token) {
        return ResponseEntity.ok(ApiResponse.ok(svc.acceptCaregiverInvitation(me.getUserId(), token)));
    }

    @PostMapping("/api/caregiver-invitations/{token}/reject")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CaregiverDelegationResponse>> reject(
            @AuthenticationPrincipal UserPrincipal me, @PathVariable String token) {
        return ResponseEntity.ok(ApiResponse.ok(svc.rejectCaregiverInvitation(me.getUserId(), token)));
    }

    @PostMapping("/api/pets/{id}/caregiver-revoke")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CaregiverDelegationResponse>> revoke(
            @AuthenticationPrincipal UserPrincipal me, @PathVariable UUID id,
            @Valid @RequestBody RevokeCaregiverRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.revokeCaregiver(me.getUserId(), id, req)));
    }
}
