package com.petcare.module.pet.controller;

import com.petcare.module.pet.dto.CreatePetRequest;
import com.petcare.module.pet.dto.PetResponse;
import com.petcare.module.pet.dto.TransferPetRequest;
import com.petcare.module.pet.dto.UpdatePetRequest;
import com.petcare.module.pet.service.PetService;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.UserPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
@RestController
@RequestMapping("/api/pets")
@RequiredArgsConstructor
public class PetController {

    private final PetService svc;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PetResponse>> create(
            @AuthenticationPrincipal UserPrincipal me, @Valid @RequestBody CreatePetRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(svc.create(me.getUserId(), req), "success"));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PageResponse<PetResponse>>> list(
            @AuthenticationPrincipal UserPrincipal me, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.of(svc.list(me.getUserId(), pageable))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PetResponse>> detail(
            @AuthenticationPrincipal UserPrincipal me, @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(svc.detail(me.getUserId(), id)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PetResponse>> update(
            @AuthenticationPrincipal UserPrincipal me, @PathVariable UUID id,
            @Valid @RequestBody UpdatePetRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.update(me.getUserId(), id, req)));
    }

    @PostMapping("/{id}/transfer")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PetResponse>> transfer(
            @AuthenticationPrincipal UserPrincipal me, @PathVariable UUID id,
            @Valid @RequestBody TransferPetRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.managePetOwnership(me.getUserId(), id, req)));
    }
}
