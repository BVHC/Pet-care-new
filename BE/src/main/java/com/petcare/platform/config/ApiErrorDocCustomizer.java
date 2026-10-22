package com.petcare.platform.config;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

/**
 * Chuyển {@link ApiErrorDoc} của handler thành {@code responses} của operation và thêm {@code 500} cho mọi operation
 * (docs/adr/0040 mục 2, 4). Mã không có response dùng chung mà lại không có mô tả là lỗi khai báo: ném ngay lúc dựng
 * tài liệu để {@code OpenApiContractParityIT} đỏ, thay vì sinh một response rỗng.
 */
public class ApiErrorDocCustomizer implements OperationCustomizer {

    static final String JSON = "application/json";

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            operation.setResponses(responses);
        }
        for (ApiErrorDoc doc : handlerMethod.getMethod().getAnnotationsByType(ApiErrorDoc.class)) {
            responses.addApiResponse(String.valueOf(doc.status()), toResponse(doc, handlerMethod));
        }
        responses.addApiResponse("500", OpenApiConfig.responseRef(OpenApiConfig.INTERNAL_ERROR));
        return operation;
    }

    static ApiResponse toResponse(ApiErrorDoc doc, HandlerMethod handlerMethod) {
        if (!doc.description().isBlank()) {
            return errorResponse(doc.description());
        }
        String shared = OpenApiConfig.sharedResponseName(doc.status());
        if (shared == null) {
            throw new IllegalStateException("@ApiErrorDoc(status = " + doc.status() + ") của "
                    + handlerMethod.getShortLogMessage() + " cần description: không có response dùng chung cho mã này");
        }
        return OpenApiConfig.responseRef(shared);
    }

    /** Response lỗi riêng của một endpoint: mô tả kèm schema {@code ErrorResponse}. */
    static ApiResponse errorResponse(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(JSON, new MediaType()
                        .schema(new Schema<>().$ref(OpenApiConfig.ERROR_SCHEMA_REF))));
    }
}
