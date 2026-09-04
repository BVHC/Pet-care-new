const SQ_DIAGRAMS = {
      sq1: {
        description: "Sequence: Customer registers → OTP sent → Verify → Account Active",
        participants: [
          { name: "Customer", system: false },
          { name: "Frontend", system: true },
          { name: "Backend API", system: true },
          { name: "OTP Service", system: true },
          { name: "Database", system: true }
        ],
        steps: [
          { from: 0, to: 1, label: "Register(phone,email)", type: "sync" },
          { from: 1, to: 2, label: "SendOTPRequest()", type: "sync" },
          { from: 2, to: 3, label: "Generate OTP (TTL=5m)", type: "sync" },
          { from: 3, to: 4, label: "Save OTP record", type: "sync" },
          { from: 3, to: 0, label: "SMS/Email OTP", type: "async" },
          { from: 0, to: 1, label: "Submit OTP code", type: "sync" },
          { from: 1, to: 2, label: "VerifyOTP(code)", type: "sync" },
          { from: 2, to: 4, label: "Check OTP & attempts", type: "sync" },
          { from: 2, to: 4, label: "Account.status=ACTIVE", type: "sync" },
          { from: 2, to: 1, label: "Success + JWT Token", type: "return" }
        ]
      },
      sq2: {
        description: "Sequence: HoldSlot (15m TTL) → BookAppointment → Confirm → Resource Lock",
        participants: [
          { name: "Customer", system: false },
          { name: "Store App", system: true },
          { name: "Scheduling API", system: true },
          { name: "Slot Service", system: true },
          { name: "Inventory", system: true }
        ],
        steps: [
          { from: 0, to: 1, label: "Select time slot", type: "sync" },
          { from: 1, to: 2, label: "HoldSlot(slotId, 15m)", type: "sync" },
          { from: 2, to: 3, label: "Lock slot, set TTL=900s", type: "sync" },
          { from: 2, to: 0, label: "Hold confirmed", type: "return" },
          { from: 0, to: 1, label: "Confirm booking", type: "sync" },
          { from: 1, to: 2, label: "BookAppointment()", type: "sync" },
          { from: 2, to: 3, label: "Check availability", type: "sync" },
          { from: 2, to: 4, label: "Lock resources", type: "sync" },
          { from: 2, to: 3, label: "Appointment→BOOKED", type: "sync" },
          { from: 2, to: 0, label: "Booking confirmed", type: "return" }
        ]
      },
      sq4: {
        description: "Sequence: Checkout → Payment Gateway → Webhook → Payment Success",
        participants: [
          { name: "Customer", system: false },
          { name: "Store App", system: true },
          { name: "Order Service", system: true },
          { name: "Payment Gateway", system: true },
          { name: "Webhook Handler", system: true }
        ],
        steps: [
          { from: 0, to: 1, label: "Checkout(cartId)", type: "sync" },
          { from: 1, to: 2, label: "CreatePayment(intent)", type: "sync" },
          { from: 2, to: 3, label: "Initialize payment", type: "sync" },
          { from: 2, to: 0, label: "Redirect to Gateway", type: "async" },
          { from: 0, to: 3, label: "Enter card details", type: "sync" },
          { from: 3, to: 0, label: "Payment result", type: "async" },
          { from: 3, to: 4, label: "Webhook callback", type: "async" },
          { from: 4, to: 2, label: "Verify signature", type: "sync" },
          { from: 4, to: 2, label: "Payment.status=SUCCESS", type: "sync" },
          { from: 2, to: 1, label: "PaymentSucceeded event", type: "async" },
          { from: 1, to: 0, label: "Order confirmed", type: "return" }
        ]
      },
      sq7: {
        description: "Sequence: Stock Transfer → Approve → Ship → In Transit → Receive → Discrepancy",
        participants: [
          { name: "Inventory Staff", system: false },
          { name: "Store App", system: true },
          { name: "Transfer Service", system: true },
          { name: "Store Manager", system: false },
          { name: "Destination Store", system: true }
        ],
        steps: [
          { from: 0, to: 1, label: "CreateStockTransfer()", type: "sync" },
          { from: 1, to: 2, label: "Transfer → REQUESTED", type: "sync" },
          { from: 2, to: 3, label: "Notify Manager", type: "async" },
          { from: 3, to: 1, label: "ApproveTransfer()", type: "sync" },
          { from: 1, to: 2, label: "Transfer → APPROVED", type: "sync" },
          { from: 0, to: 1, label: "ShipStock()", type: "sync" },
          { from: 1, to: 2, label: "IN_TRANSIT, deduct source", type: "sync" },
          { from: 2, to: 4, label: "Shipment arrives", type: "async" },
          { from: 4, to: 1, label: "ReceiveWithDiscrepancy()", type: "sync" },
          { from: 1, to: 2, label: "DISCREPANCY_RECORDED", type: "sync" },
          { from: 2, to: 3, label: "ResolveDiscrepancy()", type: "sync" },
          { from: 1, to: 2, label: "RECEIVED", type: "sync" }
        ]
      },
      sq3: {
        description: "Sequence: Walk-in → Register Queue → Call → Start Service → Complete",
        participants: [
          { name: "Customer", system: false },
          { name: "Receptionist", system: false },
          { name: "Queue Service", system: true },
          { name: "Staff", system: false },
          { name: "System", system: true }
        ],
        steps: [
          { from: 0, to: 1, label: "Walk-in request", type: "sync" },
          { from: 1, to: 2, label: "RegisterQueueEntry()", type: "sync" },
          { from: 2, to: 3, label: "Assign queue number", type: "sync" },
          { from: 2, to: 0, label: "Queue ticket", type: "return" },
          { from: 1, to: 2, label: "CallQueueEntry()", type: "sync" },
          { from: 2, to: 0, label: "SMS: Your turn!", type: "async" },
          { from: 3, to: 1, label: "StartQueueService()", type: "sync" },
          { from: 1, to: 2, label: "Create internal Appointment", type: "sync" },
          { from: 2, to: 3, label: "Service IN_PROGRESS", type: "sync" },
          { from: 3, to: 1, label: "CompleteQueueEntry()", type: "sync" },
          { from: 1, to: 2, label: "Complete", type: "sync" }
        ]
      },
      sq5: {
        description: "Sequence: Cross-Store EMR Access → OTP Consent → 24h Access",
        participants: [
          { name: "Vet (Other Store)", system: false },
          { name: "Store App", system: true },
          { name: "EMR Service", system: true },
          { name: "Customer", system: false },
          { name: "OTP Service", system: true }
        ],
        steps: [
          { from: 0, to: 1, label: "RequestCrossStoreConsent()", type: "sync" },
          { from: 1, to: 2, label: "Validate request", type: "sync" },
          { from: 2, to: 3, label: "OTP request notification", type: "async" },
          { from: 3, to: 4, label: "OTP TTL=5m", type: "sync" },
          { from: 4, to: 3, label: "SMS OTP", type: "async" },
          { from: 3, to: 0, label: "Submit OTP", type: "sync" },
          { from: 0, to: 1, label: "VerifyOTP()", type: "sync" },
          { from: 1, to: 2, label: "Grant access 24h", type: "sync" },
          { from: 2, to: 0, label: "EMR data access", type: "return" }
        ]
      },
      sq6: {
        description: "Sequence: Grooming + Surcharge Invoice (D-02) → Independent Invoice",
        participants: [
          { name: "Groomer", system: false },
          { name: "Grooming App", system: true },
          { name: "Grooming Service", system: true },
          { name: "Customer", system: false },
          { name: "Invoice Service", system: true }
        ],
        steps: [
          { from: 0, to: 1, label: "Start Grooming Session", type: "sync" },
          { from: 1, to: 2, label: "Session IN_PROGRESS", type: "sync" },
          { from: 0, to: 1, label: "AddGroomingService()", type: "sync" },
          { from: 1, to: 2, label: "AWAITING_APPROVAL", type: "sync" },
          { from: 2, to: 3, label: "Approve request?", type: "async" },
          { from: 3, to: 1, label: "ConfirmAdditionalService()", type: "sync" },
          { from: 1, to: 4, label: "CreateSurchargeInvoice()", type: "sync" },
          { from: 4, to: 3, label: "Invoice DRAFT", type: "sync" },
          { from: 1, to: 2, label: "IN_PROGRESS", type: "sync" },
          { from: 0, to: 1, label: "CompleteGrooming()", type: "sync" },
          { from: 1, to: 2, label: "COMPLETED", type: "sync" }
        ]
      },
      sq8: {
        description: "Sequence: Vaccination → Scan Barcode → Safety Check → Administer → Schedule Next",
        participants: [
          { name: "Vet", system: false },
          { name: "Vaccine App", system: true },
          { name: "Vaccine Service", system: true },
          { name: "Inventory", system: true }
        ],
        steps: [
          { from: 0, to: 1, label: "Scan vaccine barcode", type: "sync" },
          { from: 1, to: 2, label: "Validate batch", type: "sync" },
          { from: 2, to: 3, label: "Check: expiry, qty, status", type: "sync" },
          { from: 3, to: 1, label: "Batch OK? EXPIRED?", type: "return" },
          { from: 0, to: 1, label: "AdministerVaccine()", type: "sync" },
          { from: 1, to: 2, label: "Record vaccination", type: "sync" },
          { from: 1, to: 3, label: "Deduct from batch qty", type: "sync" },
          { from: 1, to: 2, label: "ScheduleNextVaccination()", type: "sync" },
          { from: 2, to: 0, label: "Reminder set", type: "return" }
        ]
      },
      sq9: {
        description: "Sequence: Staff Onboarding D-04 → CreateStaff → Active → First Login → Change Password",
        participants: [
          { name: "Platform Admin", system: false },
          { name: "Admin Portal", system: true },
          { name: "Auth Service", system: true },
          { name: "Staff", system: false },
          { name: "Email/SMS", system: true }
        ],
        steps: [
          { from: 0, to: 1, label: "CreateStaff()", type: "sync" },
          { from: 1, to: 2, label: "Create account ACTIVE", type: "sync" },
          { from: 1, to: 2, label: "Set must_change=true", type: "sync" },
          { from: 1, to: 4, label: "Send temp password", type: "async" },
          { from: 4, to: 0, label: "Temp credentials", type: "return" },
          { from: 3, to: 2, label: "First Login", type: "sync" },
          { from: 2, to: 3, label: "Force ChangePassword()", type: "sync" },
          { from: 2, to: 3, label: "must_change=false", type: "sync" },
          { from: 2, to: 3, label: "Issue JWT", type: "return" }
        ]
      },
      sq10: {
        description: "Sequence: Emergency Override → Abort → ClinicalIncident → Notification",
        participants: [
          { name: "Vet", system: false },
          { name: "Clinic App", system: true },
          { name: "Appointment Service", system: true },
          { name: "Incident Service", system: true },
          { name: "Notification Service", system: true }
        ],
        steps: [
          { from: 0, to: 1, label: "Emergency detected!", type: "sync" },
          { from: 1, to: 2, label: "AbortAppointment()", type: "sync" },
          { from: 2, to: 3, label: "CreateClinicalIncident()", type: "sync" },
          { from: 2, to: 3, label: "CRITICAL severity", type: "sync" },
          { from: 2, to: 0, label: "ABORTED", type: "return" },
          { from: 3, to: 4, label: "SendIncidentNotification()", type: "async" },
          { from: 4, to: 0, label: "Alert Customer + Manager", type: "async" },
          { from: 3, to: 1, label: "Record audit log", type: "sync" }
        ]
      },
      sq11: {
        description: "Sequence: Checkout → Payment Intent → Gateway → Webhook → Confirm Order (UC12a)",
        participants: [
          { name: "Customer", system: false },
          { name: "Store App", system: true },
          { name: "Order Service", system: true },
          { name: "Payment Gateway", system: true },
          { name: "Webhook Handler", system: true }
        ],
        steps: [
          { from: 0, to: 1, label: "View cart & checkout", type: "sync" },
          { from: 1, to: 2, label: "ValidateCart()", type: "sync" },
          { from: 2, to: 1, label: "Cart validated", type: "return" },
          { from: 0, to: 1, label: "Select shipping address", type: "sync" },
          { from: 1, to: 2, label: "SaveShippingAddress()", type: "sync" },
          { from: 0, to: 1, label: "Choose payment method", type: "sync" },
          { from: 1, to: 2, label: "CreatePaymentIntent(cart)", type: "sync" },
          { from: 2, to: 3, label: "POST /intents", type: "sync" },
          { from: 3, to: 2, label: "client_secret + intent_id", type: "return" },
          { from: 2, to: 1, label: "Return redirect URL", type: "return" },
          { from: 0, to: 3, label: "Redirect to Gateway", type: "async" },
          { from: 3, to: 0, label: "Card form / QR code", type: "async" },
          { from: 3, to: 4, label: "Webhook: payment.succeeded", type: "async" },
          { from: 4, to: 2, label: "VerifySignature()", type: "sync" },
          { from: 4, to: 2, label: "Order.status=PAID", type: "sync" },
          { from: 2, to: 1, label: "PaymentSucceeded event", type: "async" },
          { from: 1, to: 0, label: "Order confirmed + receipt", type: "return" }
        ]
      },
      sq12: {
        description: "Sequence: Payment Failed → Webhook retry → Timeout → Order cancelled",
        participants: [
          { name: "Customer", system: false },
          { name: "Store App", system: true },
          { name: "Order Service", system: true },
          { name: "Payment Gateway", system: true },
          { name: "Webhook Handler", system: true }
        ],
        steps: [
          { from: 0, to: 1, label: "Initiate checkout", type: "sync" },
          { from: 1, to: 2, label: "CreatePaymentIntent()", type: "sync" },
          { from: 2, to: 3, label: "Initialize gateway session", type: "sync" },
          { from: 3, to: 0, label: "Redirect to Gateway", type: "async" },
          { from: 0, to: 3, label: "Enter card → Submit", type: "sync" },
          { from: 3, to: 4, label: "Webhook: payment.failed", type: "async" },
          { from: 4, to: 2, label: "Record failure reason", type: "sync" },
          { from: 4, to: 2, label: "Order.status=PAYMENT_FAILED", type: "sync" },
          { from: 2, to: 1, label: "PaymentFailed event", type: "async" },
          { from: 1, to: 0, label: "Show retry option", type: "return" },
          { from: 0, to: 1, label: "Retry / Cancel", type: "sync" },
          { from: 2, to: 2, label: "Schedule cancel job (TTL=30m)", type: "sync" },
          { from: 2, to: 2, label: "[TIMEOUT] CancelOrder()", type: "sync" },
          { from: 2, to: 1, label: "Order cancelled", type: "return" }
        ]
      }
    };

    // ============================================================
    // FSM STATE MACHINES
    // ============================================================

    // ============================================================
    // FSM STATE MACHINES
    // ============================================================
