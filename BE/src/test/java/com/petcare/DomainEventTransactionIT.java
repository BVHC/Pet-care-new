package com.petcare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;

/**
 * Cơ chế sự kiện đồng bộ của docs/convention/backend/07-transaction-management.md §7.2 (06-module-contracts §1):
 * {@code publishEvent} + {@code @EventListener} chạy cùng thread, cùng transaction với bên phát; listener lỗi thì
 * rollback cả use case; phát ngoài transaction thì service {@code MANDATORY} của bên nhận lỗi ngay.
 * Các bean thăm dò chỉ có trong test và mô phỏng đúng pattern: service phát → listener → service nhận.
 * {@code audit_logs} không xóa được (trigger BR-QT-16) nên mỗi test đánh dấu bản ghi bằng {@code reason} ngẫu nhiên.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, DomainEventTransactionIT.TestBeans.class})
class DomainEventTransactionIT {

    record ProbeEvent(String marker, boolean fail) {
    }

    /** Bên phát: đổi dữ liệu của mình rồi phát sự kiện, trong transaction của use case. */
    static class ProbePublisherService {
        private final AuditRecorder recorder;
        private final ApplicationEventPublisher events;

        ProbePublisherService(AuditRecorder recorder, ApplicationEventPublisher events) {
            this.recorder = recorder;
            this.events = events;
        }

        @Transactional
        public void publish(String marker, boolean fail) {
            recorder.record(AuditEntry.of("PROBE_PUBLISHED").reason(marker));
            events.publishEvent(new ProbeEvent(marker, fail));
        }
    }

    /** Bên nhận: listener không logic, chỉ gọi service của module mình. */
    static class ProbeListener {
        private final ProbeHandlerService handler;
        private final AtomicReference<Thread> thread = new AtomicReference<>();
        private final AtomicReference<String> transactionName = new AtomicReference<>();

        ProbeListener(ProbeHandlerService handler) {
            this.handler = handler;
        }

        @EventListener
        public void on(ProbeEvent event) {
            thread.set(Thread.currentThread());
            transactionName.set(TransactionSynchronizationManager.isActualTransactionActive()
                    ? TransactionSynchronizationManager.getCurrentTransactionName()
                    : null);
            handler.handle(event);
        }
    }

    static class ProbeHandlerService {
        private final AuditRecorder recorder;

        ProbeHandlerService(AuditRecorder recorder) {
            this.recorder = recorder;
        }

        @Transactional(propagation = Propagation.MANDATORY)
        public void handle(ProbeEvent event) {
            recorder.record(AuditEntry.of("PROBE_HANDLED").reason(event.marker()));
            if (event.fail()) {
                throw new IllegalStateException("listener failed");
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        ProbePublisherService probePublisherService(AuditRecorder recorder, ApplicationEventPublisher events) {
            return new ProbePublisherService(recorder, events);
        }

        @Bean
        ProbeHandlerService probeHandlerService(AuditRecorder recorder) {
            return new ProbeHandlerService(recorder);
        }

        @Bean
        ProbeListener probeListener(ProbeHandlerService handler) {
            return new ProbeListener(handler);
        }
    }

    @Autowired
    private ProbePublisherService publisher;

    @Autowired
    private ProbeListener listener;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private JdbcTemplate jdbc;

    private String marker;

    @BeforeEach
    void setUp() {
        marker = UUID.randomUUID().toString();
        listener.thread.set(null);
        listener.transactionName.set(null);
    }

    @Test
    void listenerWritesCommitWithPublisherInSameThreadAndTransaction() {
        publisher.publish(marker, false);

        assertThat(countMarked("PROBE_PUBLISHED")).isEqualTo(1);
        assertThat(countMarked("PROBE_HANDLED")).isEqualTo(1);
        assertThat(listener.thread.get()).isSameAs(Thread.currentThread());
        assertThat(listener.transactionName.get())
                .isEqualTo(ProbePublisherService.class.getName() + ".publish");
    }

    @Test
    void listenerFailureRollsBackPublisher() {
        assertThatThrownBy(() -> publisher.publish(marker, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("listener failed");

        assertThat(countMarked("PROBE_PUBLISHED")).isZero();
        assertThat(countMarked("PROBE_HANDLED")).isZero();
    }

    @Test
    void publishOutsideTransactionFailsFast() {
        assertThatThrownBy(() -> events.publishEvent(new ProbeEvent(marker, false)))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(listener.transactionName.get()).isNull();
        assertThat(countMarked("PROBE_HANDLED")).isZero();
    }

    private long countMarked(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE reason = ? AND action = ?",
                Long.class, marker, action);
    }
}
