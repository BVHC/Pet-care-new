package com.petcare.platform.metrics;

import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;

/**
 * Kết quả mỗi lượt chạy của job {@code @Scheduled} (docs/adr/0155). Thời gian chạy đã có sẵn ở
 * {@code tasks.scheduled.execution}, nhưng job tự bắt lỗi và chỉ log {@code <NAME>_FAILED} nên meter đó luôn báo thành
 * công; helper này ghi kết quả thật:
 * <ul>
 *   <li>{@code petcare.job.runs{job, outcome}}: một lần cho mỗi lượt chạy;</li>
 *   <li>{@code petcare.job.items{job, result}}: số bản ghi theo đúng bộ đếm job đang log (marked, skipped, failed…).</li>
 * </ul>
 * Gọi cạnh dòng log tổng kết của job và trong {@code catch} ngoài cùng. {@code job} là tên sự kiện log viết thường
 * ({@code NO_SHOW} → {@code no_show}). Không ném lỗi: job vẫn theo quy tắc "không rethrow".
 */
@Component
public class JobMetrics {

    public static final String RUNS = "petcare.job.runs";
    public static final String ITEMS = "petcare.job.items";

    /**
     * Không ghi gì: giá trị mặc định của job khi được dựng bằng tay trong test. Bean thật được tiêm qua setter
     * {@code setJobMetrics} của từng job ({@code JobMetricsCoverageTest} giữ quy ước này).
     */
    public static final JobMetrics NOOP = new JobMetrics(new CompositeMeterRegistry());

    /** Kết thúc của một lượt chạy. */
    public enum Outcome {
        /** Chạy hết, có thể có bản ghi lỗi lẻ (đếm ở {@code items}). */
        OK,
        /** Lỗi ở mức cả lượt (nhánh {@code <NAME>_FAILED} ngoài cùng). */
        FAILED,
        /** Dừng vì hết {@code timeBudget}; phần còn lại để lượt sau. */
        BUDGET_STOPPED;

        /** Lượt chạy không lỗi: {@link #BUDGET_STOPPED} nếu dừng vì hết thời gian, ngược lại {@link #OK}. */
        public static Outcome finished(boolean stoppedByBudget) {
            return stoppedByBudget ? BUDGET_STOPPED : OK;
        }
    }

    private final MeterRegistry meters;

    public JobMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    /** Ghi một lượt chạy, không có bộ đếm bản ghi. */
    public void record(String job, Outcome outcome) {
        record(job, outcome, Map.of());
    }

    /** Ghi một lượt chạy cùng số bản ghi theo từng loại kết quả; giá trị âm bị bỏ qua. */
    public void record(String job, Outcome outcome, Map<String, ? extends Number> items) {
        meters.counter(RUNS, "job", job, "outcome", outcome.name().toLowerCase(Locale.ROOT)).increment();
        items.forEach((result, count) -> {
            double amount = count == null ? 0 : count.doubleValue();
            if (amount >= 0) {
                meters.counter(ITEMS, "job", job, "result", result).increment(amount);
            }
        });
    }
}
