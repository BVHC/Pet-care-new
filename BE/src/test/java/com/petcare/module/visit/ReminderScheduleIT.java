package com.petcare.module.visit;

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
 * Khi có cron, ST04 và ST18 thật sự được đăng ký với scheduler (docs/adr/0082, 0083): tên property đúng cho cả ba job. Profile {@code test}
 * đặt cron {@code "-"}; ở đây ghi đè bằng biểu thức mặc định của {@code application.yml}.
 */
@SpringBootTest(properties = {"app.jobs.vaccine-reminder.cron=30 10 7-20 * * *",
        "app.jobs.follow-up-reminder.cron=30 40 7-20 * * *", "app.jobs.vaccine-overdue.cron=30 25 7-20 * * *"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ReminderScheduleIT {

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    private List<CronTask> tasksOf(String method) {
        return scheduledTasks.getScheduledTasks().stream().filter(task -> task.toString().contains(method))
                .map(task -> task.getTask()).filter(CronTask.class::isInstance).map(CronTask.class::cast).toList();
    }

    @Test
    void vaccineReminderIsRegisteredAsCronTaskWithConfiguredExpression() {
        assertThat(tasksOf("VaccineReminderJob.run")).singleElement().extracting(CronTask::getExpression)
                .isEqualTo("30 10 7-20 * * *");
    }

    @Test
    void vaccineOverdueIsRegisteredAsCronTaskWithConfiguredExpression() {
        assertThat(tasksOf("VaccineOverdueJob.run")).singleElement().extracting(CronTask::getExpression)
                .isEqualTo("30 25 7-20 * * *");
    }

    @Test
    void followUpReminderIsRegisteredAsCronTaskWithConfiguredExpression() {
        assertThat(tasksOf("FollowUpReminderJob.run")).singleElement().extracting(CronTask::getExpression)
                .isEqualTo("30 40 7-20 * * *");
    }
}
