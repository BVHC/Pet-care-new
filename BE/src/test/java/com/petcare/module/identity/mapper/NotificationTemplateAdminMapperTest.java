package com.petcare.module.identity.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.identity.dto.NotificationTemplateResponse;
import com.petcare.module.identity.entity.NotificationTemplate;

/**
 * identity-v1 schema {@code NotificationTemplate} (#33–35; docs/adr/0037): mỗi trường lấy đúng nguồn. Mọi cột của
 * entity có giá trị khác nhau — đặc biệt {@code subject} ≠ {@code default_subject}, {@code body} ≠ {@code default_body}
 * (ở seed V3–V12 chúng trùng nhau nên test theo seed không bắt được map nhầm cột).
 */
class NotificationTemplateAdminMapperTest {

    private final NotificationTemplateAdminMapper mapper = Mappers.getMapper(NotificationTemplateAdminMapper.class);

    @Test
    void everyFieldComesFromItsOwnColumn() {
        NotificationTemplate template = template("ACCOUNT_LOCKED_FOLLOW_UP_APP", "IN_APP", "Tiêu đề đang dùng",
                "Nội dung đang dùng {ly_do}", "Tiêu đề mặc định", "Nội dung mặc định {ly_do}",
                List.of("ho_ten", "ly_do", "thoi_diem"), List.of("thoi_diem", "ly_do"));
        ReflectionTestUtils.setField(template, "updatedBy", 42L);

        assertThat(mapper.toResponse(template)).isEqualTo(new NotificationTemplateResponse(
                "ACCOUNT_LOCKED_FOLLOW_UP_APP", Channel.IN_APP, "Tiêu đề đang dùng", "Nội dung đang dùng {ly_do}",
                List.of("ho_ten", "ly_do", "thoi_diem"), List.of("thoi_diem", "ly_do"), false));
    }

    @Test
    void isDefaultFollowsTheCurrentContent() {
        NotificationTemplate template = template("OTP_REGISTER", "EMAIL", "S", "B {ma_otp}", "S", "B {ma_otp}",
                List.of("ma_otp"), List.of("ma_otp"));
        assertThat(mapper.toResponse(template).isDefault()).isTrue();

        template.changeContent("S", "B {ma_otp} ", 1L);
        assertThat(mapper.toResponse(template).isDefault()).isFalse();
    }

    @Test
    void nullSubjectStaysNull() {
        NotificationTemplate template = template("X", "EMAIL", null, "B", null, "B", List.of(), List.of());

        NotificationTemplateResponse response = mapper.toResponse(template);

        assertThat(response.subject()).isNull();
        assertThat(response.isDefault()).isTrue();
        assertThat(response.allowedVars()).isEmpty();
    }

    @Test
    void listsAreImmutableCopies() {
        List<String> allowed = new ArrayList<>(List.of("ma_otp"));
        NotificationTemplate template = template("X", "EMAIL", "S", "B", "S", "B", allowed, List.of());

        NotificationTemplateResponse response = mapper.toResponse(template);
        allowed.add("sau");

        assertThat(response.allowedVars()).containsExactly("ma_otp");
        assertThatThrownBy(() -> response.allowedVars().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unknownChannelInTheDatabaseFailsLoudly() {
        NotificationTemplate template = template("X", "SMS", "S", "B", "S", "B", List.of(), List.of());

        assertThatThrownBy(() -> mapper.toResponse(template)).isInstanceOf(IllegalArgumentException.class);
    }

    /** JSON giữ đúng 7 khóa của hợp đồng; {@code isDefault} không bị Jackson đổi thành {@code default}. */
    @Test
    void jsonHasExactlyTheContractKeys() throws Exception {
        NotificationTemplate template = template("OTP_REGISTER", "EMAIL", "S", "B", "S", "B", List.of("ma_otp"),
                List.of("ma_otp"));

        Map<String, Object> json = new ObjectMapper().readValue(
                new ObjectMapper().writeValueAsString(mapper.toResponse(template)), new TypeReference<>() {
                });

        assertThat(json).containsOnlyKeys("code", "channel", "subject", "body", "allowedVars", "requiredVars",
                "isDefault").containsEntry("isDefault", true).containsEntry("channel", "EMAIL");
    }

    private static NotificationTemplate template(String code, String channel, String subject, String body,
            String defaultSubject, String defaultBody, List<String> allowed, List<String> required) {
        NotificationTemplate template = BeanUtils.instantiateClass(NotificationTemplate.class);
        ReflectionTestUtils.setField(template, "code", code);
        ReflectionTestUtils.setField(template, "channel", channel);
        ReflectionTestUtils.setField(template, "subject", subject);
        ReflectionTestUtils.setField(template, "body", body);
        ReflectionTestUtils.setField(template, "defaultSubject", defaultSubject);
        ReflectionTestUtils.setField(template, "defaultBody", defaultBody);
        ReflectionTestUtils.setField(template, "allowedVars", allowed);
        ReflectionTestUtils.setField(template, "requiredVars", required);
        return template;
    }
}
