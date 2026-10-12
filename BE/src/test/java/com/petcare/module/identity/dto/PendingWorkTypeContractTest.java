package com.petcare.module.identity.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.petcare.module.identity.dto.PendingWorkItem.PendingWorkType;

/**
 * {@link PendingWorkType} trùng khít enum {@code PendingWorkItem.type} của contract sinh ra
 * ({@code docs/api/openapi/identity-v1.yaml}), cả tên lẫn thứ tự — thứ tự là thứ tự sắp trong response (docs/adr/0035).
 * Đổi contract mà quên code (hoặc ngược lại) thì đỏ.
 */
class PendingWorkTypeContractTest {

    private static final Path CONTRACT = Path.of("..", "docs", "api", "openapi", "identity-v1.yaml");

    @Test
    void enumMatchesContractExactly() throws IOException {
        String yaml = Files.readString(CONTRACT, StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("PendingWorkItem:.*?type:\\s*\\n\\s*type: string\\s*\\n\\s*enum: \\[([^\\]]+)]",
                Pattern.DOTALL).matcher(yaml);
        assertThat(matcher.find()).as("PendingWorkItem.type enum trong %s", CONTRACT).isTrue();
        List<String> contract = Arrays.stream(matcher.group(1).split(",")).map(String::strip).toList();

        assertThat(Arrays.stream(PendingWorkType.values()).map(Enum::name).toList())
                .containsExactlyElementsOf(contract);
    }
}
