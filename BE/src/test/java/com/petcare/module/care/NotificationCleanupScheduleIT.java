package com.petcare.module.care;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;

import com.petcare.TestcontainersConfiguration;

/**
 * Khi có cron, job dọn thông báo thật sự được đăng ký với scheduler (docs/adr/0007, 0112): chứng minh
 * {@code @EnableScheduling} đang bật và tên property đúng. Zone giờ Việt Nam kiểm ở {@code NotificationCleanupJobTest}.
 */
@SpringBootTest(properties = "app.jobs.notification-cleanup.cron=23 50 3 * * *")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class NotificationCleanupScheduleIT {

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Test
    void registeredAsCronTaskWithConfiguredExpression() {
        List<CronTask> tasks = scheduledTasks.getScheduledTasks().stream()
                .filter(task -> task.toString().contains("NotificationCleanupJob.run"))
                .map(task -> task.getTask())
                .filter(CronTask.class::isInstance).map(CronTask.class::cast)
                .toList();

        assertThat(tasks).singleElement().extracting(CronTask::getExpression).isEqualTo("23 50 3 * * *");
    }
}
