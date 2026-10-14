package com.petcare.module.identity.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Lệnh sửa của {@link NotificationTemplate} (UC10, BR-QT-14; docs/adr/0037): chỉ {@code subject}, {@code body},
 * {@code updated_by} đổi; so mặc định theo từng ký tự.
 */
class NotificationTemplateTest {

    @Test
    void changeContentTouchesOnlySubjectBodyAndUpdatedBy() {
        NotificationTemplate template = template("Tiêu đề", "Nội dung {ma_otp}");

        template.changeContent("  Mới  ", "Mới\r\n{ma_otp}", 7L);

        assertThat(template.getSubject()).isEqualTo("  Mới  ");
        assertThat(template.getBody()).isEqualTo("Mới\r\n{ma_otp}");
        assertThat(template.getUpdatedBy()).isEqualTo(7L);
        assertThat(template.getDefaultSubject()).isEqualTo("Tiêu đề");
        assertThat(template.getDefaultBody()).isEqualTo("Nội dung {ma_otp}");
        assertThat(template.getChannel()).isEqualTo("EMAIL");
        assertThat(template.getAllowedVars()).containsExactly("ma_otp");
        assertThat(template.getRequiredVars()).containsExactly("ma_otp");
        assertThat(template.isDefault()).isFalse();
    }

    @Test
    void restoreDefaultCopiesBothDefaults() {
        NotificationTemplate template = template("Tiêu đề", "Nội dung {ma_otp}");
        template.changeContent("Khác", "Khác {ma_otp}", 7L);

        template.restoreDefault(8L);

        assertThat(template.getSubject()).isEqualTo("Tiêu đề");
        assertThat(template.getBody()).isEqualTo("Nội dung {ma_otp}");
        assertThat(template.getUpdatedBy()).isEqualTo(8L);
        assertThat(template.isDefault()).isTrue();
    }

    @Test
    void isDefaultComparesEveryCharacterAndHandlesNullSubject() {
        NotificationTemplate template = template(null, "Nội dung");
        assertThat(template.isDefault()).isTrue();

        template.changeContent("", "Nội dung", 1L);
        assertThat(template.isDefault()).isFalse();

        template.changeContent(null, "Nội dung ", 1L);
        assertThat(template.isDefault()).isFalse();
    }

    @Test
    void hasContentComparesBothParts() {
        NotificationTemplate template = template("A", "B");

        assertThat(template.hasContent("A", "B")).isTrue();
        assertThat(template.hasContent("A", "B ")).isFalse();
        assertThat(template.hasContent("a", "B")).isFalse();
    }

    private static NotificationTemplate template(String subject, String body) {
        NotificationTemplate template = BeanUtils.instantiateClass(NotificationTemplate.class);
        ReflectionTestUtils.setField(template, "code", "OTP_REGISTER");
        ReflectionTestUtils.setField(template, "channel", "EMAIL");
        ReflectionTestUtils.setField(template, "subject", subject);
        ReflectionTestUtils.setField(template, "body", body);
        ReflectionTestUtils.setField(template, "defaultSubject", subject);
        ReflectionTestUtils.setField(template, "defaultBody", body);
        ReflectionTestUtils.setField(template, "allowedVars", List.of("ma_otp"));
        ReflectionTestUtils.setField(template, "requiredVars", List.of("ma_otp"));
        return template;
    }
}
