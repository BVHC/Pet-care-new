package com.petcare.module.identity.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.identity.dto.ForgotPasswordRequest;
import com.petcare.module.identity.dto.LoginRequest;
import com.petcare.module.identity.dto.LoginResponse;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.dto.RegisterAccountRequest;
import com.petcare.module.identity.dto.RegistrationResponse;
import com.petcare.module.identity.dto.ResendRegistrationOtpRequest;
import com.petcare.module.identity.dto.ResetPasswordRequest;
import com.petcare.module.identity.dto.VerificationResponse;
import com.petcare.module.identity.dto.VerifyAccountRequest;
import com.petcare.module.identity.service.LoginService;
import com.petcare.module.identity.service.LogoutService;
import com.petcare.module.identity.service.PasswordResetService;
import com.petcare.module.identity.service.RegistrationService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * {@code /api/auth/**} của identity-v1. Mọi path ở đây là public, luôn xử lý như chưa đăng nhập (docs/adr/0005),
 * <b>trừ</b> {@code /logout}: path đó cần đăng nhập (docs/adr/0003 mục 6). Không {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final RegistrationService registrations;
    private final LoginService logins;
    private final LogoutService logouts;
    private final PasswordResetService passwordResets;

    public AuthController(RegistrationService registrations, LoginService logins, LogoutService logouts,
            PasswordResetService passwordResets) {
        this.registrations = registrations;
        this.logins = logins;
        this.logouts = logouts;
        this.passwordResets = passwordResets;
    }

    @Operation(operationId = "registerAccount", tags = "Auth",
            summary = "Đăng ký tài khoản",
            description = "UC01 · BR-TK-01, 02, 03, 04, 05, 07, BR-KH-01. Actor: A01. Quyền: Public. "
                + "Chuyển trạng thái: — → PENDING (Tài khoản#1); tạo hồ sơ online. Gửi OTP_REGISTER qua outbox (A8).")
    @ApiErrorDoc(status = 400, description = "`BR-TK-01` email đã được sử dụng · `BR-TK-02` chưa xác nhận "
            + "≥ 18 tuổi / điều khoản · `BR-TK-03` mật khẩu yếu hoặc quá 72 byte · `BR-TK-07` email đã nhận quá 5 "
            + "mã OTP/giờ [CFG]")
    @ApiErrorDoc(status = 409, description = "`CONCURRENCY_CONFLICT` hai request đăng ký cùng email cùng "
            + "lúc")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RegistrationResponse> register(@Valid @RequestBody RegisterAccountRequest request) {
        return ApiResponse.created(registrations.registerAccount(request),
                "Đăng ký thành công, vui lòng kiểm tra email để lấy mã OTP");
    }

    @Operation(operationId = "verifyRegistration", tags = "Auth",
            summary = "Xác thực OTP đăng ký",
            description = "UC02 · BR-TK-05, 06, 19. Actor: A01. Quyền: Public. Chuyển trạng thái: PENDING "
                + "→ ACTIVE (Tài khoản#2). Không tự đăng nhập (A2).")
    @ApiErrorDoc(status = 400, description = "`BR-TK-05` mã sai / hết hạn · `BR-TK-06` sai quá 5 lần, mã "
            + "bị hủy")
    @ApiErrorDoc(status = 404, description = "Không có tài khoản PENDING với email này")
    @PostMapping("/register/verify")
    public ApiResponse<VerificationResponse> verify(@Valid @RequestBody VerifyAccountRequest request) {
        return ApiResponse.ok(registrations.verifyAccount(request), "Xác thực tài khoản thành công");
    }

    @Operation(operationId = "resendRegistrationOtp", tags = "Auth",
            summary = "Gửi lại OTP đăng ký",
            description = "UC02 · BR-TK-04, 05, 07. Actor: A01. Quyền: Public. Chuyển trạng thái: —. Mã cũ "
                + "cùng mục đích mất hiệu lực (BR-TK-05).")
    @ApiErrorDoc(status = 400, description = "`BR-TK-07` chưa đủ 60 giây hoặc vượt 5 mã/giờ")
    @ApiErrorDoc(status = 404, description = "Không có tài khoản PENDING với email này")
    @PostMapping("/register/resend-otp")
    public ApiResponse<OtpSentResponse> resendRegistrationOtp(
            @Valid @RequestBody ResendRegistrationOtpRequest request) {
        return ApiResponse.ok(registrations.resendRegistrationOtp(request),
                "Đã gửi lại mã OTP, vui lòng kiểm tra email");
    }

    /** IP client sau proxy đã là {@code getRemoteAddr()} (docs/adr/0002); lưu vào phiên (docs/adr/0003). */
    @Operation(operationId = "login", tags = "Auth",
            summary = "Đăng nhập",
            description = "UC03 · BR-TK-08, 09, 10, 11, 17, BR-QT-15. Actor: A02–A08. Quyền: Public. "
                + "Chuyển trạng thái: —. Ghi audit thành công và thất bại (ADR-0019). `account.mustChangePassword = "
                + "true` vẫn đăng nhập được (BR-TK-17). Request sai hình thức (400 `VALIDATION_FAILED` / "
                + "`MALFORMED_REQUEST`) không tính là một lần đăng nhập sai.")
    @ApiErrorDoc(status = 400, description = "Chỉ khi mật khẩu đúng (ADR-0019): `BR-TK-08` tài khoản chưa "
            + "xác thực → FE chuyển màn OTP · `BR-TK-09` đang khóa tạm, message có giờ thử lại (`HH:mm "
            + "dd/MM/yyyy`) · `BR-TK-11` tài khoản bị khóa / vô hiệu hóa")
    @ApiErrorDoc(status = 401, description = "Sai email hoặc mật khẩu — thông điệp chung \"Email hoặc mật "
            + "khẩu không đúng\" (BR-TK-10); sai lần thứ 5 trong 15 phút [CFG] kích hoạt ST01 (khóa tạm + email "
            + "cảnh báo); sai trong lúc khóa không đếm")
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(logins.login(request, httpRequest.getRemoteAddr(),
                httpRequest.getHeader(HttpHeaders.USER_AGENT)), "Đăng nhập thành công");
    }

    /**
     * Hủy phiên của token đang dùng (docs/adr/0021). Mọi role đã đăng nhập; miễn chặn BR-TK-17. Body (FE cũ gửi
     * {@code refreshToken}) không được đọc. 204 không có body (docs/api/00-method.md §3.4).
     */
    @Operation(operationId = "logout", tags = "Auth",
            summary = "Đăng xuất",
            description = "UC03 · BR-TK-11, BR-TN-06. Actor: A02–A08. Quyền: Người đang đăng nhập. Chuyển "
                + "trạng thái: —. Hủy phiên hiện tại; nhân viên chuyển offline ngay (ADR-0021). Body không được đọc. "
                + "Gọi lại sau khi thành công → 401. Request cùng token đang chạy vẫn hoàn tất (§3.1 của `00-method`). "
                + "Không ghi audit (ADR-0019).")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        logouts.logout();
    }

    /**
     * UC04 — quên mật khẩu (docs/adr/0023). 202 cùng một body ở mọi trường hợp, kể cả email không có tài khoản
     * (BR-TK-10; docs/api/00-method.md §3.4). Gửi lại mã = gọi lại endpoint này.
     */
    @Operation(operationId = "forgotPassword", tags = "Auth",
            summary = "Quên mật khẩu — gửi OTP",
            description = "UC04 · BR-TK-04, 07, 10, 12. Actor: A02–A08. Quyền: Public. Chuyển trạng thái: "
                + "—. Luôn trả 202 cùng nội dung (BR-TK-10), kể cả email không tồn tại, tài khoản không đủ điều kiện "
                + "BR-TK-12 (`PENDING`, bị khóa, `DISABLED` — không gửi mã), đã chạm quota BR-TK-07 (không gửi mã, "
                + "`resendAvailableAt` vẫn = now + 60 giây [CFG]). Tài khoản đang khóa tạm BR-TK-09 vẫn nhận mã. Gửi "
                + "lại = gọi lại endpoint này (A7). ADR-0023.")
    @ApiErrorDoc(status = 400, description = "`VALIDATION_FAILED` email trống / sai định dạng / quá 255 "
            + "ký tự")
    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<OtpSentResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return ApiResponse.accepted(passwordResets.requestPasswordReset(request),
                "Nếu email đã đăng ký, mã OTP đã được gửi tới hộp thư");
    }

    /** UC04 — đặt lại mật khẩu bằng OTP (docs/adr/0023). 204 không có body; mọi phiên cũ bị hủy (BR-TK-13). */
    @Operation(operationId = "resetPassword", tags = "Auth",
            summary = "Đặt lại mật khẩu bằng OTP",
            description = "UC04 · BR-TK-03, 05, 06, 13. Actor: A02–A08. Quyền: Public. Chuyển trạng thái: "
                + "—. Thứ tự kiểm: hình thức → chính sách mật khẩu BR-TK-03 → mã BR-TK-05/06 → trùng mật khẩu hiện tại "
                + "BR-TK-03. Thành công: hủy mọi phiên, gỡ khóa tạm, gỡ bắt đổi mật khẩu lần đầu BR-TK-17, chuyển "
                + "offline (BR-TN-06), gửi email PASSWORD_CHANGED (BR-TK-13); không audit. ADR-0023.")
    @ApiErrorDoc(status = 400, description = "`BR-TK-05` mã sai / hết hạn · `BR-TK-06` sai quá 5 lần, mã "
            + "bị hủy — cũng là lỗi cho email không có tài khoản, tài khoản không đủ điều kiện BR-TK-12 hoặc chưa "
            + "có mã (BR-TK-10) · `BR-TK-03` mật khẩu mới không hợp lệ (kiểm trước mã, không tiêu lượt nhập) hoặc "
            + "trùng mật khẩu hiện tại (chỉ kiểm khi mã đúng; mã vẫn dùng lại được)")
    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResets.resetPassword(request);
    }
}
