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
    void catalogHas70Parameters() {
        // 50 tham số [CFG] của docs/02 + session.ttl_hours (docs/adr/0003) + session.access_ttl_minutes (docs/adr/0091); V2 + V27 = 52 dòng
        // + 18 khóa ai.* (docs/07 §4.6, V35, docs/adr/0110) = 70
        assertThat(ConfigKey.values()).hasSize(70);
    }

    @Test
    void aiKeysCoverSpec07Section46() {
        assertThat(Arrays.stream(ConfigKey.values()).map(ConfigKey::key).filter(key -> key.startsWith("ai.")))
                .hasSize(18);
    }
}
