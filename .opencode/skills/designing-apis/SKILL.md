---
name: designing-apis
description: Design production-ready REST or GraphQL APIs from business requirements, business operations, business rules, state machines, domain models, ERDs, and existing contracts. Use when designing new APIs, reviewing or evolving existing APIs, defining module or service boundaries, creating API contracts or OpenAPI specifications, or deciding resources, endpoints, schemas, authentication, authorization, validation, errors, pagination, filtering, idempotency, concurrency, versioning, and cross-domain workflows.
---

# Designing APIs

Design APIs as stable business contracts, not as CRUD wrappers around database tables.

The goal is to expose business capabilities through interfaces that are:
- correct with respect to business rules
- hard to misuse
- predictable for consumers
- secure
- resilient to retries and failures
- backward-compatible where possible
- understandable and evolvable

This skill applies primarily to REST and GraphQL APIs, and also to public/internal module or service interfaces where the same contract principles matter.

## Core Mental Model

Always reason in this direction:

Business Operations
        ↓
Business Rules
        ↓
State Machines
        ↓
Glossary / Domain Language
        ↓
Domain Model
        ↓
ERD / Persistence Model
        ↓
API Resources + Operations
        ↓
API Contract
        ↓
Request / Response
        ↓
Validation / Security / Reliability
        ↓
OpenAPI or GraphQL Schema
        ↓
Contract Validation

Do NOT start with database tables and automatically create CRUD endpoints.

A database entity is not necessarily an API resource.

A business operation is not necessarily CRUD.

The API is the boundary between clients and the business domain.

## Phase 0 — Understand the System

Before designing endpoints, inspect available project documentation.

Prefer this reading order:

1. business operations
2. business rules
3. state machines
4. glossary
5. domain model
6. ERD
7. actors / roles / permissions
8. existing API documentation or schemas
9. existing consumers/tests when available

Use project documentation as the primary source of truth.

Understand:
- actors and their capabilities
- important business operations
- preconditions
- business rules
- entity lifecycles
- valid state transitions
- canonical terminology
- domain boundaries
- persistence relationships
- cross-domain dependencies
- external integrations
- existing API commitments

Do not invent unsupported business behavior.

When information is missing, state the assumption explicitly.

## Phase 1 — Business Operation Inventory

Before defining URLs, inventory the business operations.

For every operation capture:

- Operation
- Actor
- Domain owner
- Preconditions
- Business rules
- State transition
- Read/write behavior
- Dependencies
- External effects
- Retry/idempotency requirements
- Authorization requirements

Example:

Operation:
Cancel Order

Actor:
Customer

Domain:
Order

Preconditions:
Order exists.
Order belongs to customer.

Business Rules:
Only cancellable orders can be cancelled.

State Transition:
CONFIRMED → CANCELLED

Dependencies:
Payment

External Effects:
Potential refund.
Notification.

Do not lose business operations simply because they do not look like CRUD.

## Phase 2 — Derive API Operations

For every business operation, decide how it should be represented by the API.

### Resource-oriented CRUD

Use ordinary resource operations when the behavior is naturally CRUD:

POST   /orders
GET    /orders/{orderId}
PATCH  /orders/{orderId}
DELETE /orders/{orderId}

### Business actions / commands

Use an explicit action when the operation represents meaningful business intent, a controlled state transition, or domain-specific behavior.

Examples:

POST /orders/{orderId}/cancel
POST /orders/{orderId}/pay
POST /events/{eventId}/publish
POST /users/{userId}/reset-password

Do not force meaningful business commands into arbitrary PATCH operations.

Do not create action endpoints for trivial field changes.

The decision must follow business semantics.

## Resource Design

Identify resources from the domain and client perspective, not directly from database tables.

Consider:
- identity
- lifecycle
- ownership
- business meaning
- relationships
- client usage
- authorization boundary

A database table such as:

event_ticket_type

does not automatically imply:

/event-ticket-types

It may instead be modeled as:

/events/{eventId}/ticket-types

if the domain treats ticket types as resources managed within an event.

## Endpoint Structure

Prefer predictable resource-oriented URLs.

Use nouns for ordinary resources:

GET    /users
GET    /users/{userId}
POST   /users
PATCH  /users/{userId}
DELETE /users/{userId}

Avoid RPC-style CRUD endpoints:

GET  /getUsers
POST /createUser
POST /deleteUser

Meaningful business commands are allowed:

POST /orders/{orderId}/cancel
POST /orders/{orderId}/confirm
POST /orders/{orderId}/pay

Principle:

Use resource-oriented URLs by default, but model meaningful domain commands explicitly when they represent business intent or controlled state transitions.

## HTTP Methods

Use HTTP methods according to their semantics.

GET:
Retrieve data.

POST:
Create a resource or execute a non-idempotent business action.

PUT:
Replace a resource when full replacement semantics are appropriate.

PATCH:
Partially modify a resource.

DELETE:
Delete a resource when deletion is genuinely supported by the business.

Do not automatically expose every HTTP method for every resource.

If a field update represents a meaningful business operation, prefer an explicit command.

## Relationships

Represent relationships clearly.

Examples:

GET  /events/{eventId}/ticket-types
GET  /events/{eventId}/bookings
GET  /users/{userId}/orders

Use nesting when the child has meaningful ownership/context under the parent.

Avoid excessive nesting.

Do not create URLs such as:

/events/{eventId}/ticket-types/{ticketTypeId}/bookings/{bookingId}/payments/{paymentId}

when the final resource has its own identity.

Prefer:

/payments/{paymentId}

## Request Design

Define:
- path parameters
- query parameters
- headers
- request body
- required fields
- optional fields
- nullable fields
- defaults
- enums
- formats
- validation constraints
- client-generated vs server-generated fields
- immutable vs mutable fields

Keep input and output contracts separate.

Prefer:

CreateOrderRequest
UpdateOrderRequest
OrderResponse

rather than one generic DTO for every purpose.

Do not expose persistence fields merely because they exist in the database.

## Response Design

Define response schemas explicitly.

A project may use an envelope such as:

{
  "data": {
    "id": "123",
    "name": "Example"
  }
}

For collections:

{
  "data": [],
  "meta": {
    "page": 1,
    "pageSize": 20,
    "total": 100
  }
}

The exact envelope is a project convention, not a REST requirement.

Maintain one consistent response strategy.

Never blindly serialize database entities.

Avoid exposing:
- internal implementation details
- unnecessary internal identifiers
- secrets
- passwords
- sensitive data
- internal exception details

## Error Design

Use one consistent error strategy.

Recommended structure:

{
  "error": {
    "code": "ORDER_NOT_CANCELLABLE",
    "message": "The order cannot be cancelled in its current state.",
    "details": {}
  }
}

Requirements:
- machine-readable error code
- human-readable message
- optional structured details
- consistent shape across endpoints

Do not mix:
- thrown errors
- null responses
- arbitrary error objects
- different error envelopes

Consumers should be able to predict error behavior.

## HTTP Status Codes

Use status codes consistently.

200 — Successful request
201 — Resource created
202 — Accepted for asynchronous processing
204 — Successful request with no response body
400 — Invalid request
401 — Authentication missing or invalid
403 — Authenticated but not authorized
404 — Resource not found
409 — Conflict with current state, uniqueness, or concurrency
422 — Syntactically valid but semantically invalid request
429 — Rate limit exceeded
500 — Unexpected server error
503 — Service temporarily unavailable

Choose 400 vs 422 according to project conventions and semantics.

Do not return HTTP 200 with success=false for errors that have appropriate HTTP semantics.

## Validate at Boundaries

Validate untrusted data at system boundaries.

Validate:
- API input
- frontend form input
- third-party API responses
- webhook payloads
- message/event payloads from external systems
- environment/configuration input

After validation, internal code should be able to rely on established contracts.

Do not scatter redundant validation throughout trusted internal functions without a reason.

Third-party responses are untrusted data. Validate their shape and content before using them in business logic.

Persistence data from your own database is generally trusted with respect to schema, but still enforce domain invariants where required.

## Authentication

Authentication answers:

Who are you?

Authorization answers:

What are you allowed to do?

Possible mechanisms:
- JWT
- session authentication
- API keys
- OAuth 2.0
- OpenID Connect

Do not assume every endpoint is authenticated.

Explicitly classify endpoints as:
- Public
- Authenticated
- Role-restricted
- Permission-restricted
- Owner-only
- Internal/system

## Authorization

For every protected operation determine:
- required role
- required permission
- ownership rules
- resource-level access
- tenant/organization boundary
- special business rules

Example:

GET /orders/{orderId}

Authentication:
Required

Authorization:
Customer can access own order.
Admin can access all orders.

Authorization must be enforced server-side.

A hidden frontend button is not authorization.

## Business Rules

Business rules must survive translation into the API contract.

Example:

Only CONFIRMED orders can be cancelled.

Prefer:

POST /orders/{orderId}/cancel

with a state conflict such as:

409 Conflict

over arbitrary status mutation:

PATCH /orders/{orderId}
{
  "status": "CANCELLED"
}

when cancellation is a meaningful business operation.

## State Machines

For every stateful domain object identify:
- states
- transitions
- actors
- conditions
- side effects

Example:

PENDING
   ↓ confirm
CONFIRMED
   ↓ cancel
CANCELLED

Meaningful transitions may become explicit commands:

POST /orders/{id}/confirm
POST /orders/{id}/cancel

Do not expose arbitrary status mutation when the domain requires controlled transitions.

## Pagination

Paginate collection endpoints that may grow.

Offset example:

GET /products?page=1&pageSize=20

Cursor example:

GET /products?cursor=abc123&limit=20

Offset pagination is often suitable for:
- administrative screens
- simple datasets
- direct page navigation

Cursor pagination is often preferable for:
- large datasets
- frequently changing data
- stable traversal
- high-scale feeds

Do not make pagination semantics ambiguous.

## Filtering

Use predictable query parameters.

Examples:

GET /products?category=electronics
GET /orders?status=CONFIRMED
GET /events?startDate=2026-01-01

Keep filtering semantics consistent.

## Sorting

Use one project-wide convention.

Example:

GET /products?sort=createdAt
GET /products?sort=-createdAt

or:

GET /products?sortBy=createdAt&sortDirection=DESC

Do not mix conventions between domains.

## Search

Distinguish structured filtering from free-text search.

Filtering:

GET /products?category=BOOKS

Search:

GET /products?q=clean+code

Do not overload unrelated parameters.

## Idempotency

Treat idempotency as a full contract, not merely accepting an Idempotency-Key header.

Consider idempotency for:
- payments
- booking
- order creation
- resource creation with costly effects
- external API calls
- state-changing commands
- message/event publishing

### Key semantics

The key represents one intent, not one attempt.

Good:
Idempotency-Key: client-generated stable key reused across retries.

Potentially good when the domain has a stable immutable operation identifier:
charge:v1:{orderId}

Bad:
A new UUID generated for every retry.

Bad:
A timestamp generated per attempt.

Bad:
A deterministic key that causes two legitimate distinct operations to collapse into one.

The key should come from the client or initiating event, not from the layer performing retries.

### Atomic claiming

Do not implement:

check key exists
→ perform effect
→ insert key

That is a race.

Prefer an atomic claim backed by a unique constraint:

1. insert key with IN_PROGRESS state
2. unique constraint determines the winner
3. execute the effect
4. persist the result
5. replay or reject duplicates

### Payload binding

Bind the idempotency key to the request intent.

If the same key is reused with a different payload, fail loudly rather than silently returning the result of another request.

### In-flight duplicates

Decide deliberately what a duplicate gets while the first request is still running.

Possible strategies:
- 409 Conflict
- bounded wait for the original result
- 202 Accepted with a status resource

Do not let the duplicate execute the effect simply because the first request appears stuck.

### Unknown outcome

A timeout does not necessarily mean failure.

For external effects, there are three meaningful outcomes:

- success
- failure
- unknown

A request can time out after the external system has already applied the effect.

Design retries around this reality.

Record enough intent/state before external calls to recover from ambiguous outcomes.

### Retention

Idempotency records must survive the longest path that can re-deliver the same intent.

Consider:
- client retry windows
- job retries
- queue redelivery
- dead-letter queue replay
- scheduled reconciliation
- provider recovery/dispute flows

A TTL shorter than the longest replay path can reintroduce duplicates.

## Concurrency

Consider race conditions for state-changing operations.

Examples:
- two users buy the last ticket
- two requests cancel the same order
- two admins update the same resource
- two workers process the same message

Potential mechanisms:
- optimistic locking
- version fields
- conditional requests
- database constraints
- atomic state transitions
- idempotent processing

API validation alone does not prevent concurrent conflicts.

## Cross-Domain Workflows

After designing individual domains, review complete business workflows.

Example:

User
 ↓
Authentication
 ↓
Event
 ↓
Ticket Type
 ↓
Booking
 ↓
Payment
 ↓
Notification

Ask:
- Which domain owns the operation?
- Which domains participate?
- Where is the transaction boundary?
- Which calls are synchronous?
- Which effects are asynchronous?
- What happens when an external system fails?
- Can the operation be safely retried?
- What happens if payment succeeds but the response is lost?
- Which state transition happens first?
- Which events need to be published?
- Where is eventual consistency acceptable?

Do not design endpoints in isolation.

## External Integrations

For payment providers, email providers, message brokers, third-party APIs, and webhooks consider:
- timeouts
- retries
- idempotency
- duplicate callbacks
- authentication/verification
- failure states
- asynchronous processing
- uncertain outcomes
- reconciliation

Never assume an external call is an atomic transaction with your local database.

## Messaging and At-Least-Once Delivery

Do not assume a queue provides exactly-once business effects.

A consumer can crash after performing a side effect but before acknowledging the message.

Therefore, design message consumers for at-least-once delivery:
- make processing idempotent
- use unique business/event identifiers
- persist processing state where needed
- protect side effects with uniqueness or idempotency mechanisms

If a workflow requires reliable DB + event publication, consider an appropriate transactional messaging pattern such as an outbox.

Do not claim that a broker's delivery semantics alone guarantee exactly-once business behavior.

## API Evolution and Stability

Treat a public API as a long-lived contract.

### Hyrum's Law

With enough consumers, every observable behavior may become depended upon, whether or not it is documented.

Therefore:
- expose only intentional behavior
- avoid leaking implementation details
- treat response shapes, ordering, error behavior, and other observable behavior as potentially contractual
- plan deprecation before introducing something that may need removal

Tests alone do not prove that a change is safe for all consumers.

### Prefer Addition Over Modification

Prefer backward-compatible extension.

Generally safer:
- add a new optional response field
- add a new optional request field
- add a new endpoint
- add a new capability without changing existing semantics

Potentially breaking:
- remove a field
- change field type
- change meaning of an existing field
- make an optional field required
- change enum semantics unexpectedly
- change ordering or filtering semantics relied upon by consumers

Do not treat "it was undocumented" as proof that consumers do not depend on it.

## Versioning

Prefer backward-compatible evolution before introducing multiple API versions.

When a change is proposed, classify it as:
- additive
- backward compatible
- potentially breaking
- breaking

Only introduce a new version when there is a real compatibility need.

If versioning is required, use one project-wide strategy, for example:

/api/v1/users
/api/v1/orders

Do not maintain multiple versions casually; version proliferation multiplies testing, documentation, operational, and maintenance cost.

Plan deprecation and migration when retiring a contract.

## Internal APIs and Module Interfaces

Internal consumers are still consumers.

For internal APIs, services, and module boundaries:
- define explicit contracts
- avoid leaking implementation details
- document assumptions
- keep boundaries stable
- avoid unnecessary coupling
- allow consumers to evolve independently

The same design principles apply to:
- REST APIs
- GraphQL schemas
- service-to-service APIs
- application module interfaces
- frontend/backend contracts

## GraphQL

When designing GraphQL:
- model domain concepts rather than database tables
- define explicit input and output types
- use mutations for state-changing operations
- design authorization at field/resolver/domain boundaries
- avoid exposing arbitrary database structure
- prevent unbounded queries
- consider pagination for large collections
- consider query complexity/depth limits where appropriate
- keep errors predictable
- treat schema evolution as a compatibility problem

GraphQL changes should follow the same business-first and stability principles as REST.

## Naming Conventions

Use project-wide conventions.

Typical examples:

REST:
- plural nouns
- no verbs for ordinary CRUD

Query parameters:
- camelCase

JSON:
- camelCase

Boolean fields:
- is/has/can prefix where appropriate

Enums:
- consistent project convention, often UPPER_SNAKE_CASE

The existing project's established conventions take precedence.

## Security Checklist

Verify:
- authentication is correctly defined
- authorization is enforced server-side
- object-level authorization is considered
- tenant isolation is enforced where applicable
- input is validated at boundaries
- third-party responses are validated
- sensitive data is not exposed
- passwords/secrets are never returned
- error messages do not leak internals
- rate limiting is considered
- replay/duplicate requests are considered
- idempotency is considered for sensitive operations
- CORS is intentionally configured
- webhooks are authenticated/verified
- secrets are never placed in URLs

Avoid sensitive information in query parameters because URLs may be logged.

## API Design Matrix

Before writing OpenAPI, create an API Design Matrix.

Use:

references/api-design-matrix.md

The matrix connects business operations to API operations and makes gaps visible before implementation.

## OpenAPI

Generate OpenAPI only after the business and API design has been validated.

Use:

references/openapi-template.yaml

Use reusable components and $ref.

Group endpoints using tags.

The OpenAPI document is the API contract, not merely generated documentation.

## Existing API Review

When reviewing an existing API, evaluate it in this order:

Business correctness
        ↓
Domain correctness
        ↓
State transition correctness
        ↓
Authorization correctness
        ↓
Cross-domain correctness
        ↓
Reliability / idempotency / concurrency
        ↓
Backward compatibility
        ↓
HTTP semantics
        ↓
Contract consistency
        ↓
Naming/style

A beautiful REST API is still bad if it violates business rules or cannot safely handle retries and evolution.

## Anti-Patterns

Avoid:

### Database → CRUD → API

Do not map every table directly to an endpoint.

### Every Field Is PATCHable

Do not expose state-changing business operations as arbitrary field updates when the domain requires controlled transitions.

### RPC Everywhere

Do not use verbs for ordinary CRUD.

### Generic HTTP 200 for Errors

Do not hide HTTP error semantics inside success envelopes.

### Authorization Only in Frontend

Frontend controls are not security boundaries.

### Direct Entity Serialization

Do not blindly expose persistence entities.

### Validation Everywhere

Do not duplicate boundary validation throughout trusted internal code without reason.

### Check-Then-Act Idempotency

A SELECT followed by an INSERT is not an atomic idempotency guard.

### Per-Attempt Idempotency Keys

A new key for every retry defeats idempotency.

### Same Key, Different Payload

Do not silently replay the first result for a different request body.

### Assuming Timeout Means Failure

An unknown external outcome must not automatically trigger a duplicate side effect.

### Assuming Exactly-Once Delivery

Broker acknowledgment does not make the business side effect exactly once.

### Ignoring Existing Consumers

An undocumented observable behavior may already be part of someone's dependency.

### Designing Domains Independently

Do not design Order, Payment, Notification, etc. without reviewing the complete business workflow.

## Verification Checklist

Before finalizing an API, verify:

### Business
- [ ] Every important business operation has an API representation
- [ ] Business rules are preserved
- [ ] Preconditions are understood
- [ ] State transitions are correct
- [ ] Actors are identified

### Domain
- [ ] API resources are meaningful from the client/domain perspective
- [ ] Resources are not merely database tables
- [ ] Relationships are appropriate
- [ ] Cross-domain ownership is clear

### Contract
- [ ] Every endpoint has explicit input and output schemas
- [ ] Input and output models are appropriately separated
- [ ] Error format is consistent
- [ ] Status codes are consistent
- [ ] Pagination is defined where needed
- [ ] Filtering and sorting are defined where needed
- [ ] Naming is consistent

### Security
- [ ] Authentication is explicit
- [ ] Authorization is explicit
- [ ] Ownership checks are explicit
- [ ] Boundary validation exists
- [ ] Sensitive data is protected

### Reliability
- [ ] Retry behavior is considered
- [ ] Idempotency is considered where needed
- [ ] Idempotency keys represent intent
- [ ] Idempotency claiming is atomic
- [ ] Reused keys with different payloads are rejected
- [ ] In-flight duplicates have deliberate behavior
- [ ] Unknown external outcomes are handled
- [ ] Concurrency is considered
- [ ] Message processing is safe under at-least-once delivery

### Evolution
- [ ] Existing observable behavior is treated as potentially contractual
- [ ] Breaking changes are identified
- [ ] Additive evolution is preferred
- [ ] Deprecation is planned when needed
- [ ] Versioning strategy is consistent

### Architecture
- [ ] Transaction boundaries are reasonable
- [ ] External integrations are modeled realistically
- [ ] Async effects are identified
- [ ] Cross-domain workflows are reviewed
- [ ] Event publication/reliability requirements are understood

## Expected Output

When asked to design an API for a project, produce:

### 1. System Understanding
- domains
- actors
- important operations
- important state machines
- major dependencies

### 2. Business Operation Inventory
For each operation:
- actor
- domain
- preconditions
- rules
- state transition
- dependencies
- external effects

### 3. API Design Matrix
- operation
- actor
- domain
- method
- endpoint
- authentication
- authorization
- state transition
- dependencies

### 4. Domain API Design
For each domain:
- resources
- relationships
- CRUD operations
- business actions
- authorization
- validation
- errors
- pagination/filtering/sorting

### 5. Cross-Domain Workflows
Explain important workflows, including:
- transaction boundaries
- synchronous/asynchronous behavior
- failure paths
- retry behavior
- consistency model
- external effects

### 6. API Contract
For every endpoint:
- method
- path
- purpose
- authentication
- authorization
- path parameters
- query parameters
- headers
- request body
- response body
- status codes
- errors
- validation
- idempotency
- concurrency considerations where relevant

### 7. Evolution Review
Identify:
- compatibility risks
- breaking changes
- extension opportunities
- deprecation requirements

### 8. OpenAPI
Generate the final OpenAPI specification only after the design has been validated.

## Final Rule

Do not optimize for:

"How do I make this API look RESTful?"

Optimize for:

"How do I expose the system's business capabilities as a clear, secure, reliable, evolvable API contract?"

Business requirements define what the API must support.
Domain rules define what the API is allowed to do.
State machines define valid transitions.
The persistence model supports storage.
The API contract defines how consumers interact with the system.
Reliability rules define how it behaves under retries, concurrency, and failure.
Compatibility principles define how it evolves.
