# API Design Matrix

Use this matrix before writing the final OpenAPI specification.

The matrix must be derived from business operations, business rules, state machines, domain models, authorization requirements, and cross-domain workflows.

| Operation | Actor | Domain | Preconditions | Business Rules | Method | Endpoint | Auth | Authorization | State Transition | Dependencies | External Effects | Idempotency | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Example: Create order | Customer | Order | Authenticated | Items must be valid | POST | /orders | Authenticated | Customer | — | Product | — | Required if retry can duplicate creation | |
| Example: Cancel order | Customer | Order | Order exists | Only cancellable orders | POST | /orders/{orderId}/cancel | Authenticated | Owner/Admin | CONFIRMED → CANCELLED | Payment | Refund/notification | Consider | |

## Rules

1. Every important business operation should be represented.
2. Do not derive the matrix directly from database tables.
3. Distinguish CRUD from business commands.
4. Explicitly record state transitions.
5. Explicitly record authorization.
6. Record cross-domain dependencies.
7. Record external side effects.
8. Record idempotency requirements where relevant.
9. Record concurrency concerns where relevant.
10. If information is unknown, mark it as an assumption rather than inventing behavior.

## Review Questions

- Is this really CRUD?
- Does it represent a meaningful business command?
- Does it cause a state transition?
- Who is allowed to perform it?
- Is ownership relevant?
- Does another domain participate?
- Does it call an external system?
- Can the operation be retried safely?
- Could duplicate execution cause harm?
- Is an idempotency key needed?
- What happens if the result is unknown?
- Could concurrent requests conflict?
- Is the endpoint backward-compatible with existing consumers?
