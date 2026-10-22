package com.petcare.module.identity.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.identity.dto.ChangePasswordRequest;
import com.petcare.module.identity.dto.LinkCandidateResponse;
import com.petcare.module.identity.dto.LinkConfirmRequest;
import com.petcare.module.identity.dto.LinkOtpRequest;
import com.petcare.module.identity.dto.LinkResult;
import com.petcare.module.identity.dto.MeResponse;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.dto.StaffProfileResponse;
import com.petcare.module.identity.dto.UpdateStaffProfileRequest;
import com.petcare.module.identity.service.ChangePasswordService;
import com.petcare.module.identity.service.LinkProfileService;
import com.petcare.module.identity.service.MeService;
import com.petcare.module.identity.service.StaffProfileService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

/**
 * {@code /api/me/**} của identity-v1 — thao tác của chính người đang đăng nhập (cần token). Hiện có: thông tin người
 * đang đăng nhập (UC06), đổi mật khẩu (UC05) — mọi role; sửa hồ sơ nhân viên (UC06) — chỉ nhân viên A03–A08; liên
 * kết hồ sơ khách có sẵn (UC07, docs/adr/0027) — chỉ khách.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final MeService me;
    private final ChangePasswordService changePasswords;
    private final StaffProfileService staffProfiles;
    private final LinkProfileService linkProfiles;

    public MeController(MeService me, ChangePasswordService changePasswords, StaffProfileService staffProfiles,
            LinkProfileService linkProfiles) {
        this.me = me;
        this.changePasswords = changePasswords;
        this.staffProfiles = staffProfiles;
        this.linkProfiles = linkProfiles;
    }

    /**
     * identity-v1 #8. Được miễn chặn BR-TK-17 ({@code MustChangePasswordInterceptor}) để FE đọc
     * {@code account.mustChangePassword}. Không nhận tham số: luôn trả về chủ token.
     */
    @Operation(operationId = "getMe", tags = "Me",
            summary = "Thông tin người đang đăng nhập",
            description = "UC06 · BR-TK-17, 19. Actor: A02–A08. Quyền: Người đang đăng nhập. Chuyển trạng "
                + "thái: —.")
    @GetMapping
    public ApiResponse<MeResponse> getMe() {
        return ApiResponse.ok(me.getMe());
    }

    /**
     * UC05 (docs/adr/0022). Được miễn chặn BR-TK-17 ({@code MustChangePasswordInterceptor}); thành công thì gỡ cờ bắt
     * đổi mật khẩu và hủy mọi phiên khác, phiên đang dùng giữ nguyên. 204 không có body (docs/api/00-method.md §3.4).
     */
    @Operation(operationId = "changePassword", tags = "Me",
            summary = "Đổi mật khẩu",
            description = "UC05 · BR-TK-03, 09, 14, 17. Actor: A02–A08. Quyền: Người đang đăng nhập. "
                + "Chuyển trạng thái: —. Đăng xuất mọi phiên khác, phiên đang dùng giữ nguyên; gỡ "
                + "`mustChangePassword`; xóa bộ đếm đăng nhập sai. Không gửi email. Request sai hình thức (400 "
                + "`VALIDATION_FAILED` / `MALFORMED_REQUEST`) không tính là một lần nhập sai (ADR-0022).")
    @ApiErrorDoc(status = 400, description = "`BR-TK-14` mật khẩu hiện tại sai (tính vào bộ đếm BR-TK-09; "
            + "lần chạm ngưỡng khóa tạm đăng nhập, message kèm giờ mở khóa) · `BR-TK-03` mật khẩu mới không hợp lệ "
            + "hoặc trùng mật khẩu cũ (chỉ kiểm khi mật khẩu hiện tại đúng) · `BR-TK-09` đang khóa tạm, message có "
            + "giờ thử lại · `BR-TK-11` tài khoản vừa bị khóa / vô hiệu hóa")
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        changePasswords.changePassword(request);
    }

    /**
     * identity-v1 #10 (UC06, docs/adr/0026). Khách sửa hồ sơ ở module customer nên bị 403 ở đây. Bị chặn khi còn phải
     * đổi mật khẩu lần đầu (BR-TK-17, không có trong danh sách miễn của {@code MustChangePasswordInterceptor}).
     */
    @Operation(operationId = "updateMyStaffProfile", tags = "Me",
            summary = "Sửa hồ sơ nhân viên của tôi",
            description = "UC06 · BR-TK-15, 20. Actor: A03–A08. Quyền: Nhân viên đang đăng nhập; "
                + "specialty, bio chỉ VET. Chuyển trạng thái: —. null = giữ nguyên; chuỗi rỗng = xóa (`avatarUrl`, "
                + "`specialty`, `bio`). Thứ tự kiểm: hình thức → BR-TK-15 → BR-TK-11 → 403 → BR-TK-20 (chỉ khi gửi "
                + "`bio`). Field khác (`role`, `branchId`…) bị bỏ qua. Không audit (ADR-0026).")
    @ApiErrorDoc(status = 400, description = "`BR-TK-15` body có `email` (email không tự sửa được) · "
            + "`BR-TK-11` tài khoản vừa bị khóa / vô hiệu hóa · `BR-TK-20` mô tả ngắn vượt 500 ký tự [CFG] · "
            + "`BR-TK-17` chưa đổi mật khẩu lần đầu · `VALIDATION_FAILED` họ tên / SĐT rỗng, SĐT khác 10 số, "
            + "`avatarUrl` không phải `https://`")
    @ApiErrorDoc(status = 403, description = "`ACCESS_DENIED` tài khoản khách · "
            + "`ACCESS_DENIED_SCOPE_MISMATCH` không phải VET mà gửi `specialty` / `bio`")
    @PatchMapping("/staff-profile")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_MANAGER', 'BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER')")
    public ApiResponse<StaffProfileResponse> updateStaffProfile(
            @Valid @RequestBody UpdateStaffProfileRequest request) {
        return ApiResponse.ok(staffProfiles.updateStaffProfile(request), "Đã cập nhật hồ sơ");
    }

    /**
     * identity-v1 #11 (UC07, BR-TK-19). {@code phone} bỏ trống = SĐT đã khai trên hồ sơ online. Constraint trên
     * {@code @RequestParam} được Spring kiểm (không {@code @Validated} ở lớp) → {@code HandlerMethodValidationException}
     * → 400 {@code VALIDATION_FAILED}.
     */
    @Operation(operationId = "listLinkCandidates", tags = "Profile link",
            summary = "Danh sách hồ sơ tại quầy có thể liên kết",
            description = "UC07 · BR-TK-19, BR-KH-10. Actor: A02. Quyền: Khách đang đăng nhập. Chuyển "
                + "trạng thái: —. Chỉ trả hồ sơ COUNTER chưa liên kết, họ tên đã che; không có thì mảng rỗng "
                + "(ADR-0027).",
            parameters = {
                    @Parameter(name = "phone", in = ParameterIn.QUERY, example = "0901234567",
                            description = "SĐT của hồ sơ tại quầy (UC07 khi không có cờ); bỏ trống = SĐT đã khai "
                                    + "trên hồ sơ online")})
    @ApiErrorDoc(status = 400, description = "`BR-TK-19` hồ sơ online đã phát sinh dữ liệu, hoặc tài "
            + "khoản đã liên kết hồ sơ tại quầy · `VALIDATION_FAILED` `phone` sai định dạng")
    @ApiErrorDoc(status = 403)
    @GetMapping("/link-candidates")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<List<LinkCandidateResponse>> listLinkCandidates(
            @RequestParam(required = false)
            @Pattern(regexp = "^0[0-9]{9,10}$", message = "Số điện thoại phải gồm 10–11 chữ số, bắt đầu bằng 0")
            String phone) {
        return ApiResponse.ok(linkProfiles.listCandidates(phone));
    }

    /** identity-v1 #12 (UC07, BR-TK-04, 07, 19). Mã gửi tới email hồ sơ tại quầy; {@code maskedEmail} cho biết nơi nhận. */
    @Operation(operationId = "sendLinkOtp", tags = "Profile link",
            summary = "Gửi OTP tới email hồ sơ tại quầy",
            description = "UC07 · BR-TK-04, 07, 19. Actor: A02. Quyền: Khách đang đăng nhập. Chuyển trạng "
                + "thái: —. Thứ tự kiểm: BR-TK-11 → 404 → BR-TK-19 → BR-TK-07. Mã gửi tới email ghi trong hồ sơ tại "
                + "quầy (BR-TK-04); gọi lại = gửi lại (BR-TK-07, mã mới hủy mã liên kết cũ của tài khoản). "
                + "`maskedEmail` là email đó đã che (ADR-0027).")
    @ApiErrorDoc(status = 400, description = "`BR-TK-19` hồ sơ không có email (ra quầy), hồ sơ online đã "
            + "có dữ liệu hoặc tài khoản đã liên kết · `BR-TK-11` tài khoản vừa bị khóa · `BR-TK-07` gửi quá nhanh "
            + "/ quá 5 mã/giờ")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404, description = "Hồ sơ không tồn tại hoặc không phải ứng viên của SĐT (một "
            + "thông báo chung)")
    @PostMapping("/link/otp")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<OtpSentResponse> sendLinkOtp(@Valid @RequestBody LinkOtpRequest request) {
        return ApiResponse.ok(linkProfiles.sendLinkOtp(request), "Đã gửi mã xác thực tới email của hồ sơ");
    }

    /** identity-v1 #13 (UC07, BR-TK-05, 06, 19). */
    @Operation(operationId = "confirmLink", tags = "Profile link",
            summary = "Xác nhận liên kết",
            description = "UC07 · BR-TK-05, 06, 19. Actor: A02. Quyền: Khách đang đăng nhập. Chuyển trạng "
                + "thái: —. Gắn tài khoản vào hồ sơ tại quầy, xóa hồ sơ online cùng sổ địa chỉ của nó, email hồ sơ "
                + "theo email tài khoản, SĐT giữ nguyên, ghi audit `CUSTOMER_PROFILE_LINKED` (ADR-0027). Sau lỗi mạng, "
                + "đọc lại `GET /me` để biết đã liên kết chưa.")
    @ApiErrorDoc(status = 400, description = "`BR-TK-05` mã sai / hết hạn · `BR-TK-06` sai quá 5 lần, mã "
            + "bị hủy (sai mã vẫn được tính) · `BR-TK-19` hồ sơ online đã phát sinh dữ liệu / tài khoản đã liên "
            + "kết / hồ sơ tại quầy vừa được liên kết hoặc đổi email — mã vẫn dùng lại được · `BR-TK-11` tài khoản "
            + "vừa bị khóa")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404, description = "Hồ sơ không tồn tại")
    @PostMapping("/link/confirm")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<LinkResult> confirmLink(@Valid @RequestBody LinkConfirmRequest request) {
        return ApiResponse.ok(linkProfiles.confirmLink(request), "Liên kết hồ sơ thành công");
    }

    /** identity-v1 #14 (UC07, BR-TK-19 (b) "Không phải tôi"). 204 không body; body gửi kèm bị bỏ qua. */
    @Operation(operationId = "declineLink", tags = "Profile link",
            summary = "Chọn \"Không phải tôi\"",
            description = "UC07 · BR-TK-19. Actor: A02. Quyền: Khách đang đăng nhập. Chuyển trạng thái: —. "
                + "Không audit (ADR-0027).")
    @ApiErrorDoc(status = 400, description = "`BR-TK-11` tài khoản vừa bị khóa")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 409, description = "`INVALID_STATE_TRANSITION` hồ sơ không còn cờ chờ quyết định")
    @PostMapping("/link/decline")
    @PreAuthorize("hasRole('CUSTOMER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void declineLink() {
        linkProfiles.declineLink();
    }
}
