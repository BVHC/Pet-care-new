package com.petcare.module.identity.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.identity.dto.CounterLinkRequest;
import com.petcare.module.identity.dto.CustomerEmailChangeRequest;
import com.petcare.module.identity.dto.LinkResult;
import com.petcare.module.identity.dto.OtpCodeRequest;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.service.CounterLinkService;
import com.petcare.module.identity.service.CustomerAccountService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

import jakarta.validation.Valid;

/**
 * {@code /api/customers/{customerId}/…} của identity-v1 — lễ tân xử lý tài khoản của khách tại quầy (UC22, BR-TK-16),
 * tag "Customer account". Tiền tố {@code /api/customers} dùng chung với {@code customer/controller/CustomerController}
 * (hồ sơ khách, thú cưng): hai controller thuộc hai module nhưng không trùng path nào. Chỉ RECEPTIONIST
 * ({@link #RECEPTIONIST_ONLY}; các role khác kể cả quản lý → 403 {@code ACCESS_DENIED}, không audit): hồ sơ khách dùng
 * chung toàn chuỗi nên không có phạm vi chi nhánh hay phân cấp BR-QT-01. Bị chặn khi người gọi còn phải đổi mật khẩu lần
 * đầu (BR-TK-17, {@code MustChangePasswordInterceptor}). Hiện có: sửa email tài khoản khách (#25 gửi OTP, #26 xác nhận) và
 * liên kết hộ hồ sơ tại quầy với tài khoản online (#27).
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerAccountController {

    static final String RECEPTIONIST_ONLY = "hasRole('RECEPTIONIST')";

    private final CustomerAccountService customerAccounts;
    private final CounterLinkService counterLinks;

    public CustomerAccountController(CustomerAccountService customerAccounts, CounterLinkService counterLinks) {
        this.customerAccounts = customerAccounts;
        this.counterLinks = counterLinks;
    }

    /**
     * identity-v1 #25 (BR-TK-01, 04, 07, 16). Gửi OTP tới email mới của tài khoản gắn với hồ sơ; cả khi tài khoản đang bị
     * khóa, không với tài khoản {@code PENDING}; không audit ở bước này.
     */
    @Operation(operationId = "startCustomerEmailChange", tags = "Customer account",
            summary = "Sửa email tài khoản khách / khôi phục tài khoản — gửi OTP",
            description = "UC22 · BR-TK-01, 04, 07, 16, BR-KH-10. Actor: A06. Quyền: Lễ tân. Chuyển trạng thái: —. "
                + "Email mới được chuẩn hóa chữ thường. Mã gửi tới email mới (BR-TK-04), `maskedEmail` = null. Được cả "
                + "khi tài khoản đang bị khóa (khôi phục tài khoản); tài khoản chưa xác thực email (PENDING) thì "
                + "không. Mỗi tài khoản tối đa một mã đổi email còn hiệu lực: gửi mã mới (kể cả tới email khác) hủy mã "
                + "cũ. Không ghi audit ở bước này. Gửi lại = gọi lại endpoint. ADR-0034.",
            parameters = {
                    @Parameter(name = "customerId", in = ParameterIn.PATH, example = "315")})
    @ApiErrorDoc(status = 400, description = "`VALIDATION_FAILED` hồ sơ chưa gắn tài khoản online, hoặc tài khoản "
            + "chưa xác thực email (PENDING) — message không kèm mã BR · `BR-TK-01` email mới đã dùng (kể cả tài "
            + "khoản PENDING, nhân viên) hoặc trùng email hiện tại · `BR-TK-07` gửi lại trong 60 giây / quá 5 mã/giờ "
            + "[CFG] tới email đó · `VALIDATION_FAILED` `newEmail` trống / sai định dạng / quá 255 ký tự, thiếu "
            + "`verificationMethod` · `MALFORMED_REQUEST` `verificationMethod` ngoài `ID_CARD_IN_PERSON`")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @PostMapping("/{customerId}/account-email-change")
    @PreAuthorize(RECEPTIONIST_ONLY)
    public ApiResponse<OtpSentResponse> startEmailChange(@PathVariable Long customerId,
            @Valid @RequestBody CustomerEmailChangeRequest request) {
        return ApiResponse.ok(customerAccounts.startEmailChange(customerId, request),
                "Đã gửi mã xác thực tới email mới");
    }

    /**
     * identity-v1 #26 (BR-TK-01, 05, 06, 16). Đổi email tài khoản, thông báo tới email cũ và mới, ghi audit
     * {@code CUSTOMER_EMAIL_CHANGED}; không hủy phiên của khách. 204 không body (docs/api/00-method.md §3.4).
     */
    @Operation(operationId = "confirmCustomerEmailChange", tags = "Customer account",
            summary = "Sửa email tài khoản khách — xác nhận OTP",
            description = "UC22 · BR-TK-01, 05, 06, 16. Actor: A06. Quyền: Lễ tân. Chuyển trạng thái: —. Email mới "
                + "lấy từ mã đang mở của tài khoản. Đổi email tài khoản, gửi EMAIL_CHANGED_NOTICE tới email cũ và mới, "
                + "ghi audit `CUSTOMER_EMAIL_CHANGED` (before/after email, `verificationMethod = ID_CARD_IN_PERSON`). "
                + "Trạng thái, khóa, mật khẩu không đổi; không hủy phiên của khách. Bấm lại sau lỗi mạng nhận "
                + "`BR-TK-05`. ADR-0034.",
            parameters = {
                    @Parameter(name = "customerId", in = ParameterIn.PATH, example = "315")})
    @ApiErrorDoc(status = 400, description = "`BR-TK-05` mã sai / hết hạn · `BR-TK-06` sai quá 5 lần, mã bị hủy (sai "
            + "mã vẫn được tính) · không còn mã đang mở → `BR-TK-05` · `BR-TK-01` email mới vừa được tài khoản khác "
            + "dùng (mã vẫn dùng lại được) · `VALIDATION_FAILED` hồ sơ chưa gắn tài khoản online, hoặc tài khoản "
            + "chưa xác thực email (PENDING) — message không kèm mã BR · `VALIDATION_FAILED` `code` trống / không "
            + "phải 1–8 chữ số")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "`CONCURRENCY_CONFLICT` hai lần xác nhận khác tài khoản cùng một email "
            + "mới chạy song song")
    @PostMapping("/{customerId}/account-email-change/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(RECEPTIONIST_ONLY)
    public void confirmEmailChange(@PathVariable Long customerId, @Valid @RequestBody OtpCodeRequest request) {
        customerAccounts.confirmEmailChange(customerId, request);
    }

    /**
     * identity-v1 #27 (BR-TK-11, 16, 19). Lễ tân liên kết hộ tài khoản online với hồ sơ tại quầy sau khi đối chiếu CCCD:
     * không OTP, không thông báo, không hủy phiên; ghi audit {@code CUSTOMER_PROFILE_LINKED}. Cùng điều kiện về hồ sơ
     * online như UC07 (chưa có dữ liệu), thêm: hồ sơ tại quầy phải có email, tài khoản phải {@code ACTIVE} và không bị
     * khóa.
     */
    @Operation(operationId = "linkAccountAtCounter", tags = "Customer account",
            summary = "Liên kết hồ sơ tại quầy với tài khoản",
            description = "UC22 · BR-TK-16, 19. Actor: A06. Quyền: Lễ tân. Chuyển trạng thái: —. Không cần OTP: lễ tân "
                + "đã đối chiếu CCCD với họ tên hồ sơ (không lưu CCCD). `accountEmail` được chuẩn hóa chữ thường. Thứ "
                + "tự kiểm: hình thức → quyền → hồ sơ (404) → hồ sơ đã gắn tài khoản / chưa có email (400) → tài khoản "
                + "(404; đang bị khóa → `BR-TK-11`) → hồ sơ online của tài khoản (400 `BR-TK-19`). Hồ sơ tại quầy phải "
                + "có email (như UC07); email đó, kể cả email tạm lễ tân vừa nhập, bị ghi đè bằng email tài khoản khi "
                + "liên kết. Gắn tài khoản vào hồ sơ tại quầy, xóa hồ sơ online cùng sổ địa chỉ của nó, SĐT hồ sơ giữ "
                + "nguyên. Ghi audit `CUSTOMER_PROFILE_LINKED` (`verificationMethod = ID_CARD_IN_PERSON`); không gửi "
                + "thông báo, không hủy phiên của khách.",
            parameters = {
                    @Parameter(name = "customerId", in = ParameterIn.PATH, example = "315")})
    @ApiErrorDoc(status = 400, description = "`BR-TK-19` hồ sơ đã gắn tài khoản (hồ sơ online hoặc đã liên kết) · hồ sơ "
            + "tại quầy chưa có email — bổ sung email vào hồ sơ (`PATCH /customers/{customerId}`) rồi liên kết lại · "
            + "tài khoản đã liên kết một hồ sơ tại quầy khác · hồ sơ online của tài khoản đã có thú cưng, lịch hẹn, đặt "
            + "chỗ, Order hoặc feedback · hồ sơ tại quầy vừa được liên kết hoặc đổi email trong lúc chờ · `BR-TK-11` "
            + "tài khoản đang bị khóa · `VALIDATION_FAILED` `accountEmail` trống / sai định dạng / quá 255 ký tự, "
            + "thiếu `verificationMethod` · `MALFORMED_REQUEST` `verificationMethod` ngoài `ID_CARD_IN_PERSON`")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404, description = "không có hồ sơ khách · không có tài khoản khách đã kích hoạt với "
            + "`accountEmail` (email lạ, tài khoản nhân viên, tài khoản chưa xác thực email PENDING — một thông báo "
            + "chung)")
    @PostMapping("/{customerId}/link-account")
    @PreAuthorize(RECEPTIONIST_ONLY)
    public ApiResponse<LinkResult> linkAccount(@PathVariable Long customerId,
            @Valid @RequestBody CounterLinkRequest request) {
        return ApiResponse.ok(counterLinks.linkAccount(customerId, request), "Đã liên kết hồ sơ với tài khoản");
    }
}
