package com.petcare.module.identity.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.identity.dto.ChangePasswordRequest;
import com.petcare.module.identity.service.ChangePasswordService;

import jakarta.validation.Valid;

/**
 * {@code /api/me/**} của identity-v1 — thao tác của chính người đang đăng nhập (mọi role, cần token; không
 * {@code @PreAuthorize}). Hiện có: đổi mật khẩu (UC05).
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final ChangePasswordService changePasswords;

    public MeController(ChangePasswordService changePasswords) {
        this.changePasswords = changePasswords;
    }

    /**
     * UC05 (docs/adr/0022). Được miễn chặn BR-TK-17 ({@code MustChangePasswordInterceptor}); thành công thì gỡ cờ bắt
     * đổi mật khẩu và hủy mọi phiên khác, phiên đang dùng giữ nguyên. 204 không có body (docs/api/00-method.md §3.4).
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        changePasswords.changePassword(request);
    }
}
