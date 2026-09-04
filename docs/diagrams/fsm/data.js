const FSM_DIAGRAMS = {
      fsm1: {
        description: "FSM: Order Lifecycle (8 states) - PENDING → PAID → CONFIRMED → PROCESSING → READY → DELIVERED",
        states: [
          { id: "s1", name: "PENDING_PAYMENT", type: "initial" },
          { id: "s2", name: "PAID", focal: true },
          { id: "s3", name: "CONFIRMED" },
          { id: "s4", name: "PROCESSING" },
          { id: "s5", name: "READY" },
          { id: "s6", name: "DELIVERED", type: "final" },
          { id: "s7", name: "CANCELLED", type: "final" },
          { id: "s8", name: "REFUNDED", type: "final" }
        ],
        transitions: [
          { from: "s1", to: "s2", event: "PaymentSucceeded" },
          { from: "s1", to: "s7", event: "Cancel / Timeout" },
          { from: "s2", to: "s3", event: "ConfirmOrder" },
          { from: "s2", to: "s7", event: "CancelWithRefund" },
          { from: "s3", to: "s4", event: "ProcessOrder" },
          { from: "s4", to: "s5", event: "PrepareComplete" },
          { from: "s5", to: "s6", event: "Handover" },
          { from: "s6", to: "s8", event: "FullRefund" }
        ]
      },
      fsm2: {
        description: "FSM: Appointment Lifecycle (8 states) - BOOKED → CONFIRMED → CHECKED_IN → IN_PROGRESS → COMPLETED",
        states: [
          { id: "s1", name: "BOOKED", type: "initial" },
          { id: "s2", name: "CONFIRMED" },
          { id: "s3", name: "CHECKED_IN" },
          { id: "s4", name: "IN_PROGRESS", focal: true },
          { id: "s5", name: "COMPLETED", type: "final" },
          { id: "s6", name: "NO_SHOW", type: "final" },
          { id: "s7", name: "CANCELLED", type: "final" },
          { id: "s8", name: "ABORTED", type: "final" }
        ],
        transitions: [
          { from: "s1", to: "s2", event: "Confirm" },
          { from: "s1", to: "s7", event: "Cancel" },
          { from: "s1", to: "s6", event: "MarkNoShow" },
          { from: "s2", to: "s3", event: "CheckIn" },
          { from: "s2", to: "s7", event: "Cancel" },
          { from: "s3", to: "s4", event: "StartService" },
          { from: "s4", to: "s5", event: "Complete" },
          { from: "s4", to: "s8", event: "Abort" }
        ]
      },
      fsm3: {
        description: "FSM: Invoice Lifecycle (4 states) - DRAFT → ISSUED → PAID (Immutable D-01)",
        states: [
          { id: "s1", name: "DRAFT", type: "initial" },
          { id: "s2", name: "ISSUED", focal: true },
          { id: "s3", name: "PAID", type: "final", focal: true },
          { id: "s4", name: "VOID", type: "final" }
        ],
        transitions: [
          { from: "s1", to: "s2", event: "IssueInvoice" },
          { from: "s1", to: "s4", event: "Discard" },
          { from: "s2", to: "s3", event: "PaymentSucceeded", focal: true },
          { from: "s2", to: "s4", event: "VoidInvoice" }
        ]
      },
      fsm4: {
        description: "FSM: Grooming Session Lifecycle - WAITING → IN_PROGRESS → AWAITING_APPROVAL → COMPLETED",
        states: [
          { id: "s1", name: "WAITING", type: "initial" },
          { id: "s2", name: "IN_PROGRESS", focal: true },
          { id: "s3", name: "AWAITING_APPROVAL" },
          { id: "s4", name: "COMPLETED", type: "final" },
          { id: "s5", name: "ABORTED", type: "final" }
        ],
        transitions: [
          { from: "s1", to: "s2", event: "StartGrooming" },
          { from: "s2", to: "s3", event: "AddService" },
          { from: "s2", to: "s4", event: "Complete" },
          { from: "s2", to: "s5", event: "Abort" },
          { from: "s3", to: "s2", event: "ConfirmSurcharge" },
          { from: "s3", to: "s2", event: "RejectSurcharge" }
        ]
      },
      fsm5: {
        description: "FSM: Queue Entry Lifecycle - WAITING → CALLED → IN_SERVICE → COMPLETED",
        states: [
          { id: "s1", name: "WAITING", type: "initial" },
          { id: "s2", name: "CALLED" },
          { id: "s3", name: "IN_SERVICE", focal: true },
          { id: "s4", name: "COMPLETED", type: "final" },
          { id: "s5", name: "NO_SHOW", type: "final" },
          { id: "s6", name: "CANCELLED", type: "final" }
        ],
        transitions: [
          { from: "s1", to: "s2", event: "CallQueue" },
          { from: "s1", to: "s6", event: "Cancel" },
          { from: "s2", to: "s3", event: "StartService" },
          { from: "s2", to: "s5", event: "3x NoShow" },
          { from: "s2", to: "s6", event: "Cancel" },
          { from: "s3", to: "s4", event: "Complete" }
        ]
      },
      fsm6: {
        description: "FSM: Payment Lifecycle (6 states) - PENDING → PROCESSING → SUCCESS/REFUNDED",
        states: [
          { id: "s1", name: "PENDING", type: "initial" },
          { id: "s2", name: "PROCESSING" },
          { id: "s3", name: "SUCCESS", focal: true },
          { id: "s4", name: "FAILED", type: "final" },
          { id: "s5", name: "PARTIAL_REFUND", type: "final" },
          { id: "s6", name: "REFUNDED", type: "final" }
        ],
        transitions: [
          { from: "s1", to: "s2", event: "GatewayReceived" },
          { from: "s1", to: "s4", event: "Cancel" },
          { from: "s2", to: "s3", event: "VerifySuccess" },
          { from: "s2", to: "s4", event: "VerifyFailed" },
          { from: "s3", to: "s5", event: "PartialRefund" },
          { from: "s3", to: "s6", event: "FullRefund" }
        ]
      },
      fsm7: {
        description: "FSM: Stock Transfer Lifecycle - REQUESTED → APPROVED → SHIPPED → IN_TRANSIT → RECEIVED",
        states: [
          { id: "s1", name: "REQUESTED", type: "initial" },
          { id: "s2", name: "APPROVED" },
          { id: "s3", name: "SHIPPED" },
          { id: "s4", name: "IN_TRANSIT" },
          { id: "s5", name: "DISCREPANCY", focal: true },
          { id: "s6", name: "RECEIVED", type: "final" },
          { id: "s7", name: "CLOSED", type: "final" }
        ],
        transitions: [
          { from: "s1", to: "s2", event: "Approve" },
          { from: "s1", to: "s7", event: "Cancel" },
          { from: "s2", to: "s3", event: "Ship" },
          { from: "s3", to: "s4", event: "Depart" },
          { from: "s4", to: "s6", event: "Receive OK" },
          { from: "s4", to: "s5", event: "Discrepancy" },
          { from: "s5", to: "s6", event: "Resolve" }
        ]
      },
      fsm8: {
        description: "FSM: Store Lifecycle (5 states) - DRAFT → ACTIVE ↔ SUSPENDED → DEACTIVATED → ARCHIVED",
        states: [
          { id: "s1", name: "DRAFT", type: "initial" },
          { id: "s2", name: "ACTIVE", focal: true },
          { id: "s3", name: "SUSPENDED" },
          { id: "s4", name: "DEACTIVATED" },
          { id: "s5", name: "ARCHIVED", type: "final" }
        ],
        transitions: [
          { from: "s1", to: "s2", event: "Activate", focal: true },
          { from: "s2", to: "s3", event: "Suspend" },
          { from: "s3", to: "s2", event: "Reactivate" },
          { from: "s3", to: "s4", event: "Deactivate" },
          { from: "s4", to: "s5", event: "Archive" }
        ]
      },
      fsm9: {
        description: "FSM: Caregiver Delegation Lifecycle - INVITED → ACTIVE/EXPIRED → REVOKED",
        states: [
          { id: "s1", name: "INVITED", type: "initial" },
          { id: "s2", name: "ACTIVE", focal: true },
          { id: "s3", name: "EXPIRED", type: "final" },
          { id: "s4", name: "REVOKED", type: "final" },
          { id: "s5", name: "REJECTED", type: "final" }
        ],
        transitions: [
          { from: "s1", to: "s2", event: "Accept" },
          { from: "s1", to: "s5", event: "Reject" },
          { from: "s1", to: "s3", event: "TTL Expired" },
          { from: "s2", to: "s4", event: "Revoke" },
          { from: "s2", to: "s3", event: "EndDate Expired" }
        ]
      },
      fsm10: {
        description: "FSM: Order Checkout Lifecycle (9 states) — CART → PAID → CONFIRMED → PROCESSING → SHIPPED → DELIVERED / CANCELLED",
        states: [
          { id: "s1", name: "CART", type: "initial" },
          { id: "s2", name: "PENDING_PAYMENT" },
          { id: "s3", name: "PAID", focal: true },
          { id: "s4", name: "CONFIRMED" },
          { id: "s5", name: "PROCESSING" },
          { id: "s6", name: "SHIPPED" },
          { id: "s7", name: "DELIVERED", type: "final" },
          { id: "s8", name: "CANCELLED", type: "final" },
          { id: "s9", name: "REFUNDED", type: "final" }
        ],
        transitions: [
          { from: "s1", to: "s2", event: "InitiateCheckout", focal: true },
          { from: "s1", to: "s8", event: "AbandonCart" },
          { from: "s2", to: "s3", event: "PaymentSucceeded" },
          { from: "s2", to: "s8", event: "PaymentFailed / Timeout" },
          { from: "s3", to: "s4", event: "ConfirmOrder" },
          { from: "s3", to: "s8", event: "CancelWithRefund" },
          { from: "s4", to: "s5", event: "StartProcessing" },
          { from: "s4", to: "s8", event: "CancelOrder" },
          { from: "s5", to: "s6", event: "ShipOrder" },
          { from: "s6", to: "s7", event: "MarkDelivered" },
          { from: "s6", to: "s8", event: "ReturnRequested" },
          { from: "s7", to: "s9", event: "RequestRefund" },
          { from: "s8", to: "s9", event: "ProcessRefund" }
        ]
      }
    };

    // State Variables
