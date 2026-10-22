package com.petcare.module.identity.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.identity.dto.NotificationTemplateResponse;
import com.petcare.module.identity.entity.NotificationTemplate;

/**
 * Mẫu thông báo (identity-v1 #33, #34, #35; UC10 — docs/adr/0037). Mọi trường đích khai báo tường minh và
 * {@code unmappedTargetPolicy = ERROR}: thiếu một trường của contract là lỗi biên dịch. {@code default_subject},
 * {@code default_body} cố ý không map (chỉ dùng để tính {@code isDefault}). Chỉ gọi trong transaction của
 * {@code NotificationTemplateAdminService} (OSIV tắt, docs/adr/0020).
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface NotificationTemplateAdminMapper {

    @Mapping(target = "code", source = "code")
    @Mapping(target = "channel", source = "channel")
    @Mapping(target = "subject", source = "subject")
    @Mapping(target = "body", source = "body")
    @Mapping(target = "allowedVars", expression = "java(copy(template.getAllowedVars()))")
    @Mapping(target = "requiredVars", expression = "java(copy(template.getRequiredVars()))")
    @Mapping(target = "isDefault", expression = "java(template.isDefault())")
    NotificationTemplateResponse toResponse(NotificationTemplate template);

    /** Bản sao bất biến: list của entity là list Hibernate quản lý, không để lọt ra ngoài transaction. */
    default List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
