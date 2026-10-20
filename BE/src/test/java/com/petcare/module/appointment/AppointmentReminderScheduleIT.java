package com.petcare.module.appointment;

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

/** Khi có cron, ST03 thật sự được đăng ký với scheduler (docs/adr/0051): tên property đúng. */
@SpringBootTest(properties = "app.jobs.appointment-reminder.cron=0 2/5 * * * *")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AppointmentReminderScheduleIT {

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Test
    void registeredAsCronTaskWithConfiguredExpression() {
        List<CronTask> tasks = scheduledTasks.getScheduledTasks().stream()
                .filter(task -> task.toString().contains("AppointmentReminderJob.run"))
                .map(task -> task.getTask())
                .filter(CronTask.class::isInstance).map(CronTask.class::cast)
                .toList();

        assertThat(tasks).singleElement().extracting(CronTask::getExpression).isEqualTo("0 2/5 * * * *");
    }
}
