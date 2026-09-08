[← Backend Convention Index](../backend-convention.md)

# 6. Validation

| Layer | Trách nhiệm | Cơ chế |
|---|---|---|
| Controller | Format/shape input (null, độ dài, kiểu dữ liệu) | Bean Validation (`@Valid`, `@NotNull`, `@Size`, `@Future`...) |
| Service / TransitionHandler | Toàn bộ Business Rule (RULE-ID trong `02-business-rules.md`) | Validate thủ công, throw `BusinessRuleViolationException(ruleId, message)` |

Controller **không bao giờ** biết đến RULE-ID. Mọi guard nghiệp vụ nằm ở Service/Handler, ngay trước thao tác ghi dữ liệu, trong cùng transaction.

```java
@PostMapping("/appointments")
public AppointmentResponse book(@Valid @RequestBody BookAppointmentRequest req) {
    return mapper.toResponse(appointmentService.bookAppointment(req));
}

// Service
public Appointment bookAppointment(BookAppointmentRequest req) {
    checkPetScheduleCollision(req); // RULE-06-11
    checkResourceCollision(req);    // RULE-06-10
    ...
}
```

---

[← 5. FSM Implementation Pattern](05-fsm-pattern.md) · [Backend Convention Index](../backend-convention.md) · [Tiếp: 7. Transaction Management →](07-transaction-management.md)
