package com.petcare.platform.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

/** {@link ManagementExposureGuard}: prometheus chỉ được expose khi actuator ở cổng management riêng (docs/adr/0155). */
class ManagementExposureGuardTest {

    /** Cột: include, exclude, server.port, management.server.port ({@code -} = không đặt), có chặn không. */
    @ParameterizedTest
    @CsvSource({
            "'health,info',            -,          8080, -,    false",
            "'health,info,prometheus', -,          8080, -,    true",
            "'*',                      -,          8080, -,    true",
            "'health,PROMETHEUS',      -,          8080, 8080, true",
            "'health,prometheus',      -,          -,    8080, true",
            "'health,prometheus',      prometheus, 8080, -,    false",
            "'*',                      '*',        8080, -,    false",
            "'health,info,prometheus', -,          8080, 9091, false",
            "'health,info,prometheus', -,          0,    0,    false",
            "'health,info,prometheus', -,          8080, -1,   false"})
    void blocksPrometheusOnTheApplicationPort(String include, String exclude, String serverPort,
            String managementPort, boolean blocked) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(ManagementExposureGuard.INCLUDE, include);
        set(environment, ManagementExposureGuard.EXCLUDE, exclude);
        set(environment, "server.port", serverPort);
        set(environment, "management.server.port", managementPort);

        if (blocked) {
            assertThatThrownBy(() -> new ManagementExposureGuard(environment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("management.server.port");
        } else {
            assertThatCode(() -> new ManagementExposureGuard(environment)).doesNotThrowAnyException();
        }
    }

    private static void set(MockEnvironment environment, String key, String value) {
        if (!"-".equals(value)) {
            environment.setProperty(key, value);
        }
    }
}
