package com.petcare.module.care.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.petcare.module.care.dto.NotificationResponse;
import com.petcare.module.care.dto.UnreadCountResponse;
import com.petcare.module.care.entity.Notification;

/**
 * Thông báo trong ứng dụng (care-v1 #5, #6; docs/adr/0039). Mọi trường đích khai báo tường minh và
 * {@code unmappedTargetPolicy = ERROR}: thiếu một trường của contract là lỗi biên dịch. Không trả {@code accountId},
 * {@code updatedAt}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface NotificationMapper {

    @Mapping(target = "notificationId", source = "id")
    @Mapping(target = "type", source = "type")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "body", source = "body")
    @Mapping(target = "linkUrl", source = "linkUrl")
    @Mapping(target = "readAt", source = "readAt")
    @Mapping(target = "createdAt", source = "createdAt")
    NotificationResponse toResponse(Notification notification);

    @Mapping(target = "count", source = "count")
    UnreadCountResponse toUnreadCount(Integer count);
}
