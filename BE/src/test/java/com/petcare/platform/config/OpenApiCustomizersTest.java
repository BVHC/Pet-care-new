package com.petcare.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import com.petcare.platform.security.SecurityConfig;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

/**
 * {@link ApiErrorDocCustomizer} và {@link PublicPathSecurityCustomizer} (docs/adr/0040 mục 2–4): lỗi khai báo bằng
 * {@link ApiErrorDoc}, 500 luôn có, path public theo đúng {@code SecurityConfig.publicPaths()} của runtime.
 */
class OpenApiCustomizersTest {

    private final PublicPathSecurityCustomizer security =
            new PublicPathSecurityCustomizer(SecurityConfig.publicPaths());

    @Test
    void publicPathsFollowRuntimeList() {
        assertThat(security.isPublic("/api/auth/login")).isTrue();
        assertThat(security.isPublic("/api/auth/register")).isTrue();
        assertThat(security.isPublic("/api/auth/register/verify")).isTrue();
        assertThat(security.isPublic("/api/auth/password/reset")).isTrue();
        assertThat(security.isPublic("/api/public/vets")).isTrue();

        assertThat(security.isPublic("/api/auth/logout")).isFalse();
        assertThat(security.isPublic("/api/staff/{id}")).isFalse();
        assertThat(security.isPublic("/api/me")).isFalse();
    }

    @Test
    void publicOperationGetsEmptySecurityAndNo401() {
        Operation login = operation("200");
        Operation logout = operation("204");
        OpenAPI api = api("/api/auth/login", login, "/api/auth/logout", logout);

        security.customise(api);

        assertThat(login.getSecurity()).isEmpty();
        assertThat(login.getResponses()).doesNotContainKey("401");
        assertThat(logout.getSecurity()).isNull();
        assertThat(logout.getResponses().get("401").get$ref()).isEqualTo("#/components/responses/Unauthorized");
    }

    @Test
    void declared401IsKeptAndResponsesAreSorted() {
        Operation login = operation("200");
        login.getResponses().addApiResponse("500", new ApiResponse().description("x"));
        login.getResponses().addApiResponse("401", new ApiResponse().description("Sai email hoặc mật khẩu"));
        login.getResponses().addApiResponse("400", new ApiResponse().description("BR-TK-09"));

        security.customise(api("/api/auth/login", login, "/api/me", operation("200")));

        assertThat(login.getResponses().keySet()).containsExactly("200", "400", "401", "500");
        assertThat(login.getResponses().get("401").getDescription()).isEqualTo("Sai email hoặc mật khẩu");
    }

    @Test
    void errorDocsBecomeResponsesAnd500IsAlwaysAdded() throws NoSuchMethodException {
        Operation op = new ApiErrorDocCustomizer().customize(operation("200"), handler("documented"));

        assertThat(op.getResponses().keySet()).containsExactly("200", "400", "404", "409", "500");
        assertThat(op.getResponses().get("404").get$ref()).isEqualTo("#/components/responses/NotFound");
        ApiResponse conflict = op.getResponses().get("409");
        assertThat(conflict.getDescription()).isEqualTo("`INVALID_STATE_TRANSITION` đã bị khóa");
        assertThat(conflict.getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/ErrorResponse");
        assertThat(op.getResponses().get("400").getDescription()).isEqualTo("`BR-QT-11` khóa chính mình");
        assertThat(op.getResponses().get("500").get$ref()).isEqualTo("#/components/responses/InternalError");
    }

    @Test
    void handlerWithoutDocsStillGets500() throws NoSuchMethodException {
        Operation op = new ApiErrorDocCustomizer().customize(new Operation(), handler("undocumented"));

        assertThat(op.getResponses().keySet()).containsExactly("500");
    }

    @Test
    void statusWithoutSharedResponseNeedsDescription() throws NoSuchMethodException {
        ApiErrorDocCustomizer customizer = new ApiErrorDocCustomizer();
        HandlerMethod handler = handler("conflictWithoutDescription");

        assertThatThrownBy(() -> customizer.customize(operation("200"), handler))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status = 409");
    }

    private static Operation operation(String success) {
        return new Operation().responses(new ApiResponses().addApiResponse(success, new ApiResponse().description("OK")));
    }

    private static OpenAPI api(String path1, Operation op1, String path2, Operation op2) {
        return new OpenAPI().paths(new Paths()
                .addPathItem(path1, new PathItem().post(op1))
                .addPathItem(path2, new PathItem().post(op2)));
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        Method method = SampleController.class.getDeclaredMethod(name);
        return new HandlerMethod(new SampleController(), method);
    }

    static class SampleController {

        @ApiErrorDoc(status = 400, description = "`BR-QT-11` khóa chính mình")
        @ApiErrorDoc(status = 404)
        @ApiErrorDoc(status = 409, description = "`INVALID_STATE_TRANSITION` đã bị khóa")
        void documented() {
        }

        void undocumented() {
        }

        @ApiErrorDoc(status = 409)
        void conflictWithoutDescription() {
        }
    }
}
