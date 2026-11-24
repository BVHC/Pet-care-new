package com.petcare.module.inpatient.service;

import org.springframework.stereotype.Component;

import com.petcare.module.inpatient.entity.Admission;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.api.VisitQueryApi.VisitSummary;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Một nguồn cho luật "mọi việc của VET trên ca (xuất viện, ra và hủy y lệnh) thuộc VET đang được gán Visit" (BR-LT-13,
 * BR-LT-15; khuôn {@code VisitCompletionService}; docs/adr/0149 mục 29). Gán lại lượt (BR-TN-08) chuyển quyền sang người
 * mới. Đọc Visit không khóa: chấp nhận ở D080.
 */
@Component
public class AdmissionAssignees {

    private final VisitQueryApi visitQueries;

    public AdmissionAssignees(VisitQueryApi visitQueries) {
        this.visitQueries = visitQueries;
    }

    /**
     * @throws AccessDeniedScopeException người gọi không phải người đang được gán Visit của ca (403)
     * @throws IllegalStateException Visit của ca biến mất (dữ liệu hỏng: không có xóa cứng)
     */
    public void requireAssignee(Admission admission, SecurityPrincipal principal) {
        VisitSummary visit = visitQueries.findVisit(admission.getVisitId())
                .orElseThrow(() -> new IllegalStateException("Lượt tiếp nhận #" + admission.getVisitId()
                        + " của ca nội trú #" + admission.getId() + " biến mất"));
        if (!principal.accountId().equals(visit.assigneeId())) {
            throw new AccessDeniedScopeException("assignee:" + visit.assigneeId(), "account:" + principal.accountId());
        }
    }
}
