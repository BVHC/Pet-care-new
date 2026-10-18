package com.petcare.platform.config;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.model.ErrorResponse;
import com.petcare.platform.security.SecurityConfig;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;

/**
 * Tài liệu OpenAPI ({@code /v3/api-docs}, {@code /swagger-ui.html}) sinh từ annotation trong code (docs/adr/0040,
 * docs/convention/backend/10-swagger-api-docs.md §7). Phần chung đặt ở đây: thông tin API, scheme bearer
 * {@value #BEARER_SCHEME} làm mặc định cho mọi operation, schema {@code ErrorResponse}, các response lỗi dùng chung
 * (text chép từ hợp đồng {@code docs/api/openapi/*.yaml}) và các tag. Path public ({@code security: []}), {@code 401},
 * {@code 500} do {@link PublicPathSecurityCustomizer} và {@link ApiErrorDocCustomizer} thêm.
 */
@Configuration
public class OpenApiConfig {

    /** Tên scheme bearer, trùng hợp đồng {@code docs/api} ({@code bearerAuth}). */
    public static final String BEARER_SCHEME = "bearerAuth";

    static final String ERROR_SCHEMA = "ErrorResponse";
    static final String ERROR_SCHEMA_REF = "#/components/schemas/" + ERROR_SCHEMA;

    static final String BAD_REQUEST = "BadRequest";
    static final String UNAUTHORIZED = "Unauthorized";
    static final String FORBIDDEN = "Forbidden";
    static final String NOT_FOUND = "NotFound";
    static final String INTERNAL_ERROR = "InternalError";

    /** Response lỗi dùng chung theo mã HTTP; {@code @ApiErrorDoc} không có mô tả tham chiếu tới đây. */
    private static final Map<Integer, String> SHARED_RESPONSES = Map.of(
            400, BAD_REQUEST,
            401, UNAUTHORIZED,
            403, FORBIDDEN,
            404, NOT_FOUND);

    /** Text chép nguyên văn từ {@code components/responses} của hợp đồng (giống nhau ở mọi module). */
    private static final Map<String, String> SHARED_DESCRIPTIONS = Map.of(
            BAD_REQUEST, "VALIDATION_FAILED / MALFORMED_REQUEST: dữ liệu không hợp lệ · BUSINESS_RULE_VIOLATION: "
                    + "vi phạm rule, message kết thúc bằng mã rule \"(BR-…)\".",
            UNAUTHORIZED, "UNAUTHENTICATED: chưa đăng nhập hoặc phiên đã hết hạn / bị hủy.",
            FORBIDDEN, "ACCESS_DENIED: sai vai trò · ACCESS_DENIED_SCOPE_MISMATCH: ngoài phạm vi (chi nhánh, "
                    + "chủ sở hữu). Message chung, không lộ cấu trúc phân quyền.",
            NOT_FOUND, "Không tìm thấy (RESOURCE_NOT_FOUND).",
            INTERNAL_ERROR, "INTERNAL_ERROR: lỗi không lường trước. Message chung; tra `traceId` trong log của "
                    + "backend.");

    /** Tag của các endpoint đã có tài liệu, mô tả lấy từ hợp đồng. */
    private static final List<Tag> TAGS = List.of(
            tag("Auth", "Đăng ký, OTP, đăng nhập, mật khẩu (UC01–UC05)"),
            tag("Me", "Thông tin và hồ sơ của người đang đăng nhập (UC05, UC06)"),
            tag("Profile link", "Liên kết tài khoản với hồ sơ khách có sẵn (UC07, BR-TK-19)"),
            tag("My profile", "Hồ sơ khách và sổ địa chỉ của khách đang đăng nhập (UC06)"),
            tag("Notifications", "Thông báo trong ứng dụng và cài đặt (UC88)"),
            tag("Staff", "Quản lý tài khoản nhân viên (UC08)"),
            tag("Accounts", "Khóa / mở khóa mọi tài khoản (UC09)"),
            tag("Configuration", "Tham số [CFG] và mẫu thông báo (UC10)"),
            tag("Audit", "Nhật ký audit (UC11)"),
            tag("Slots", "Khung giờ trống (UC39)"),
            tag("Public", "Thông tin công khai, không cần đăng nhập (UC15)"));

    private static final String DESCRIPTION = """
            API của hệ thống quản lý chuỗi phòng khám thú cưng Pet Care. Hợp đồng thiết kế: `docs/api/*-v1.md`.

            - **Thành công:** `{ data, message, code }` — payload nằm trong `data`; danh sách phân trang là \
            `{ content, page, size, totalElements, totalPages }` trong `data` (`page` bắt đầu từ 0). \
            HTTP 204 không có body.
            - **Lỗi:** `ErrorResponse` đủ 6 field \
            `{ success: false, errorCode, message, statusCode, timestamp, traceId }`. Vi phạm business rule: \
            `errorCode = BUSINESS_RULE_VIOLATION`, `message` kết thúc bằng mã rule, ví dụ "… (BR-LH-05)". \
            Xung đột trạng thái / đồng thời là 409, không dùng 422.
            - **Xác thực:** gọi `POST /api/auth/login`, lấy `data.accessToken`, bấm *Authorize* và dán token. \
            Token gắn với một phiên: đăng xuất, đổi mật khẩu, bị khóa thì token hết hiệu lực ở request kế tiếp. Path public luôn \
            xử lý như chưa đăng nhập, kể cả khi gửi token.
            - **Kiểu dữ liệu:** tiền là số nguyên VND; thời điểm trả về là ISO-8601 UTC (`2026-10-14T02:30:00Z`), \
            tham số thời điểm nhận ISO-8601 có offset (`2026-10-14T09:30:00+07:00`, trong query mã hóa `+` \
            thành `%2B`); ngày nghiệp vụ `yyyy-MM-dd` theo giờ Asia/Ho_Chi_Minh.
            """;

    @Bean
    public OpenAPI petcareOpenApi() {
        Components components = new Components()
                .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token trả về từ POST /api/auth/login, gắn với một phiên (sessions). Phiên"
                                + " bị hủy thì token hết hiệu lực ngay (BR-TK-11, 13, 14)."))
                .addSchemas(ERROR_SCHEMA, errorSchema());
        SHARED_DESCRIPTIONS.forEach((name, description) ->
                components.addResponses(name, ApiErrorDocCustomizer.errorResponse(description)));
        return new OpenAPI()
                .info(new Info().title("Pet Care API").version("v1").description(DESCRIPTION))
                .components(components)
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .tags(TAGS);
    }

    @Bean
    public ApiErrorDocCustomizer apiErrorDocCustomizer() {
        return new ApiErrorDocCustomizer();
    }

    @Bean
    public PublicPathSecurityCustomizer publicPathSecurityCustomizer() {
        return new PublicPathSecurityCustomizer(SecurityConfig.publicPaths());
    }

    /** Tên response dùng chung của mã HTTP, hoặc {@code null} nếu mã đó không có. */
    static String sharedResponseName(int status) {
        return SHARED_RESPONSES.get(status);
    }

    static ApiResponse responseRef(String name) {
        return new ApiResponse().$ref("#/components/responses/" + name);
    }

    /** Schema dựng từ record {@link ErrorResponse}; {@code errorCode} liệt kê đủ {@link ErrorCode}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema errorSchema() {
        Schema schema = ModelConverters.getInstance()
                .readAllAsResolvedSchema(new AnnotatedType(ErrorResponse.class))
                .schema;
        Schema errorCode = (Schema) schema.getProperties().get("errorCode");
        errorCode.setEnum(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
        return schema;
    }

    private static Tag tag(String name, String description) {
        return new Tag().name(name).description(description);
    }
}
