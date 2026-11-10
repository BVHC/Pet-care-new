package com.petcare.module.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * {@code app.ai} từ {@code application.yml} thật (docs/adr/0109 D4, 0122, 0130). Không khẳng định giá trị khóa mặc định:
 * máy dev có thể đã export {@code GEMINI_API_KEY}; chỉ kiểm khóa đi vào đúng tên biến và không bị in ra.
 */
class AiPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void defaultsFromApplicationYml() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AiProperties properties = context.getBean(AiProperties.class);
            assertThat(properties.provider()).isEqualTo("gemini");
            assertThat(properties.baseUrl()).isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/");
            assertThat(properties.llmModel()).isEqualTo("gemini-3.1-flash-lite");
            assertThat(properties.embeddingModel()).isEqualTo("gemini-embedding-001");
            assertThat(properties.embeddingDim()).isEqualTo(768);
        });
    }

    @Test
    void keyAndModelsComeFromEnvironmentVariablesAndTheKeyIsNeverPrinted() {
        runner.withPropertyValues("GEMINI_API_KEY=khoa-gia-cho-test", "AI_LLM_MODEL=gemini-2.5-flash", "AI_PROVIDER=off")
                .run(context -> {
                    AiProperties properties = context.getBean(AiProperties.class);
                    assertThat(properties.apiKey()).isEqualTo("khoa-gia-cho-test");
                    assertThat(properties.llmModel()).isEqualTo("gemini-2.5-flash");
                    assertThat(properties.provider()).isEqualTo("off");
                    assertThat(properties.toString()).doesNotContain("khoa-gia-cho-test").contains("<masked>");
                });
    }

    @Test
    void toStringSaysUnsetWhenThereIsNoKey() {
        runner.withPropertyValues("GEMINI_API_KEY=")
                .run(context -> assertThat(context.getBean(AiProperties.class).toString()).contains("<unset>"));
    }
}
