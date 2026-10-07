package com.petcare.module.care.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.service.DeliveryLane;
import com.petcare.platform.config.TimeConfig;

/** Ba job ST20 chỉ ủy quyền cho runner theo đúng luồng, lịch từ property, giờ Việt Nam, không transaction. */
class NotificationOutboxJobsTest {

    private final NotificationOutboxRunner runner = mock(NotificationOutboxRunner.class);

    @Test
    void priorityJobRunsHighLane() {
        new PriorityNotificationJob(runner).run();

        verify(runner).run(DeliveryLane.HIGH);
    }

    @Test
    void outboxJobRunsNormalLane() {
        new NotificationOutboxJob(runner).run();

        verify(runner).run(DeliveryLane.NORMAL);
    }

    @Test
    void inAppJobRunsInAppLane() {
        new InAppNotificationJob(runner).run();

        verify(runner).run(DeliveryLane.IN_APP);
    }

    @Test
    void scheduledFromPropertiesInBusinessZone() throws NoSuchMethodException {
        assertSchedule(PriorityNotificationJob.class, "${app.jobs.notification-outbox.priority-cron}");
        assertSchedule(NotificationOutboxJob.class, "${app.jobs.notification-outbox.cron}");
        assertSchedule(InAppNotificationJob.class, "${app.jobs.notification-outbox.in-app-cron}");
    }

    /** Mỗi dòng một transaction ở service (convention 07 §7.4); job và runner không được bao cả lượt. */
    @Test
    void jobsAndRunnerAreNotTransactional() throws NoSuchMethodException {
        for (Class<?> type : new Class<?>[] {PriorityNotificationJob.class, NotificationOutboxJob.class,
                InAppNotificationJob.class, NotificationOutboxRunner.class}) {
            assertThat(type.isAnnotationPresent(Transactional.class)).as(type.getSimpleName()).isFalse();
            assertThat(type.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
            for (Method method : type.getDeclaredMethods()) {
                assertThat(method.isAnnotationPresent(Transactional.class)).as(method.toString()).isFalse();
            }
        }
    }

    @Test
    void defaultCronsMatchPlan() {
        assertThat(CronExpression.isValidExpression("*/5 * * * * *")).isTrue();
        assertThat(CronExpression.isValidExpression("*/10 * * * * *")).isTrue();
    }

    private static void assertSchedule(Class<?> job, String cron) throws NoSuchMethodException {
        Scheduled scheduled = job.getMethod("run").getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo(cron);
        assertThat(scheduled.zone()).isEqualTo(TimeConfig.BUSINESS_ZONE_ID);
    }
}
