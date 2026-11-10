package com.petcare.module.ai.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.petcare.module.ai.service.CatalogProtocolSync;
import com.petcare.module.ai.service.KnowledgeIndexer;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.config.TraceContext;

import lombok.extern.slf4j.Slf4j;

/**
 * ST22 — các đường kích hoạt định kỳ (07 §3, docs/adr/0130; cơ chế job: docs/adr/0007). Lượt chạy làm hai việc: (1) khi đã
 * qua [CFG] {@code ai.index.catalog_sync_minutes} kể từ lần đồng bộ trước, đồng bộ tài liệu phác đồ tiêm
 * ({@link CatalogProtocolSync}); (2) xếp hàng mọi tài liệu {@code PUBLISHED} chưa có chỉ mục khớp phiên bản cho luồng nền
 * của {@link KnowledgeIndexer}, nên job <b>không gọi mạng</b> trên thread scheduler. Mọi phút lẻ 5 đã có job dài, nên cron
 * chạy mỗi 5 phút ở giây 45 và chu kỳ thật của bước (1) được kiểm bằng thời gian trôi qua, đọc [CFG] ở mỗi lượt (đổi
 * [CFG] có hiệu lực từ lượt sau). Lỗi được log và nuốt; lượt sau tự thử lại vì điều kiện nằm ở dòng
 * {@code PENDING} / {@code FAILED} trong CSDL. Cũng quét một lần khi khởi động xong (tài liệu còn dở từ lần chạy trước).
 */
@Slf4j
@Component
public class KnowledgeIndexJob {

    private final CatalogProtocolSync catalogSync;
    private final KnowledgeIndexer indexer;
    private final SystemConfigApi config;
    private final Clock clock;
    private volatile Instant lastCatalogSync;

    public KnowledgeIndexJob(CatalogProtocolSync catalogSync, KnowledgeIndexer indexer, SystemConfigApi config,
            Clock clock) {
        this.catalogSync = catalogSync;
        this.indexer = indexer;
        this.config = config;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.jobs.knowledge-index.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)
    public void run() {
        MDC.put(TraceContext.MDC_KEY, "job-" + UUID.randomUUID());
        try {
            Instant now = Instant.now(clock);
            if (catalogSyncDue(now)) {
                int changed = catalogSync.sync();
                lastCatalogSync = now;
                log.info("KNOWLEDGE_INDEX catalogChanged={}", changed);
            }
            indexer.requestPending();
        } catch (RuntimeException e) {
            log.error("KNOWLEDGE_INDEX_JOB_FAILED", e);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    /** Quét một lần khi khởi động xong: tài liệu còn {@code PENDING} / {@code FAILED} từ lần chạy trước. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            indexer.requestPending();
        } catch (RuntimeException e) {
            log.error("KNOWLEDGE_INDEX_STARTUP_SCAN_FAILED", e);
        }
    }

    private boolean catalogSyncDue(Instant now) {
        Instant last = lastCatalogSync;
        return last == null
                || !now.isBefore(last.plus(Duration.ofMinutes(config.getInt(ConfigKey.AI_INDEX_CATALOG_SYNC_MINUTES))));
    }
}
