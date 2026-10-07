package com.petcare.module.identity.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.NotificationTemplateQueryApi;
import com.petcare.module.identity.entity.NotificationTemplate;
import com.petcare.module.identity.repository.NotificationTemplateRepository;

/** Cài {@link NotificationTemplateQueryApi}: đọc {@code notification_templates} cho worker ST20 (docs/adr/0012). */
@Service
public class NotificationTemplateService implements NotificationTemplateQueryApi {

    private final NotificationTemplateRepository templates;

    public NotificationTemplateService(NotificationTemplateRepository templates) {
        this.templates = templates;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TemplateView> findTemplate(String code) {
        return templates.findById(code).map(NotificationTemplateService::toView);
    }

    private static TemplateView toView(NotificationTemplate template) {
        return new TemplateView(template.getCode(), template.getChannel(), template.getSubject(), template.getBody(),
                List.copyOf(template.getAllowedVars()), List.copyOf(template.getRequiredVars()));
    }
}
