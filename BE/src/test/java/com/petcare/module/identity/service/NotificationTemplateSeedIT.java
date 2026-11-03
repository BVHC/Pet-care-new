package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.care.service.NotificationTemplateRenderer;
import com.petcare.module.identity.api.NotificationTemplateQueryApi;
import com.petcare.module.identity.api.NotificationTemplateQueryApi.TemplateView;

/**
 * D019: mọi mẫu thông báo do migration seed (V3–V26) đúng ngữ pháp của bộ quét {@link TemplateVariables} và được
 * renderer quét một lượt — giá trị payload chứa token của biến khác ra nguyên văn, không mẫu nào dựa vào việc bị thay
 * hai lần. Bỏ qua mẫu {@code IT_*} do các IT khác tự thêm.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class NotificationTemplateSeedIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationTemplateQueryApi templates;

    private final NotificationTemplateRenderer renderer = new NotificationTemplateRenderer();

    @Test
    void everySeededTemplateIsValidAndRendersValuesLiterally() {
        List<String> codes = jdbc.queryForList(
                "SELECT code FROM notification_templates WHERE code NOT LIKE 'IT\\_%' ORDER BY code", String.class);
        assertThat(codes).as("mẫu seed").hasSizeGreaterThan(20);

        for (String code : codes) {
            TemplateView template = templates.findTemplate(code).orElseThrow();
            assertThat(TemplateVariables.scan(template.subject(), template.allowedVars()).valid())
                    .as("%s — subject", code).isTrue();
            assertThat(TemplateVariables.scan(template.body(), template.allowedVars()).valid())
                    .as("%s — body", code).isTrue();

            Map<String, Object> sentinels = new HashMap<>();
            Map<String, Object> hostile = new HashMap<>();
            List<String> vars = template.allowedVars();
            for (int i = 0; i < vars.size(); i++) {
                sentinels.put(vars.get(i), "§" + i + "§");
                hostile.put(vars.get(i), "⟦{" + vars.get((i + 1) % vars.size()) + "}{" + vars.get(0) + "⟧");
            }
            String expected = renderer.render(template, sentinels).body();
            for (int i = 0; i < vars.size(); i++) {
                expected = expected.replace("§" + i + "§", (String) hostile.get(vars.get(i)));
            }

            assertThat(renderer.render(template, hostile).body()).as("%s — giá trị không bị quét lại", code)
                    .isEqualTo(expected);
        }
    }
}
