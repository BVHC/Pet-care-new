package com.petcare.module.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.content.entity.PageContent;
import com.petcare.module.content.entity.PageKey;
import com.petcare.module.content.repository.PageContentRepository;

/**
 * Nền module content: V21 seed bốn trang tĩnh (erd §10) và các entity khớp schema ({@code ddl-auto=validate} làm
 * context khởi động thất bại nếu cột / kiểu lệch).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ContentSchemaIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PageContentRepository pages;

    @Test
    void pageContentsSeededWithFourEmptyObjectsOneForEveryKey() {
        List<String> keys = jdbc.queryForList("SELECT key FROM page_contents ORDER BY key", String.class);

        assertThat(keys).containsExactlyInAnyOrderElementsOf(
                java.util.Arrays.stream(PageKey.values()).map(Enum::name).toList());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM page_contents WHERE jsonb_typeof(content) = 'object'",
                Long.class)).isEqualTo(4L);
    }

    @Test
    void jsonContentLoadsAsAnObjectNode() {
        PageContent about = pages.findById("ABOUT").orElseThrow();

        assertThat(about.getContent().isObject()).isTrue();
        assertThat(about.getUpdatedBy()).isNull();
    }
}
