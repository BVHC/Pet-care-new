package com.petcare.module.identity.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** Convention 03: khóa {@code system_configs} dạng {@code <nhóm>.<tên>} chữ thường; mỗi khóa một hằng số. */
class ConfigKeyTest {

    @Test
    void keysAreUniqueAndFollowNamingConvention() {
        assertThat(Arrays.stream(ConfigKey.values()).map(ConfigKey::key))
                .doesNotHaveDuplicates()
                .allMatch(key -> key.matches("^[a-z_]+\\.[a-z_]+$"), "matches <group>.<name>")
                .allMatch(key -> key.length() <= 100, "fits system_configs.key VARCHAR(100)");
    }

    @Test
    void catalogHas52Parameters() {
        // 51 tham số [CFG] của docs/02 + session.ttl_hours (docs/adr/0003); V2 seed đúng 52 dòng
        assertThat(ConfigKey.values()).hasSize(52);
    }
}
