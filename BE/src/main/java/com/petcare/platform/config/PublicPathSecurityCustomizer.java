package com.petcare.platform.config;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponses;

/**
 * Đánh dấu xác thực theo đúng danh sách path public của runtime (docs/adr/0040 mục 3): path khớp
 * {@code SecurityConfig.publicPaths()} có {@code security: []}, path còn lại dùng bearer toàn cục và được thêm
 * {@code 401} nếu chưa khai báo. Cuối cùng sắp {@code responses} theo mã để Swagger UI hiển thị theo thứ tự.
 */
public class PublicPathSecurityCustomizer implements OpenApiCustomizer {

    private final List<PathPattern> publicPatterns;

    public PublicPathSecurityCustomizer(List<String> publicPaths) {
        PathPatternParser parser = new PathPatternParser();
        this.publicPatterns = publicPaths.stream().map(parser::parse).toList();
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().forEach((path, item) -> {
            boolean isPublic = isPublic(path);
            item.readOperations().forEach(operation -> {
                if (isPublic) {
                    operation.setSecurity(List.of());
                } else if (!operation.getResponses().containsKey("401")) {
                    operation.getResponses().addApiResponse("401",
                            OpenApiConfig.responseRef(OpenApiConfig.UNAUTHORIZED));
                }
                sortResponses(operation);
            });
        });
    }

    boolean isPublic(String path) {
        PathContainer container = PathContainer.parsePath(path);
        return publicPatterns.stream().anyMatch(pattern -> pattern.matches(container));
    }

    private static void sortResponses(Operation operation) {
        ApiResponses sorted = new ApiResponses();
        operation.getResponses().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> sorted.addApiResponse(entry.getKey(), entry.getValue()));
        operation.setResponses(sorted);
    }
}
