package com.petcare.platform.audit;

import com.petcare.platform.security.TraceIdFilter;
import com.petcare.platform.security.UserPrincipal;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * docs/convention/backend/08-logging-and-audit.md — thực thi audit log cho mọi
 * method đánh dấu {@link Auditable}. Chỉ log actor/action/outcome (KHÔNG log
 * tham số method thô) để tránh lộ dữ liệu nhạy cảm (vd mật khẩu tạm trong
 * CreateStaffRequest). Persist bảng audit_log riêng thuộc Module 25 (Audit) —
 * chưa triển khai, log SLF4J có cấu trúc là đủ cho đợt này.
 */
@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");

    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint joinPoint, Auditable auditable) throws Throwable {
        String actorId = "anonymous";
        String actorRole = "none";
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            actorId = String.valueOf(principal.getUserId());
            actorRole = principal.getRole() != null ? principal.getRole().name() : "none";
        }

        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        String action = auditable.action();

        try {
            Object result = joinPoint.proceed();
            log.info("action={} actorId={} actorRole={} outcome=SUCCESS traceId={} timestamp={}",
                    action, actorId, actorRole, traceId, Instant.now());
            return result;
        } catch (Throwable ex) {
            log.warn("action={} actorId={} actorRole={} outcome=FAILURE reason={} traceId={} timestamp={}",
                    action, actorId, actorRole, ex.getClass().getSimpleName(), traceId, Instant.now());
            throw ex;
        }
    }
}
