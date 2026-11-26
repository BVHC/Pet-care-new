package com.petcare.platform.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Quy ước của docs/adr/0155: mọi job {@code @Scheduled} ghi kết quả qua {@link JobMetrics} — có setter
 * {@code @Autowired setJobMetrics(JobMetrics)} và gọi {@code jobMetrics.record(} ở cả nhánh thành công lẫn nhánh lỗi. Ba
 * job ST20 chỉ ủy quyền cho {@code NotificationOutboxRunner}, nên runner mang setter thay chúng. Job mới quên gọi metrics
 * thì test này đỏ.
 */
class JobMetricsCoverageTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final String RUNNER = "com.petcare.module.care.job.NotificationOutboxRunner";

    @Test
    void everyScheduledJobRecordsItsOutcome() throws Exception {
        List<Class<?>> jobs = scheduledComponents();
        assertThat(jobs).as("tìm thấy các job @Scheduled").hasSizeGreaterThanOrEqualTo(26);

        List<String> problems = new ArrayList<>();
        for (Class<?> job : jobs) {
            Class<?> owner = delegatesToRunner(job) ? Class.forName(RUNNER) : job;
            if (!hasJobMetricsSetter(owner)) {
                problems.add(owner.getName() + ": thiếu @Autowired setJobMetrics(JobMetrics)");
            }
            long calls = recordCalls(owner);
            if (calls < 2) {
                problems.add(owner.getName() + ": chỉ có " + calls + " lần jobMetrics.record (cần nhánh OK và FAILED)");
            }
        }
        assertThat(problems).isEmpty();
    }

    private static List<Class<?>> scheduledComponents() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<Class<?>> jobs = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.petcare.module")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            boolean scheduled = Arrays.stream(type.getDeclaredMethods())
                    .anyMatch(method -> method.isAnnotationPresent(Scheduled.class));
            if (scheduled) {
                jobs.add(type);
            }
        }
        return jobs;
    }

    private static boolean delegatesToRunner(Class<?> job) {
        return Arrays.stream(job.getDeclaredFields()).anyMatch(field -> field.getType().getName().equals(RUNNER));
    }

    private static boolean hasJobMetricsSetter(Class<?> type) {
        try {
            Method setter = type.getDeclaredMethod("setJobMetrics", JobMetrics.class);
            return setter.isAnnotationPresent(Autowired.class);
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static long recordCalls(Class<?> type) throws IOException {
        Path source = SOURCES.resolve(type.getName().replace('.', '/') + ".java");
        Matcher calls = Pattern.compile("jobMetrics\\.record\\(").matcher(Files.readString(source));
        long count = 0;
        while (calls.find()) {
            count++;
        }
        return count;
    }
}
