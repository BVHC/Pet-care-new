package com.petcare.module.notification.service;

import com.petcare.module.notification.entity.NotificationDeliveryLog;
import com.petcare.module.notification.entity.NotificationTask;
import com.petcare.module.notification.gateway.EmailGateway;
import com.petcare.module.notification.repository.NotificationDeliveryLogRepository;
import com.petcare.module.notification.repository.NotificationTaskRepository;
import com.petcare.platform.enums.NotificationChannel;
import com.petcare.platform.enums.NotificationStatus;
import com.petcare.platform.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationTaskRepository notificationTaskRepository;
    @Mock
    private NotificationDeliveryLogRepository notificationDeliveryLogRepository;
    @Mock
    private EmailGateway emailGateway;

    private NotificationServiceImpl notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationServiceImpl(notificationTaskRepository, notificationDeliveryLogRepository, emailGateway);
    }

    @Test
    void enqueue_savesTaskAsPending() {
        UUID recipientUserId = UUID.randomUUID();
        when(notificationTaskRepository.save(any(NotificationTask.class))).thenAnswer(inv -> {
            NotificationTask t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        UUID taskId = notificationService.enqueue(recipientUserId, NotificationChannel.EMAIL, "OTP", "content");

        assertThat(taskId).isNotNull();
        ArgumentCaptor<NotificationTask> captor = ArgumentCaptor.forClass(NotificationTask.class);
        org.mockito.Mockito.verify(notificationTaskRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(captor.getValue().getRecipientUserId()).isEqualTo(recipientUserId);
    }

    @Test
    void dispatch_notFound_throwsResourceNotFound() {
        UUID taskId = UUID.randomUUID();
        when(notificationTaskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.dispatch(taskId, "to@example.com"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void dispatch_emailChannel_success_marksSentAndLogsDeliverySuccess() {
        NotificationTask task = new NotificationTask(UUID.randomUUID(), NotificationChannel.EMAIL, "OTP", "content");
        UUID taskId = UUID.randomUUID();
        task.setId(taskId);
        when(notificationTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(emailGateway.send(anyString(), anyString(), anyString())).thenReturn("msg-1");
        when(emailGateway.providerName()).thenReturn("GMAIL_SMTP");

        notificationService.dispatch(taskId, "to@example.com");

        assertThat(task.getStatus()).isEqualTo(NotificationStatus.SENT);
        ArgumentCaptor<NotificationDeliveryLog> captor = ArgumentCaptor.forClass(NotificationDeliveryLog.class);
        org.mockito.Mockito.verify(notificationDeliveryLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void dispatch_emailChannel_gatewayThrows_marksFailedAndLogsDeliveryFailure() {
        NotificationTask task = new NotificationTask(UUID.randomUUID(), NotificationChannel.EMAIL, "OTP", "content");
        UUID taskId = UUID.randomUUID();
        task.setId(taskId);
        when(notificationTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(emailGateway.send(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("SMTP timeout"));
        when(emailGateway.providerName()).thenReturn("GMAIL_SMTP");

        notificationService.dispatch(taskId, "to@example.com");

        assertThat(task.getStatus()).isEqualTo(NotificationStatus.FAILED);
        ArgumentCaptor<NotificationDeliveryLog> captor = ArgumentCaptor.forClass(NotificationDeliveryLog.class);
        org.mockito.Mockito.verify(notificationDeliveryLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(captor.getValue().getResponsePayload()).contains("SMTP timeout");
    }
}
