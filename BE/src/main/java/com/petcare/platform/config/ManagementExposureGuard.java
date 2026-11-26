package com.petcare.platform.config;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Chặn khởi động khi {@code /actuator/prometheus} lộ trên cổng app (docs/adr/0155). Path này nằm trong
 * {@code SecurityConfig.PUBLIC_PATHS} vì Spring Boot áp chuỗi filter JWT cả lên cổng management; điều đó chỉ an toàn khi
 * actuator chạy trên cổng management riêng không publish ra ngoài. Guard này giữ điều kiện đó: expose {@code prometheus}
 * (hoặc {@code *}) mà cổng management trùng cổng app thì ném {@link IllegalStateException}.
 */
@Component
public class ManagementExposureGuard {

    static final String INCLUDE = "management.endpoints.web.exposure.include";
    static final String EXCLUDE = "management.endpoints.web.exposure.exclude";

    public ManagementExposureGuard(Environment environment) {
        check(environment);
    }

    static void check(Environment environment) {
        if (!prometheusExposed(environment)) {
            return;
        }
        ManagementPortType portType = ManagementPortType.get(environment);
        if (portType == ManagementPortType.SAME) {
            throw new IllegalStateException("/actuator/prometheus không cần đăng nhập nên chỉ được expose trên cổng "
                    + "management riêng: đặt management.server.port khác server.port, hoặc bỏ prometheus khỏi " + INCLUDE
                    + " (docs/adr/0155)");
        }
    }

    static boolean prometheusExposed(Environment environment) {
        Binder binder = Binder.get(environment);
        Set<String> include = names(binder, INCLUDE);
        Set<String> exclude = names(binder, EXCLUDE);
        if (exclude.contains("*") || exclude.contains("prometheus")) {
            return false;
        }
        return include.contains("*") || include.contains("prometheus");
    }

    private static Set<String> names(Binder binder, String key) {
        return binder.bind(key, Bindable.setOf(String.class)).orElse(Set.of()).stream()
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }
}
