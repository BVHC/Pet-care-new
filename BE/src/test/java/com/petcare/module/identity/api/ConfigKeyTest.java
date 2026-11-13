package com.petcare.module.identity.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** Convention 03: khóa {@code system_configs} dạng {@code <nhóm>.<tên>} chữ thường; mỗi khóa một hằng số. */
class ConfigKeyTest {

    @Test
    void keysAreUniqueAndFollowNamingConvention() {
        // Tối đa ba đoạn: khóa ai.* của docs/07 §4.6 có dạng <nhóm>.<mục>.<tên> (ai.chat.max_question_chars), docs/adr/0110
        assertThat(Arrays.stream(ConfigKey.values()).map(ConfigKey::key))
                .doesNotHaveDuplicates()
                .allMatch(key -> key.matches("^[a-z_]+(\\.[a-z_]+){1,2}$"), "matches <group>[.<section>].<name>")
                .allMatch(key -> key.length() <= 100, "fits system_configs.key VARCHAR(100)");
    }

    @Test
    void catalogHas86Parameters() {
        // 50 tham số [CFG] của docs/02 + session.ttl_hours (docs/adr/0003) + session.access_ttl_minutes (docs/adr/0091); V2 + V27 = 52 dòng
        // + 18 khóa ai.* (docs/07 §4.6, V35, docs/adr/0110) = 70
        // + 6 khóa complaint.* (docs/09 §G) + 2 khóa workforce.* (docs/02 BR-NS-04, 07), V40, docs/adr/0113 = 78
        // + 7 khóa của BE-2 (docs/08 §G; V44, V45, V46, V48; docs/adr/0117) = 85
        // + 1 công tắc BOOL ai.chat.general_knowledge_enabled (V52, docs/adr/0136) = 86
        assertThat(ConfigKey.values()).hasSize(86);
    }

    @Test
    void complaintAndWorkforceKeysCoverSpec09SectionGAndBrNs() {
        assertThat(Arrays.stream(ConfigKey.values()).map(ConfigKey::key).filter(key -> key.startsWith("complaint.")))
                .containsExactlyInAnyOrder("complaint.max_open", "complaint.reopen_days", "complaint.max_reopen",
                        "complaint.sla_open_hours", "complaint.sla_resolve_hours", "complaint.auto_close_days");
        assertThat(Arrays.stream(ConfigKey.values()).map(ConfigKey::key).filter(key -> key.startsWith("workforce.")))
                .containsExactlyInAnyOrder("workforce.request_lead_hours", "workforce.absence_report_max_days");
    }

    @Test
    void be2KeysCoverSpec08SectionG() {
        assertThat(Arrays.stream(ConfigKey.values()).map(ConfigKey::key))
                .contains("shop.payment_window_hours", "shop.auto_confirm_days", "shop.max_redelivery",
                        "payment.txn_ttl_minutes", "payment.refund_max_attempts", "returns.window_days",
                        "content.comment_per_hour");
    }

    @Test
    void aiKeysCoverSpec07Section46() {
        assertThat(Arrays.stream(ConfigKey.values()).map(ConfigKey::key).filter(key -> key.startsWith("ai.")))
                .hasSize(19);
    }
}
