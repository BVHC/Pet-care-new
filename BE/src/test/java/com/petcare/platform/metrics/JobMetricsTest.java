package com.petcare.platform.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** {@link JobMetrics}: một lần chạy, các bộ đếm bản ghi, giá trị âm bỏ qua (docs/adr/0155). */
class JobMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final JobMetrics metrics = new JobMetrics(registry);

    @Test
    void recordsOneRunAndEachItemCounter() {
        metrics.record("no_show", JobMetrics.Outcome.OK, Map.of("marked", 3, "skipped", 1, "failed", 0));
        metrics.record("no_show", JobMetrics.Outcome.BUDGET_STOPPED, Map.of("marked", 2));

        assertThat(registry.get(JobMetrics.RUNS).tags("job", "no_show", "outcome", "ok").counter().count())
                .isEqualTo(1);
        assertThat(registry.get(JobMetrics.RUNS).tags("job", "no_show", "outcome", "budget_stopped").counter()
                .count()).isEqualTo(1);
        assertThat(registry.get(JobMetrics.ITEMS).tags("job", "no_show", "result", "marked").counter().count())
                .isEqualTo(5);
        assertThat(registry.get(JobMetrics.ITEMS).tags("job", "no_show", "result", "skipped").counter().count())
                .isEqualTo(1);
        assertThat(registry.get(JobMetrics.ITEMS).tags("job", "no_show", "result", "failed").counter().count())
                .isZero();
    }

    @Test
    void failedRunWithoutItems() {
        metrics.record("session_cleanup", JobMetrics.Outcome.FAILED);

        assertThat(registry.get(JobMetrics.RUNS).tags("job", "session_cleanup", "outcome", "failed").counter()
                .count()).isEqualTo(1);
        assertThat(registry.find(JobMetrics.ITEMS).counters()).isEmpty();
    }

    @Test
    void negativeOrMissingCountsAreIgnored() {
        Map<String, Long> items = new LinkedHashMap<>();
        items.put("deleted", -1L);
        items.put("kept", null);
        metrics.record("otp_token_cleanup", JobMetrics.Outcome.OK, items);

        assertThat(registry.find(JobMetrics.ITEMS).tag("result", "deleted").counter()).isNull();
        assertThat(registry.get(JobMetrics.ITEMS).tag("result", "kept").counter().count()).isZero();
    }
}
