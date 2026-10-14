package com.petcare.module.care.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.care.dto.NotificationResponse;
import com.petcare.module.care.dto.UnreadCountResponse;
import com.petcare.module.care.entity.Notification;

/** care-v1 schema {@code Notification}, {@code UnreadCount}: mỗi trường một giá trị riêng để bắt map nhầm nguồn. */
class NotificationMapperTest {

    private final NotificationMapper mapper = Mappers.getMapper(NotificationMapper.class);

    @Test
    void mapsEveryContractField() {
        Instant read = Instant.parse("2026-10-14T05:06:07Z");
        Instant created = Instant.parse("2026-10-13T01:02:03Z");
        Notification notification = Notification.deliver(99L, "LAST_BRANCH_MANAGER_LOCKED", "Tiêu đề A",
                "Nội dung B", "/admin/branches/4");
        ReflectionTestUtils.setField(notification, "id", 123L);
        ReflectionTestUtils.setField(notification, "readAt", read);
        ReflectionTestUtils.setField(notification, "createdAt", created);
        ReflectionTestUtils.setField(notification, "updatedAt", Instant.parse("2026-10-14T09:09:09Z"));

        assertThat(mapper.toResponse(notification)).isEqualTo(new NotificationResponse(123L,
                "LAST_BRANCH_MANAGER_LOCKED", "Tiêu đề A", "Nội dung B", "/admin/branches/4", read, created));
    }

    @Test
    void unreadWithoutLinkKeepsNulls() {
        Instant created = Instant.parse("2026-10-13T01:02:03Z");
        Notification notification = Notification.deliver(99L, "T", "Tiêu đề", "Nội dung", null);
        ReflectionTestUtils.setField(notification, "id", 1L);
        ReflectionTestUtils.setField(notification, "createdAt", created);

        assertThat(mapper.toResponse(notification))
                .isEqualTo(new NotificationResponse(1L, "T", "Tiêu đề", "Nội dung", null, null, created));
    }

    @Test
    void unreadCount() {
        assertThat(mapper.toUnreadCount(0)).isEqualTo(new UnreadCountResponse(0));
        assertThat(mapper.toUnreadCount(17)).isEqualTo(new UnreadCountResponse(17));
    }
}
