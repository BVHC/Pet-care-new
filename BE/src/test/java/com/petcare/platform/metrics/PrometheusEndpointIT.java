package com.petcare.platform.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ReflectionUtils;

import com.petcare.TestcontainersConfiguration;

/**
 * {@code /actuator/prometheus} trên cổng management riêng (docs/adr/0155), với cấu hình như profile docker: cổng
 * management khác cổng app và expose {@code prometheus}. Chứng minh:
 * <ul>
 *   <li>cổng management trả số liệu không cần token, cổng app không có endpoint này (404, không phải 401);</li>
 *   <li>{@code /readyz}, {@code /livez} trả lời trên cổng app; endpoint actuator khác không đọc được;</li>
 *   <li>bean thật: mọi job nhận {@link JobMetrics} của context (không còn {@link JobMetrics#NOOP}); {@link BusinessMetrics}
 *       chỉ đếm sau commit trên transaction manager thật.</li>
 * </ul>
 * Không chạy job nào và không ghi DB nên không cần era riêng (docs/architecture/testing-notes.md).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "management.endpoints.web.exposure.include=health,info,prometheus"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
// @SpringBootTest tắt metrics export mặc định; bật lại để có /actuator/prometheus như khi chạy thật.
@AutoConfigureObservability(tracing = false)
class PrometheusEndpointIT {

    @LocalServerPort
    int appPort;

    @LocalManagementPort
    int managementPort;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ApplicationContext context;

    @Autowired
    JobMetrics jobMetrics;

    @Autowired
    BusinessMetrics businessMetrics;

    @Autowired
    TransactionTemplate tx;

    private ResponseEntity<String> management(String path) {
        return rest.getForEntity("http://localhost:" + managementPort + path, String.class);
    }

    private ResponseEntity<String> app(String path) {
        return rest.getForEntity("http://localhost:" + appPort + path, String.class);
    }

    @Test
    void scrapeIsAnonymousOnTheManagementPortAndCarriesHttpJvmAndOutboxMeters() {
        assertThat(managementPort).isNotEqualTo(appPort);
        assertThat(app("/readyz").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> scrape = management("/actuator/prometheus");

        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scrape.getBody())
                .contains("application=\"petcare-api\"")
                .contains("http_server_requests_seconds_bucket")
                .contains("jvm_memory_used_bytes")
                .contains("hikaricp_connections")
                .contains("notification_outbox_pending");
    }

    @Test
    void theApplicationPortHasNoScrapeEndpointButAnswersProbes() {
        assertThat(app("/actuator/prometheus").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(app("/readyz").getBody()).contains("UP");
        assertThat(app("/livez").getStatusCode()).isEqualTo(HttpStatus.OK);
        // Health tổng ở profile test là 503 (không có SMTP / Redis thật); liveness không phụ thuộc chúng.
        assertThat(management("/actuator/health/liveness").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void otherActuatorEndpointsStayClosedOnTheManagementPort() {
        for (String path : List.of("/actuator/env", "/actuator/metrics", "/actuator/beans", "/actuator/heapdump")) {
            assertThat(management(path).getStatusCode()).as(path).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void everyJobBeanReceivedTheContextJobMetrics() {
        List<String> notWired = new ArrayList<>();
        int jobs = 0;
        for (Map.Entry<String, Object> entry : context.getBeansWithAnnotation(Component.class).entrySet()) {
            Class<?> type = AopUtils.getTargetClass(entry.getValue());
            if (ReflectionUtils.findMethod(type, "setJobMetrics", JobMetrics.class) == null) {
                continue;
            }
            jobs++;
            Object target = AopTestUtils.getTargetObject(entry.getValue());
            if (ReflectionTestUtils.getField(target, "jobMetrics") != jobMetrics) {
                notWired.add(entry.getKey());
            }
        }
        assertThat(jobs).as("job + NotificationOutboxRunner (GatewayRefundJob chỉ có khi bật cờ)")
                .isGreaterThanOrEqualTo(20);
        assertThat(notWired).isEmpty();
    }

    @Test
    void businessAndJobCountersReachTheScrapeOnlyAfterCommit() {
        String committed = "it-" + UUID.randomUUID();
        String rolledBack = "it-" + UUID.randomUUID();
        tx.executeWithoutResult(
                status -> businessMetrics.increment(BusinessMetrics.VISIT_COMPLETED, "branch", committed));
        tx.executeWithoutResult(status -> {
            businessMetrics.increment(BusinessMetrics.VISIT_COMPLETED, "branch", rolledBack);
            status.setRollbackOnly();
        });
        jobMetrics.record("prometheus_it", JobMetrics.Outcome.OK, Map.of("checked", 2));

        String body = management("/actuator/prometheus").getBody();

        assertThat(body)
                .contains("petcare_visit_completed_total{application=\"petcare-api\",branch=\"" + committed + "\"} 1.0")
                .doesNotContain(rolledBack)
                .contains("petcare_job_runs_total{application=\"petcare-api\",job=\"prometheus_it\",outcome=\"ok\"} 1.0")
                .contains("petcare_job_items_total{application=\"petcare-api\",job=\"prometheus_it\","
                        + "result=\"checked\"} 2.0");
    }
}
