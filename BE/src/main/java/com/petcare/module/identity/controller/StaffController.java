package com.petcare.module.identity.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.ChangeRoleRequest;
import com.petcare.module.identity.dto.CreateStaffRequest;
import com.petcare.module.identity.dto.EmailChangeRequest;
import com.petcare.module.identity.dto.OtpCodeRequest;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.dto.ReactivateStaffRequest;
import com.petcare.module.identity.dto.StaffResponse;
import com.petcare.module.identity.dto.TransferStaffRequest;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.service.StaffAdminService;
import com.petcare.module.identity.service.StaffQueryService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

/**
 * {@code /api/staff/**} của identity-v1 — quản lý tài khoản nhân viên (UC08; docs/adr/0029, 0030, 0031, 0033, 0034).
 * Chỉ ADMIN,
 * SUPER_MANAGER, BRANCH_MANAGER (role khác → 403 {@code ACCESS_DENIED}, không audit); đổi chức vụ và điều chuyển chỉ
 * SUPER_MANAGER (BR-QT-05, 06). Phạm vi chi tiết và phân cấp kiểm ở service. Bị chặn khi người gọi còn phải đổi mật
 * khẩu lần đầu (BR-TK-17, {@code MustChangePasswordInterceptor}). Hiện có: danh sách (#15), tạo (#16), chi tiết (#17),
 * đổi chức vụ (#18), điều chuyển (#19), vô hiệu hóa (#20), kích hoạt lại (#21), gửi lại mật khẩu ban đầu (#22), sửa
 * email (#23 gửi OTP, #24 xác nhận).
 */
@RestController
@RequestMapping("/api/staff")
public class StaffController {

    static final String STAFF_ADMINS = "hasAnyRole('ADMIN', 'SUPER_MANAGER', 'BRANCH_MANAGER')";
    static final String SUPER_MANAGER_ONLY = "hasRole('SUPER_MANAGER')";

    private final StaffQueryService staffQueries;
    private final StaffAdminService staffAdmin;

    public StaffController(StaffQueryService staffQueries, StaffAdminService staffAdmin) {
        this.staffQueries = staffQueries;
        this.staffAdmin = staffAdmin;
    }

    /** identity-v1 #15. */
    @Operation(operationId = "listStaff", tags = "Staff",
            summary = "Danh sách nhân viên",
            description = "UC08 · BR-QT-01, 07. Actor: A03, A04, A05. Quyền: ADMIN: SUPER_MANAGER · "
                + "SUPER_MANAGER: A05–A08 · BRANCH_MANAGER: A06–A08 chi nhánh mình. Chuyển trạng thái: —. Lọc `role` "
                + "ngoài phạm vi xem → trang rỗng; ADMIN gửi `branchId` → rỗng (SUPER_MANAGER không thuộc chi nhánh). "
                + "Không gửi `status` = ACTIVE và DISABLED. `q` không phân biệt hoa thường. ADR-0030.",
            parameters = {
                    @Parameter(name = "branchId", in = ParameterIn.QUERY, example = "1"),
                    @Parameter(name = "role", in = ParameterIn.QUERY, example = "VET"),
                    @Parameter(name = "status", in = ParameterIn.QUERY, example = "ACTIVE"),
                    @Parameter(name = "q", in = ParameterIn.QUERY, example = "nguyen",
                            description = "Tìm theo họ tên, email"),
                    @Parameter(name = "page", in = ParameterIn.QUERY, example = "0",
                            description = "Số trang, bắt đầu từ 0"),
                    @Parameter(name = "size", in = ParameterIn.QUERY, example = "20")})
    @ApiErrorDoc(status = 400, description = "tham số sai kiểu · `branchId` ≤ 0")
    @ApiErrorDoc(status = 403, description = "BRANCH_MANAGER gửi `branchId` của chi nhánh khác (không ghi "
            + "audit)")
    @GetMapping
    @PreAuthorize(STAFF_ADMINS)
    public ApiResponse<PageResponse<StaffResponse>> list(
            @RequestParam(required = false) @Positive(message = "branchId phải là số dương") Long branchId,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) AccountStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(staffQueries.listStaff(branchId, role, status, q, page, size));
    }

    /** identity-v1 #16 (Tài khoản#4). Mật khẩu tạm gửi qua email; response không có mật khẩu (BR-QT-02). */
    @Operation(operationId = "createStaff", tags = "Staff",
            summary = "Tạo tài khoản nhân viên",
            description = "UC08 · BR-QT-01, 02, 03, BR-TK-01, 17. Actor: A03, A04, A05. Quyền: Theo "
                + "BR-QT-01. Chuyển trạng thái: — → ACTIVE (Tài khoản#4). Sinh mật khẩu ngẫu nhiên gửi "
                + "STAFF_TEMP_PASSWORD; người tạo không thấy mật khẩu; mustChangePassword = true. BRANCH_MANAGER không "
                + "gửi `branchId` = chi nhánh của mình. Sau lỗi mạng, đọc lại `GET /staff?q=<email>` để biết đã tạo "
                + "chưa (gửi lại nhận `BR-TK-01`). ADR-0030.")
    @ApiErrorDoc(status = 400, description = "`BR-TK-01` email đã dùng · `BR-QT-03` thiếu chi nhánh, chi "
            + "nhánh không tồn tại, hoặc SUPER_MANAGER kèm `branchId`")
    @ApiErrorDoc(status = 403, description = "`BR-QT-01` chức vụ / chi nhánh ngoài quyền (ghi audit)")
    @ApiErrorDoc(status = 409, description = "hai request cùng email chạy song song")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(STAFF_ADMINS)
    public ApiResponse<StaffResponse> create(@Valid @RequestBody CreateStaffRequest request) {
        return ApiResponse.created(staffAdmin.createStaff(request), "Đã tạo tài khoản nhân viên");
    }

    /** identity-v1 #17. */
    @Operation(operationId = "getStaff", tags = "Staff",
            summary = "Chi tiết nhân viên",
            description = "UC08 · BR-QT-01, 07. Actor: A03, A04, A05. Quyền: Trong phạm vi như listStaff. "
                + "Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "id", in = ParameterIn.PATH, example = "42",
                            description = "accountId của nhân viên")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403, description = "ngoài phạm vi xem (không ghi audit)")
    @ApiErrorDoc(status = 404, description = "không có, hoặc là tài khoản khách (cùng message)")
    @GetMapping("/{id}")
    @PreAuthorize(STAFF_ADMINS)
    public ApiResponse<StaffResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(staffQueries.getStaff(id));
    }

    /** identity-v1 #18 (BR-QT-04, 05). Chức vụ mới trùng chức vụ hiện tại → 200 không đổi gì (docs/adr/0031). */
    @Operation(operationId = "changeStaffRole", tags = "Staff",
            summary = "Đổi chức vụ",
            description = "UC08 · BR-QT-04, 05. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng thái: "
                + "—. Trùng chức vụ hiện tại → 200 không đổi gì. Được cả khi nhân viên DISABLED / bị khóa. VET đổi "
                + "sang chức vụ khác thì specialty, bio bị xóa. Không hủy phiên: request kế tiếp của nhân viên dùng "
                + "chức vụ mới. ADR-0031.",
            parameters = {
                    @Parameter(name = "id", in = ParameterIn.PATH, example = "42",
                            description = "accountId của nhân viên")})
    @ApiErrorDoc(status = 400, description = "`BR-QT-04` là BRANCH_MANAGER ACTIVE cuối cùng của chi nhánh "
            + "ACTIVE")
    @ApiErrorDoc(status = 403, description = "người bị tác động ngoài A05–A08 hoặc chính mình (ghi audit)")
    @ApiErrorDoc(status = 404, description = "không có, hoặc là tài khoản khách")
    @ApiErrorDoc(status = 409, description = "chi nhánh của nhân viên vừa bị đổi bởi thao tác khác")
    @PostMapping("/{id}/change-role")
    @PreAuthorize(SUPER_MANAGER_ONLY)
    public ApiResponse<StaffResponse> changeRole(@PathVariable Long id, @Valid @RequestBody ChangeRoleRequest request) {
        return ApiResponse.ok(staffAdmin.changeStaffRole(id, request), "Đã đổi chức vụ");
    }

    /** identity-v1 #19 (BR-QT-03, 04, 06, 08). Đã ở chi nhánh đích → 200 không đổi gì (docs/adr/0031). */
    @Operation(operationId = "transferStaff", tags = "Staff",
            summary = "Điều chuyển chi nhánh",
            description = "UC08 · BR-QT-03, 04, 06, 08. Actor: A04. Quyền: Chỉ SUPER_MANAGER. Chuyển trạng "
                + "thái: —. Đã ở chi nhánh đích → 200 không đổi gì. Được cả khi nhân viên DISABLED / bị khóa. Không "
                + "hủy phiên: request kế tiếp của nhân viên dùng chi nhánh mới. ADR-0031, ADR-0032.",
            parameters = {
                    @Parameter(name = "id", in = ParameterIn.PATH, example = "42",
                            description = "accountId của nhân viên")})
    @ApiErrorDoc(status = 400, description = "`BR-QT-08` còn Visit được gán chưa xong / ca thu ngân OPEN "
            + "(message liệt kê) · `BR-QT-04` BRANCH_MANAGER cuối cùng · `BR-QT-03` chi nhánh không hợp lệ · "
            + "`branchId` số thực → `MALFORMED_REQUEST`")
    @ApiErrorDoc(status = 403, description = "người bị tác động ngoài A05–A08 hoặc chính mình (ghi audit)")
    @ApiErrorDoc(status = 404, description = "không có, hoặc là tài khoản khách")
    @ApiErrorDoc(status = 409, description = "chi nhánh của nhân viên vừa bị đổi bởi thao tác khác")
    @PostMapping("/{id}/transfer")
    @PreAuthorize(SUPER_MANAGER_ONLY)
    public ApiResponse<StaffResponse> transfer(@PathVariable Long id,
            @Valid @RequestBody TransferStaffRequest request) {
        return ApiResponse.ok(staffAdmin.transferStaff(id, request), "Đã điều chuyển nhân viên");
    }

    /**
     * identity-v1 #20 (Tài khoản#5; BR-QT-04, 07, 08, 09, 12, BR-TK-11). Không body. Hủy mọi phiên của nhân viên;
     * đã {@code DISABLED} → 409 (docs/adr/0033).
     */
    @Operation(operationId = "disableStaff", tags = "Staff",
            summary = "Vô hiệu hóa nhân viên",
            description = "UC08 · BR-QT-04, 07, 08, 09, 12, BR-TK-11. Actor: A03, A04, A05. Quyền: Theo "
                + "BR-QT-07; không tự vô hiệu hóa mình. Chuyển trạng thái: ACTIVE → DISABLED (Tài khoản#5). Không "
                + "body. Hủy mọi phiên, nhân viên chuyển offline ngay (BR-QT-09, BR-TK-11, BR-TN-06); không gửi email; "
                + "dữ liệu lịch sử giữ nguyên. SUPER_MANAGER không kiểm BR-QT-08. Tạm thời A05–A08 trả 500 tới khi "
                + "visit / sales có bản thật (D016, 21/10). Bấm lại sau lỗi mạng nhận 409 → đọc lại `GET /staff/{id}`. "
                + "ADR-0033.",
            parameters = {
                    @Parameter(name = "id", in = ParameterIn.PATH, example = "42",
                            description = "accountId của nhân viên")})
    @ApiErrorDoc(status = 400, description = "`BR-QT-12` đang bị khóa, cần mở khóa trước · `BR-QT-04` "
            + "BRANCH_MANAGER ACTIVE cuối cùng của chi nhánh ACTIVE · `BR-QT-08` còn Visit được gán chưa xong / ca "
            + "thu ngân OPEN (message liệt kê) · `id` sai kiểu")
    @ApiErrorDoc(status = 403, description = "ngoài phạm vi BR-QT-07 hoặc chính mình (ghi audit, message "
            + "chung)")
    @ApiErrorDoc(status = 404, description = "không có, hoặc là tài khoản khách")
    @ApiErrorDoc(status = 409, description = "đã DISABLED (`INVALID_STATE_TRANSITION`) · chi nhánh của "
            + "nhân viên vừa bị đổi bởi thao tác khác (`CONCURRENCY_CONFLICT`)")
    @PostMapping("/{id}/disable")
    @PreAuthorize(STAFF_ADMINS)
    public ApiResponse<StaffResponse> disable(@PathVariable Long id) {
        return ApiResponse.ok(staffAdmin.disableStaff(id), "Đã vô hiệu hóa nhân viên");
    }

    /**
     * identity-v1 #21 (Tài khoản#6; BR-QT-02, 03, 10, 12). Body không bắt buộc: thiếu = giữ chi nhánh hiện tại. Mật khẩu
     * tạm mới gửi qua email, response không có mật khẩu; đang {@code ACTIVE} → 409 (docs/adr/0033).
     */
    @Operation(operationId = "reactivateStaff", tags = "Staff",
            summary = "Kích hoạt lại nhân viên",
            description = "UC08 · BR-QT-02, 03, 10, 12. Actor: A03, A04, A05. Quyền: Cùng phạm vi với vô "
                + "hiệu hóa. Chuyển trạng thái: DISABLED → ACTIVE (Tài khoản#6). Body không bắt buộc: thiếu / `{}` / "
                + "`branchId` null = giữ chi nhánh hiện tại. Sinh mật khẩu tạm mới gửi STAFF_TEMP_PASSWORD (response "
                + "không có mật khẩu), mustChangePassword = true, xóa bộ đếm đăng nhập sai và khóa tạm, hủy mọi phiên. "
                + "Bấm lại sau lỗi mạng nhận 409 (không gửi thêm mật khẩu) → đọc lại `GET /staff/{id}`. ADR-0033, "
                + "ADR-0032.",
            parameters = {
                    @Parameter(name = "id", in = ParameterIn.PATH, example = "42",
                            description = "accountId của nhân viên")})
    @ApiErrorDoc(status = 400, description = "`BR-QT-10` đang bị khóa, cần mở khóa trước · `BR-QT-03` chi "
            + "nhánh không tồn tại, hoặc SUPER_MANAGER kèm `branchId` · `branchId` ≤ 0 (`VALIDATION_FAILED`) / số "
            + "thực (`MALFORMED_REQUEST`)")
    @ApiErrorDoc(status = 403, description = "ngoài phạm vi BR-QT-10 (như vô hiệu hóa), chính mình, hoặc "
            + "BRANCH_MANAGER gửi chi nhánh khác chi nhánh mình (ghi audit, message chung)")
    @ApiErrorDoc(status = 404, description = "không có, hoặc là tài khoản khách")
    @ApiErrorDoc(status = 409, description = "đang ACTIVE (`INVALID_STATE_TRANSITION`)")
    @PostMapping("/{id}/reactivate")
    @PreAuthorize(STAFF_ADMINS)
    public ApiResponse<StaffResponse> reactivate(@PathVariable Long id,
            @Valid @RequestBody(required = false) ReactivateStaffRequest request) {
        return ApiResponse.ok(staffAdmin.reactivateStaff(id, request), "Đã kích hoạt lại nhân viên");
    }

    /**
     * identity-v1 #23 (BR-TK-01, 04, 07, 16, BR-QT-01). Gửi OTP tới email mới; mọi trạng thái của nhân viên, không audit
     * (docs/adr/0034).
     */
    @Operation(operationId = "startStaffEmailChange", tags = "Staff",
            summary = "Sửa email nhân viên — gửi OTP tới email mới",
            description = "UC08 · BR-TK-04, 07, 16, BR-QT-01. Actor: A03, A04, A05. Quyền: Cấp trên theo "
                + "BR-QT-01. Chuyển trạng thái: —. Email mới được chuẩn hóa chữ thường. Mã gửi tới email mới "
                + "(BR-TK-04), `maskedEmail` = null. Được với mọi trạng thái (DISABLED, đang bị khóa). Mỗi nhân viên "
                + "tối đa một mã đổi email còn hiệu lực: gửi mã mới (kể cả tới email khác) hủy mã cũ của nhân viên đó; "
                + "tài khoản khác xin mã tới cùng email không hủy được mã này. Không ghi audit ở bước này. Gửi lại = "
                + "gọi lại endpoint. ADR-0034.",
            parameters = {
                    @Parameter(name = "id", in = ParameterIn.PATH, example = "42",
                            description = "accountId của nhân viên")})
    @ApiErrorDoc(status = 400, description = "`BR-TK-01` email mới đã dùng (kể cả tài khoản PENDING) hoặc "
            + "trùng email hiện tại · `BR-TK-07` gửi lại trong 60 giây / quá 5 mã/giờ [CFG] tới email đó · "
            + "`VALIDATION_FAILED` `newEmail` trống / sai định dạng / quá 255 ký tự")
    @ApiErrorDoc(status = 403, description = "ngoài phân cấp BR-QT-01 hoặc chính mình (ghi audit, message "
            + "chung)")
    @ApiErrorDoc(status = 404, description = "không có, hoặc là tài khoản khách (cùng message)")
    @PostMapping("/{id}/email-change")
    @PreAuthorize(STAFF_ADMINS)
    public ApiResponse<OtpSentResponse> startEmailChange(@PathVariable Long id,
            @Valid @RequestBody EmailChangeRequest request) {
        return ApiResponse.ok(staffAdmin.startEmailChange(id, request), "Đã gửi mã xác thực tới email mới");
    }

    /**
     * identity-v1 #24 (BR-TK-01, 05, 06, 16). Đổi email, thông báo tới email cũ và mới, ghi audit; không hủy phiên
     * (docs/adr/0034).
     */
    @Operation(operationId = "confirmStaffEmailChange", tags = "Staff",
            summary = "Sửa email nhân viên — xác nhận OTP",
            description = "UC08 · BR-TK-01, 05, 06, 16. Actor: A03, A04, A05. Quyền: Cấp trên theo "
                + "BR-QT-01. Chuyển trạng thái: —. Email mới lấy từ mã đang mở của nhân viên. Đổi email, gửi "
                + "EMAIL_CHANGED_NOTICE tới email cũ và mới, ghi audit `STAFF_EMAIL_CHANGED` (before/after email, "
                + "`verificationMethod = EMAIL_CODE`). Trạng thái, khóa, mật khẩu không đổi; không hủy phiên. Bấm lại "
                + "sau lỗi mạng nhận `BR-TK-05` → đọc lại `GET /staff/{id}`. Nhân viên chưa đổi mật khẩu lần đầu "
                + "(`mustChangePassword`) thì gọi tiếp `POST /staff/{id}/resend-initial-password` để mật khẩu tạm đi "
                + "tới email mới. ADR-0034.",
            parameters = {
                    @Parameter(name = "id", in = ParameterIn.PATH, example = "42",
                            description = "accountId của nhân viên")})
    @ApiErrorDoc(status = 400, description = "`BR-TK-05` mã sai / hết hạn · `BR-TK-06` sai quá 5 lần, mã "
            + "bị hủy (sai mã vẫn được tính) · không còn mã đang mở → `BR-TK-05` · `BR-TK-01` email mới vừa được "
            + "tài khoản khác dùng (mã vẫn dùng lại được) · `VALIDATION_FAILED` `code` trống / không phải 1–8 chữ "
            + "số")
    @ApiErrorDoc(status = 403, description = "ngoài phân cấp BR-QT-01 hoặc chính mình — kiểm trước khi "
            + "tra mã và lại dưới khóa (ghi audit, message chung)")
    @ApiErrorDoc(status = 404, description = "không có, hoặc là tài khoản khách (cùng message)")
    @ApiErrorDoc(status = 409, description = "`CONCURRENCY_CONFLICT` hai lần xác nhận khác nhân viên cùng "
            + "một email mới chạy song song")
    @PostMapping("/{id}/email-change/confirm")
    @PreAuthorize(STAFF_ADMINS)
    public ApiResponse<StaffResponse> confirmEmailChange(@PathVariable Long id,
            @Valid @RequestBody OtpCodeRequest request) {
        return ApiResponse.ok(staffAdmin.confirmEmailChange(id, request), "Đã đổi email nhân viên");
    }

    /** identity-v1 #22. 204 không body (docs/api/00-method.md §3.4). */
    @Operation(operationId = "resendInitialPassword", tags = "Staff",
            summary = "Gửi lại mật khẩu ban đầu",
            description = "UC08 · BR-QT-02. Actor: A03, A04, A05. Quyền: Người có quyền tạo tài khoản đó "
                + "(BR-QT-01). Chuyển trạng thái: —. Sinh mật khẩu mới, mã cũ hết hiệu lực. Chỉ khi ACTIVE và chưa đổi "
                + "mật khẩu lần đầu (đang bị khóa vẫn được). Hủy mọi phiên, xóa bộ đếm đăng nhập sai và khóa tạm, nhân "
                + "viên chuyển offline; ghi audit. ADR-0030.",
            parameters = {
                    @Parameter(name = "id", in = ParameterIn.PATH, example = "42",
                            description = "accountId của nhân viên")})
    @ApiErrorDoc(status = 403, description = "`BR-QT-01` ngoài phân cấp hoặc chính mình (ghi audit)")
    @ApiErrorDoc(status = 404, description = "không có, hoặc là tài khoản khách")
    @ApiErrorDoc(status = 409, description = "Nhân viên đã đổi mật khẩu lần đầu, hoặc tài khoản đang "
            + "DISABLED")
    @PostMapping("/{id}/resend-initial-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(STAFF_ADMINS)
    public void resendInitialPassword(@PathVariable Long id) {
        staffAdmin.resendInitialPassword(id);
    }
}
