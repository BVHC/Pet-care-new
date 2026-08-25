# State Machine — Pet Care Ecosystem

Tài liệu này đặc tả toàn bộ các Finite State Machine (FSM), trạng thái (States), lệnh kích hoạt (Commands), tác nhân (Actors), điều kiện bảo vệ (Guards/RULE-ID) và sự kiện miền (Domain Events) trong hệ thống Pet Care Ecosystem.

---

## 1. Account — AccountStatus

```text
[*] --(RegisterAccount)--> PENDING_VERIFICATION
PENDING_VERIFICATION --(VerifyOTP)--> ACTIVE
ACTIVE --(LockAccount)--> LOCKED
LOCKED --(UnlockAccount)--> ACTIVE
```

| From | Command | Actor | Guard (RULE-ID) | To | Domain Event |
|---|---|---|---|---|---|
| [*] | RegisterAccount | Customer | RULE-01-01, RULE-01-02 | PENDING_VERIFICATION | AccountRegistered |
| PENDING_VERIFICATION | VerifyOTP | Customer | RULE-01-02, RULE-01-03 | ACTIVE | AccountActivated |
| ACTIVE | LockAccount | PlatformAdmin / OrganizationAdmin | RULE-02-04, RULE-02-05 | LOCKED | AccountLocked |
| LOCKED | UnlockAccount | PlatformAdmin / OrganizationAdmin | RULE-02-05 | ACTIVE | AccountUnlocked |

- **Initial:** PENDING_VERIFICATION (khi đăng ký mới), ACTIVE (khi do Admin khởi tạo trực tiếp)
- **Terminal:** Không có (tài khoản duy trì hoặc lưu trữ theo Privacy Policy)

---

## 2. Store — StoreStatus

```text
[*] --(CreateStore)--> ACTIVE
ACTIVE --(SuspendStore)--> SUSPENDED
SUSPENDED --(ActivateStore)--> ACTIVE
ACTIVE --(DeactivateStore)--> DEACTIVATED
DEACTIVATED --(ActivateStore)--> ACTIVE
DEACTIVATED --(ArchiveStore)--> ARCHIVED
SUSPENDED --(ArchiveStore)--> ARCHIVED
```

| From | Command | Actor | Guard (RULE-ID) | To | Domain Event |
|---|---|---|---|---|---|
| [*] | CreateStore | OrganizationAdmin | RULE-03-01 | ACTIVE | StoreCreated |
| ACTIVE | SuspendStore | OrganizationAdmin | RULE-03-03 | SUSPENDED | StoreSuspended |
| SUSPENDED | ActivateStore | OrganizationAdmin | RULE-03-02, RULE-03-03 | ACTIVE | StoreActivated |
| ACTIVE | DeactivateStore | OrganizationAdmin | RULE-03-03 | DEACTIVATED | StoreDeactivated |
| DEACTIVATED | ActivateStore | OrganizationAdmin | RULE-03-02, RULE-03-03 | ACTIVE | StoreActivated |
| DEACTIVATED | ArchiveStore | OrganizationAdmin | RULE-03-03 | ARCHIVED | StoreArchived |
| SUSPENDED | ArchiveStore | OrganizationAdmin | RULE-03-03 | ARCHIVED | StoreArchived |

- **Initial:** ACTIVE
- **Terminal:** ARCHIVED

---

## 3. CaregiverInvitation / Delegation — CaregiverStatus

> `Caregiver` là Actor. `CaregiverStatus` đặc tả vòng đời của lời mời và quan hệ ủy quyền (`CaregiverInvitation` / `PetCaregiverDelegation`).
> Theo quyết định nghiệp vụ đã duyệt: `AcceptCaregiverInvitation` kích hoạt trạng thái `ACTIVE` trực tiếp, không qua bước duyệt trung gian.

```text
[*] --(InviteCaregiver)--> INVITED
INVITED --(AcceptCaregiverInvitation)--> ACTIVE
INVITED --(RejectCaregiverInvitation)--> REJECTED
INVITED --(ProcessInvitationExpiry)--> EXPIRED
ACTIVE --(RevokeCaregiver)--> REVOKED
```

| From | Command | Actor | Guard (RULE-ID) | To | Domain Event |
|---|---|---|---|---|---|
| [*] | InviteCaregiver | Customer | RULE-04-01, RULE-04-04 | INVITED | CaregiverInvited |
| INVITED | AcceptCaregiverInvitation | Caregiver | RULE-04-02 | ACTIVE | CaregiverInvitationAccepted |
| INVITED | RejectCaregiverInvitation | Caregiver | — | REJECTED | CaregiverInvitationRejected |
| INVITED | ProcessInvitationExpiry | System | RULE-04-06 | EXPIRED | CaregiverInvitationExpired |
| ACTIVE | RevokeCaregiver | Customer | RULE-04-03, RULE-04-04 | REVOKED | CaregiverRevoked |

- **Initial:** INVITED
- **Terminal:** REJECTED, EXPIRED, REVOKED

---

## 4. Appointment — AppointmentStatus

```text
[*] --(BookAppointment)--> BOOKED
BOOKED --(ConfirmAppointment)--> CONFIRMED
BOOKED --(CheckInAppointment)--> CHECKED_IN
CONFIRMED --(CheckInAppointment)--> CHECKED_IN
BOOKED --(RescheduleAppointment)--> BOOKED
CONFIRMED --(RescheduleAppointment)--> BOOKED
CHECKED_IN --(StartAppointmentService)--> IN_PROGRESS
IN_PROGRESS --(CheckOutAppointment)--> COMPLETED
CHECKED_IN --(CheckOutAppointment)--> COMPLETED
BOOKED --(CancelAppointment)--> CANCELLED
CONFIRMED --(CancelAppointment)--> CANCELLED
BOOKED --(MarkNoShow)--> NO_SHOW
CONFIRMED --(MarkNoShow)--> NO_SHOW
```

| From | Command / Trigger | Actor | Guard (RULE-ID) | To | Domain Event |
|---|---|---|---|---|---|
| [*] | BookAppointment | Customer / Caregiver / Receptionist | RULE-06-01, RULE-06-02, RULE-06-03, RULE-06-04, RULE-06-07 | BOOKED | AppointmentBooked |
| BOOKED | ConfirmAppointment | Receptionist / System | RULE-06-01 | CONFIRMED | AppointmentConfirmed |
| BOOKED | CheckInAppointment | Receptionist | RULE-06-06 | CHECKED_IN | AppointmentCheckedIn |
| CONFIRMED | CheckInAppointment | Receptionist | RULE-06-06 | CHECKED_IN | AppointmentCheckedIn |
| BOOKED | RescheduleAppointment | Customer / Receptionist | RULE-06-01, RULE-06-03 | BOOKED | AppointmentRescheduled |
| CONFIRMED | RescheduleAppointment | Customer / Receptionist | RULE-06-01, RULE-06-03 | BOOKED | AppointmentRescheduled |
| CHECKED_IN | StartAppointmentService | Veterinarian / Groomer | RULE-09-01, RULE-11-01 | IN_PROGRESS | AppointmentStarted |
| IN_PROGRESS | CheckOutAppointment | Receptionist | RULE-06-06 | COMPLETED | AppointmentCompleted |
| CHECKED_IN | CheckOutAppointment | Receptionist | RULE-06-06 | COMPLETED | AppointmentCompleted |
| BOOKED | CancelAppointment | Customer / Receptionist | RULE-06-05, RULE-06-08 | CANCELLED | AppointmentCancelled |
| CONFIRMED | CancelAppointment | Customer / Receptionist | RULE-06-05, RULE-06-08 | CANCELLED | AppointmentCancelled |
| BOOKED | MarkNoShow | Receptionist / System | RULE-06-09 | NO_SHOW | AppointmentNoShow |
| CONFIRMED | MarkNoShow | Receptionist / System | RULE-06-09 | NO_SHOW | AppointmentNoShow |

- **Initial:** BOOKED
- **Terminal:** COMPLETED, CANCELLED, NO_SHOW
- *Ghi chú:* Khi đổi lịch hẹn (`RescheduleAppointment`), cuộc hẹn quay về trạng thái `BOOKED` để tái kiểm tra tính khả dụng của tài nguyên (`StoreResource`) và nhân sự phục vụ.

---

## 5. Order — OrderStatus (v1 In-Store Fulfillment)

> Theo quyết định nghiệp vụ đã duyệt cho v1: Loại bỏ trạng thái `SHIPPED`. Hệ thống hỗ trợ hoàn thành đơn hàng tại cửa hàng (In-Store Pickup / Retail Handover).

```text
[*] --(CreateOrder)--> PENDING_PAYMENT
PENDING_PAYMENT --(Event: PaymentSucceeded)--> PAID
PENDING_PAYMENT --(CancelOrder)--> CANCELLED
PENDING_PAYMENT --(ProcessOrderTimeout)--> CANCELLED
PAID --(ConfirmOrder)--> CONFIRMED
CONFIRMED --(ProcessOrder)--> PROCESSING
PROCESSING --(PrepareProductOrder)--> READY
READY --(CompleteStoreOrder)--> DELIVERED
CONFIRMED --(CancelOrder)--> CANCELLED
PROCESSING --(CancelOrderWithRefund)--> CANCELLED
READY --(CancelOrderWithRefund)--> CANCELLED
PAID --(Event: RefundCompleted)--> REFUNDED
DELIVERED --(Event: RefundCompleted)--> REFUNDED
```

| From | Command / Trigger | Actor | Guard (RULE-ID) | To | Domain Event |
|---|---|---|---|---|---|
| [*] | CreateOrder | Customer / Receptionist | RULE-14-01, RULE-14-02 | PENDING_PAYMENT | OrderCreated |
| PENDING_PAYMENT | Event: PaymentSucceeded | System | RULE-16-03 | PAID | OrderPaid |
| PENDING_PAYMENT | CancelOrder | Customer / Receptionist | RULE-14-03, RULE-14-06 | CANCELLED | OrderCancelled |
| PENDING_PAYMENT | ProcessOrderTimeout | System | RULE-14-07 (TTL 15 phút hết hạn giữ chỗ) | CANCELLED | OrderTimedOut |
| PAID | ConfirmOrder | Receptionist / System | RULE-14-04 | CONFIRMED | OrderConfirmed |
| CONFIRMED | ProcessOrder | Receptionist | RULE-14-03 | PROCESSING | OrderProcessed |
| PROCESSING | PrepareProductOrder | InventoryStaff | RULE-12-04, RULE-14-02 | READY | ProductOrderPrepared |
| READY | CompleteStoreOrder | Receptionist | RULE-14-05 | DELIVERED | OrderDelivered |
| CONFIRMED | CancelOrder | Customer / Receptionist | RULE-14-03, RULE-14-06 | CANCELLED | OrderCancelled |
| PROCESSING | CancelOrderWithRefund | StoreManager / Receptionist | RULE-14-03 (Khách hủy/hỏng hàng trong lúc soạn, kích hoạt hoàn tiền & hoàn kho) | CANCELLED | OrderCancelled |
| READY | CancelOrderWithRefund | StoreManager / Receptionist | RULE-14-03, RULE-14-05 (Quá hạn nhận/khách từ chối nhận, kích hoạt hoàn tiền & hoàn kho) | CANCELLED | OrderCancelled |
| PAID | Event: RefundCompleted | System | RULE-17-01, RULE-17-02 | REFUNDED | OrderRefunded |
| DELIVERED | Event: RefundCompleted | System | RULE-17-01, RULE-17-02 | REFUNDED | OrderRefunded |

- **Initial:** PENDING_PAYMENT
- **Terminal:** DELIVERED, CANCELLED, REFUNDED
- *Ghi chú:* Khi đơn hàng ở trạng thái `PENDING_PAYMENT`, tồn kho được tạm giữ chỗ (Reserved Quantity) tối đa 15 phút. Nếu quá hạn chưa thanh toán, hệ thống tự động kích hoạt `ProcessOrderTimeout` chuyển sang `CANCELLED` và giải phóng tồn kho.

---

## 6. Invoice — InvoiceStatus

```text
[*] --(CreateInvoice)--> DRAFT
DRAFT --(IssueInvoice)--> ISSUED
DRAFT --(DiscardInvoice)--> VOID
ISSUED --(Event: PaymentSucceeded [một phần])--> PARTIALLY_PAID
ISSUED --(Event: PaymentSucceeded [đủ tiền])--> PAID
PARTIALLY_PAID --(Event: PaymentSucceeded [một phần tiếp theo])--> PARTIALLY_PAID
PARTIALLY_PAID --(Event: PaymentSucceeded [đủ tiền])--> PAID
ISSUED --(VoidInvoice)--> VOID
PAID --(Event: AllPaymentsRefunded)--> REFUNDED
PARTIALLY_PAID --(Event: AllPaymentsRefunded)--> REFUNDED
PARTIALLY_PAID --(VoidPartiallyPaidInvoice)--> VOID
```

| From | Command / Trigger | Actor | Guard (RULE-ID) | To | Domain Event |
|---|---|---|---|---|---|
| [*] | CreateInvoice | Receptionist / FinanceStaff | RULE-15-01, RULE-15-06 | DRAFT | InvoiceCreated |
| DRAFT | IssueInvoice | FinanceStaff / Receptionist | RULE-15-01, RULE-15-02, RULE-15-03, RULE-15-06 | ISSUED | InvoiceIssued |
| DRAFT | DiscardInvoice | Receptionist / FinanceStaff | RULE-15-01 (Hủy bản nháp hóa đơn tạo sai) | VOID | InvoiceVoided |
| ISSUED | Event: PaymentSucceeded | System | RULE-15-05, RULE-16-02 | PARTIALLY_PAID | InvoicePartiallyPaid |
| ISSUED | Event: PaymentSucceeded | System | RULE-15-05, RULE-16-02 | PAID | InvoicePaid |
| PARTIALLY_PAID | Event: PaymentSucceeded | System | RULE-15-05, RULE-16-02 (Thanh toán tiếp một phần, tổng < TotalAmount) | PARTIALLY_PAID | InvoicePartiallyPaid |
| PARTIALLY_PAID | Event: PaymentSucceeded | System | RULE-15-05, RULE-16-02 (Thanh toán đủ 100%) | PAID | InvoicePaid |
| ISSUED | VoidInvoice | FinanceStaff | RULE-15-04 | VOID | InvoiceVoided |
| PAID | Event: AllPaymentsRefunded | System | RULE-15-07, RULE-17-01 | REFUNDED | InvoiceRefunded |
| PARTIALLY_PAID | Event: AllPaymentsRefunded | System | RULE-15-07, RULE-17-01 (Hoàn 100% các khoản cọc/tiền đã nhận) | REFUNDED | InvoiceRefunded |
| PARTIALLY_PAID | VoidPartiallyPaidInvoice | FinanceStaff | RULE-15-04, RULE-15-07 (Sau khi đã hoàn trả đủ tiền đã nhận, hủy nghĩa vụ thanh toán còn lại) | VOID | InvoiceVoided |

- **Initial:** DRAFT
- **Terminal:** VOID, REFUNDED

---

## 7. Payment — PaymentStatus

```text
[*] --(MakePayment / RecordCashPayment)--> PENDING
PENDING --(VerifyPayment / SettlePayment)--> PROCESSING
PROCESSING --(ReceivePaymentCallback / SettlePayment)--> SUCCESS
PROCESSING --(ReceivePaymentCallback / FailPayment)--> FAILED
PENDING --(CancelPayment)--> CANCELLED
SUCCESS --(Event: RefundCompleted [hoàn một phần: RefundAmount < TotalAmount])--> PARTIALLY_REFUNDED
PARTIALLY_REFUNDED --(Event: RefundCompleted [tiếp tục hoàn một phần: CumulativeRefund < TotalAmount])--> PARTIALLY_REFUNDED
PARTIALLY_REFUNDED --(Event: RefundCompleted [hoàn đủ 100%: CumulativeRefund == TotalAmount])--> REFUNDED
SUCCESS --(Event: RefundCompleted [hoàn 100% lần đầu])--> REFUNDED
```

| From | Command / Trigger | Actor | Guard (RULE-ID) | To | Domain Event |
|---|---|---|---|---|---|
| [*] | MakePayment | Customer | RULE-16-01, RULE-16-02 | PENDING | PaymentCreated |
| [*] | RecordCashPayment | Receptionist | RULE-16-01, RULE-16-02 | PENDING | PaymentCreated |
| PENDING | VerifyPayment | System / FinanceStaff | RULE-16-01, RULE-16-03 | PROCESSING | PaymentProcessing |
| PROCESSING | ReceivePaymentCallback | System | RULE-16-03, RULE-16-05 | SUCCESS | PaymentSucceeded |
| PROCESSING | SettlePayment | FinanceStaff | RULE-16-03 | SUCCESS | PaymentSucceeded |
| PROCESSING | ReceivePaymentCallback [fail] | System | RULE-16-04, RULE-16-05 | FAILED | PaymentFailed |
| PENDING | CancelPayment | Customer / System | RULE-16-04 | CANCELLED | PaymentCancelled |
| SUCCESS | Event: RefundCompleted [một phần] | System | RULE-17-02 (RefundAmount < TotalAmount) | PARTIALLY_REFUNDED | PaymentPartiallyRefunded |
| PARTIALLY_REFUNDED | Event: RefundCompleted [một phần tiếp] | System | RULE-17-02 (CumulativeRefund < TotalAmount) | PARTIALLY_REFUNDED | PaymentPartiallyRefunded |
| PARTIALLY_REFUNDED | Event: RefundCompleted [hoàn 100%] | System | RULE-17-02 (CumulativeRefund == TotalAmount) | REFUNDED | PaymentRefunded |
| SUCCESS | Event: RefundCompleted [hoàn 100%] | System | RULE-17-01, RULE-17-02 (Hoàn 100% lần đầu) | REFUNDED | PaymentRefunded |

- **Initial:** PENDING
- **Terminal:** FAILED, CANCELLED, REFUNDED (Lưu ý: `SUCCESS` và `PARTIALLY_REFUNDED` là trạng thái thanh toán thành công nhưng có thể chuyển sang `REFUNDED` khi có hoàn tiền).
- **Bất biến hoàn tiền (Partial Refund Invariant):**
  - `RemainingRefundableAmount = TotalAmount - Sum(CompletedRefunds) >= 0`.
  - Mọi yêu cầu `RefundRequest` phải thỏa mãn điều kiện: `RequestedAmount <= RemainingRefundableAmount`.

---

## 8. Refund — RefundStatus

> Theo quyết định nghiệp vụ đã duyệt:
> 1. Mỗi `Refund` gắn với đúng một giao dịch `Payment` gốc cụ thể, thực hiện hoàn tiền qua đúng kênh/cổng thanh toán gốc.
> 2. Loại bỏ trạng thái `UNDER_REVIEW`. Quy trình chuyển thẳng: `REQUESTED -> APPROVED / REJECTED`.
> 3. Trạng thái `FAILED` không phải là Terminal State; hệ thống hỗ trợ thử lại qua cổng (`RetryRefund`) hoặc xử lý ngoại tuyến/tiền mặt (`ResolveRefundManually`).

```text
[*] --(RequestRefund / CreateRefundRequest)--> REQUESTED
REQUESTED --(ApproveRefund)--> APPROVED
REQUESTED --(RejectRefund)--> REJECTED
APPROVED --(ProcessRefund)--> PROCESSING
PROCESSING --(CompleteRefund / GatewayCallback)--> COMPLETED
PROCESSING --(FailRefund / GatewayCallback)--> FAILED
FAILED --(RetryRefund)--> PROCESSING
FAILED --(ResolveRefundManually)--> COMPLETED
```

| From | Command / Trigger | Actor | Guard (RULE-ID) | To | Domain Event |
|---|---|---|---|---|---|
| [*] | RequestRefund | Customer | RULE-17-01, RULE-17-02, RULE-17-03 | REQUESTED | RefundRequested |
| [*] | CreateRefundRequest | Receptionist | RULE-17-01, RULE-17-02, RULE-17-03 | REQUESTED | RefundRequested |
| REQUESTED | ApproveRefund | StoreManager | RULE-17-01, RULE-17-02, RULE-17-04 | APPROVED | RefundApproved |
| REQUESTED | RejectRefund | StoreManager | RULE-17-06 | REJECTED | RefundRejected |
| APPROVED | ProcessRefund | FinanceStaff | RULE-17-04 | PROCESSING | RefundProcessing |
| PROCESSING | CompleteRefund | FinanceStaff / System | RULE-17-02, RULE-17-05 | COMPLETED | RefundCompleted |
| PROCESSING | FailRefund | System | RULE-17-05 | FAILED | RefundFailed |
| FAILED | RetryRefund | FinanceStaff / System | RULE-17-05 (Thử lại qua cổng thanh toán) | PROCESSING | RefundProcessing |
| FAILED | ResolveRefundManually | FinanceStaff | RULE-17-03, RULE-17-05 (Chuyển khoản trực tiếp/tiền mặt đối soát thủ công) | COMPLETED | RefundCompleted |

- **Initial:** REQUESTED
- **Terminal:** COMPLETED, REJECTED

---

## 9. Membership — MembershipStatus

```text
[*] --(RegisterMembership)--> ACTIVE
ACTIVE --(RenewMembership)--> ACTIVE
ACTIVE --(UpgradeMembership)--> UPGRADED
ACTIVE --(ProcessMembershipExpiry)--> EXPIRED
```

| From | Command / Trigger | Actor | Guard (RULE-ID) | To | Domain Event |
|---|---|---|---|---|---|
| [*] | RegisterMembership | Customer | RULE-19-01 | ACTIVE | MembershipCreated |
| ACTIVE | RenewMembership | Customer | RULE-19-01, RULE-19-07 | ACTIVE | MembershipRenewed |
| ACTIVE | UpgradeMembership | Customer / StoreManager | RULE-19-01, RULE-19-08 | UPGRADED | MembershipUpgraded |
| ACTIVE | ProcessMembershipExpiry | System | RULE-19-09 | EXPIRED | MembershipExpired |

- **Initial:** ACTIVE
- **Terminal:** UPGRADED, EXPIRED
- *Ghi chú:* Khi `RenewMembership`, trạng thái giữ nguyên là `ACTIVE` và gia hạn thêm `ExpirationDate`. Phát Domain Event `MembershipRenewed`.

---

## 10. Package — PackageStatus

```text
[*] --(PurchasePackage)--> PURCHASED
PURCHASED --(ActivatePackage)--> ACTIVATED
PURCHASED --(CancelPackage)--> CANCELLED
ACTIVATED --(ConfirmPackageUsage)--> PARTIALLY_CONSUMED
ACTIVATED --(ConfirmPackageUsage)--> FULLY_CONSUMED
PARTIALLY_CONSUMED --(ConfirmPackageUsage)--> FULLY_CONSUMED
ACTIVATED --(CancelPackage)--> CANCELLED
PARTIALLY_CONSUMED --(CancelPackage)--> CANCELLED
ACTIVATED --(ProcessPackageExpiry)--> EXPIRED
PARTIALLY_CONSUMED --(ProcessPackageExpiry)--> EXPIRED
```

| From | Command / Trigger | Actor | Guard (RULE-ID) | To | Domain Event |
|---|---|---|---|---|---|
| [*] | PurchasePackage | Customer | RULE-20-01 | PURCHASED | PackagePurchased |
| PURCHASED | ActivatePackage | Receptionist | RULE-20-01 | ACTIVATED | PackageActivated |
| PURCHASED | CancelPackage | StoreManager | RULE-20-06 | CANCELLED | PackageCancelled |
| ACTIVATED | ConfirmPackageUsage | Receptionist | RULE-20-02, RULE-20-04, RULE-20-05 | PARTIALLY_CONSUMED | PackagePartiallyConsumed |
| ACTIVATED | ConfirmPackageUsage | Receptionist | RULE-20-02, RULE-20-04, RULE-20-05 | FULLY_CONSUMED | PackageFullyConsumed |
| PARTIALLY_CONSUMED | ConfirmPackageUsage | Receptionist | RULE-20-02, RULE-20-04, RULE-20-05 | FULLY_CONSUMED | PackageFullyConsumed |
| ACTIVATED | CancelPackage | StoreManager | RULE-20-06 | CANCELLED | PackageCancelled |
| PARTIALLY_CONSUMED | CancelPackage | StoreManager | RULE-20-06 | CANCELLED | PackageCancelled |
| ACTIVATED | ProcessPackageExpiry | System | RULE-20-03 | EXPIRED | PackageExpired |
| PARTIALLY_CONSUMED | ProcessPackageExpiry | System | RULE-20-03 | EXPIRED | PackageExpired |

- **Initial:** PURCHASED
- **Terminal:** FULLY_CONSUMED, CANCELLED, EXPIRED

---

## 11. StockTransfer — StockTransferStatus

```text
[*] --(CreateStockTransfer)--> REQUESTED
REQUESTED --(ApproveStockTransfer)--> APPROVED
REQUESTED --(RejectStockTransfer)--> REJECTED
REQUESTED --(CancelStockTransfer)--> CANCELLED
APPROVED --(ShipStockTransfer)--> IN_TRANSIT
IN_TRANSIT --(ReceiveStockTransfer [đủ hàng, nguyên vẹn])--> RECEIVED
IN_TRANSIT --(ReceiveStockTransferWithDiscrepancy [hao hụt/hư hỏng])--> DISCREPANCY
DISCREPANCY --(ResolveStockTransferDiscrepancy)--> RECEIVED
```

| From | Command | Actor | Guard (RULE-ID) | To | Domain Event |
|---|---|---|---|---|---|
| [*] | CreateStockTransfer | InventoryStaff | RULE-12-03, RULE-12-07 | REQUESTED | StockTransferCreated |
| REQUESTED | ApproveStockTransfer | StoreManager | RULE-12-06 | APPROVED | StockTransferApproved |
| REQUESTED | RejectStockTransfer | StoreManager | RULE-12-06 | REJECTED | StockTransferRejected |
| REQUESTED | CancelStockTransfer | InventoryStaff | RULE-12-08 | CANCELLED | StockTransferCancelled |
| APPROVED | ShipStockTransfer | InventoryStaff | RULE-12-04, RULE-12-05 | IN_TRANSIT | StockTransferShipped |
| IN_TRANSIT | ReceiveStockTransfer | InventoryStaff | RULE-12-03, RULE-12-07 (Khớp 100% số lượng & tình trạng) | RECEIVED | StockTransferReceived |
| IN_TRANSIT | ReceiveStockTransferWithDiscrepancy | InventoryStaff | RULE-12-02, RULE-12-03 (Phát hiện hư hỏng/thiếu hụt) | DISCREPANCY | StockTransferDiscrepancyReported |
| DISCREPANCY | ResolveStockTransferDiscrepancy | StoreManager | RULE-12-02 (Tạo phiếu InventoryAdjustment cân bằng kho) | RECEIVED | StockTransferDiscrepancyResolved |

- **Initial:** REQUESTED
- **Terminal:** RECEIVED, REJECTED, CANCELLED
- *Ghi chú:* Trạng thái `DISCREPANCY` là trạng thái xử lý bất thường tạm thời khi phát hiện hàng hóa chuyển kho bị hư hỏng/thất thoát trong quá trình vận chuyển. Sau khi Store Manager lập phiếu điều chỉnh kiểm kê (`InventoryAdjustment`), phiếu chuyển kho chuyển tiếp sang `RECEIVED`.

---

## 12. PurchaseRequest — PurchaseRequestStatus

```text
[*] --(CreatePurchaseRequest)--> DRAFT
DRAFT --(SubmitPurchaseRequest)--> SUBMITTED
SUBMITTED --(ApprovePurchaseRequest)--> APPROVED
SUBMITTED --(RejectPurchaseRequest)--> REJECTED
DRAFT --(CancelPurchaseRequest)--> CANCELLED
SUBMITTED --(CancelPurchaseRequest)--> CANCELLED
```

| From | Command | Actor | Guard (RULE-ID) | To | Domain Event |
|---|---|---|---|---|---|
| [*] | CreatePurchaseRequest | InventoryStaff | RULE-13-01 | DRAFT | PurchaseRequestCreated |
| DRAFT | SubmitPurchaseRequest | InventoryStaff | RULE-13-01 | SUBMITTED | PurchaseRequestSubmitted |
| SUBMITTED | ApprovePurchaseRequest | StoreManager | RULE-13-04 | APPROVED | PurchaseRequestApproved |
| SUBMITTED | RejectPurchaseRequest | StoreManager | RULE-13-04 | REJECTED | PurchaseRequestRejected |
| DRAFT / SUBMITTED | CancelPurchaseRequest | InventoryStaff | — | CANCELLED | PurchaseRequestCancelled |

- **Initial:** DRAFT (hoặc SUBMITTED nếu tạo và gửi trực tiếp)
- **Terminal:** APPROVED, REJECTED, CANCELLED

---

## 13. PurchaseOrder — PurchaseOrderStatus

```text
[*] --(CreatePurchaseOrder)--> ISSUED
ISSUED --(ReceiveGoods [một phần])--> PARTIALLY_RECEIVED
ISSUED --(ReceiveGoods [đủ hàng])--> RECEIVED
PARTIALLY_RECEIVED --(ReceiveGoods [đủ hàng còn lại])--> RECEIVED
PARTIALLY_RECEIVED --(CancelRemainingPurchaseOrder [hủy phần còn lại])--> CLOSED
ISSUED --(CancelPurchaseOrder)--> CANCELLED
```

| From | Command | Actor | Guard (RULE-ID) | To | Domain Event |
|---|---|---|---|---|---|
| [*] | CreatePurchaseOrder | InventoryStaff | RULE-13-02, RULE-13-04 | ISSUED | PurchaseOrderCreated |
| ISSUED | ReceiveGoods | InventoryStaff | RULE-13-03, RULE-13-05 | PARTIALLY_RECEIVED | GoodsReceived |
| ISSUED | ReceiveGoods | InventoryStaff | RULE-13-03, RULE-13-05 | RECEIVED | GoodsReceived |
| PARTIALLY_RECEIVED | ReceiveGoods | InventoryStaff | RULE-13-03, RULE-13-05 | RECEIVED | GoodsReceived |
| PARTIALLY_RECEIVED | CancelRemainingPurchaseOrder | StoreManager / InventoryStaff | RULE-13-03, RULE-13-06 | CLOSED | PurchaseOrderRemainingCancelled |
| ISSUED | CancelPurchaseOrder | InventoryStaff / StoreManager | — | CANCELLED | PurchaseOrderCancelled |

- **Initial:** ISSUED
- **Terminal:** RECEIVED, CLOSED, CANCELLED
- *Ghi chú:* Trạng thái `CLOSED` thể hiện đơn mua hàng đã tiếp nhận một phần hàng hóa thực tế và đã thống nhất hủy số lượng còn lại chưa giao với nhà cung cấp, đóng đơn hàng mà không làm ảnh hưởng đến số lượng hàng đã nhập kho trước đó.

---

## 14. Incident — IncidentStatus

```text
[*] --(RecordIncident)--> RECORDED
RECORDED --(ClassifyIncident)--> CLASSIFIED
CLASSIFIED --(InvestigateIncident)--> UNDER_INVESTIGATION
UNDER_INVESTIGATION --(EscalateIncident)--> ESCALATED
UNDER_INVESTIGATION --(HandleIncident)--> RESOLVED
ESCALATED --(HandleIncident)--> RESOLVED
RESOLVED --(CloseIncident)--> CLOSED
```

| From | Command | Actor | Guard (RULE-ID) | To | Domain Event |
|---|---|---|---|---|---|
| [*] | RecordIncident / RecordClinicalIncident / RecordGroomingIncident | Staff / Vet / Groomer | RULE-21-01, RULE-21-02, RULE-21-03 | RECORDED | IncidentRecorded |
| RECORDED | ClassifyIncident | StoreManager | RULE-21-05 | CLASSIFIED | IncidentClassified |
| CLASSIFIED | InvestigateIncident | StoreManager | — | UNDER_INVESTIGATION | IncidentInvestigated |
| UNDER_INVESTIGATION | EscalateIncident | StoreManager | — | ESCALATED | IncidentEscalated |
| UNDER_INVESTIGATION / ESCALATED | HandleIncident | StoreManager | — | RESOLVED | IncidentResolved |
| RESOLVED | CloseIncident | StoreManager | RULE-21-04 | CLOSED | IncidentClosed |

- **Initial:** RECORDED
- **Terminal:** CLOSED

---

## 15. Grooming — GroomingStatus

> `GroomingStatus` đặc tả toàn bộ vòng đời của một phiên dịch vụ Grooming / Spa thú cưng tại Store, bao gồm quy trình kiểm tra thể trạng, phát sinh dịch vụ thêm và phê duyệt đồng thuận từ khách hàng.

```text
[*] --(CheckInGrooming)--> WAITING
WAITING --(PerformGrooming)--> IN_PROGRESS
IN_PROGRESS --(AddGroomingService)--> AWAITING_CUSTOMER_APPROVAL
AWAITING_CUSTOMER_APPROVAL --(ConfirmAdditionalService)--> IN_PROGRESS
AWAITING_CUSTOMER_APPROVAL --(RejectAdditionalService)--> IN_PROGRESS
IN_PROGRESS --(CompleteGrooming)--> COMPLETED
WAITING --(CancelGrooming)--> CANCELLED
IN_PROGRESS --(CancelGrooming)--> CANCELLED
```

| From | Command / Trigger | Actor | Guard (RULE-ID) | To | Domain Event |
|---|---|---|---|---|---|
| [*] | CheckInGrooming | Receptionist | RULE-11-01 (Check-in tiếp nhận thú cưng tại quầy) | WAITING | GroomingCheckedIn |
| WAITING | PerformGrooming | Groomer | RULE-11-01 (Kiểm tra thể trạng trước khi bắt đầu) | IN_PROGRESS | GroomingStarted |
| IN_PROGRESS | AddGroomingService | Groomer | RULE-11-02, RULE-11-05 (Đề xuất dịch vụ phát sinh / gỡ rối / spa đặc biệt) | AWAITING_CUSTOMER_APPROVAL | AdditionalServiceRequested |
| AWAITING_CUSTOMER_APPROVAL | ConfirmAdditionalService | Customer | RULE-11-03 (Khách hàng đồng ý chi phí & dịch vụ phát sinh) | IN_PROGRESS | AdditionalServiceConfirmed |
| AWAITING_CUSTOMER_APPROVAL | RejectAdditionalService | Customer | RULE-11-03 (Khách hàng từ chối dịch vụ phát sinh) | IN_PROGRESS | AdditionalServiceRejected |
| IN_PROGRESS | CompleteGrooming | Groomer | RULE-11-04 (Chụp ảnh kết quả & nghiệm thu dịch vụ) | COMPLETED | GroomingCompleted |
| WAITING | CancelGrooming | Customer / Receptionist | — | CANCELLED | GroomingCancelled |
| IN_PROGRESS | CancelGrooming | StoreManager / Receptionist | — | CANCELLED | GroomingCancelled |

- **Initial:** WAITING
- **Terminal:** COMPLETED, CANCELLED
- *Ghi chú:* Khi Groomer đề xuất dịch vụ phát sinh (`AddGroomingService`), phiên grooming tạm dừng chuyển sang `AWAITING_CUSTOMER_APPROVAL`. Sau khi khách hàng chấp thuận (`ConfirmAdditionalService`) hoặc từ chối (`RejectAdditionalService`), phiên grooming quay lại `IN_PROGRESS` để tiếp tục.

---

## 16. Cross-aggregate Transition Triggers & Event Bridges

| Source Aggregate & State | Target Aggregate & Transition | Trigger Event / Bridge | Ràng buộc nghiệp vụ (Rule ID) |
|---|---|---|---|
| **Payment** / `SUCCESS` | **Invoice** / `PARTIALLY_PAID` hoặc `PAID` | `PaymentSucceeded` | Cập nhật lũy kế `PaidAmount`. Nếu `PaidAmount >= TotalAmount` -> `PAID`, ngược lại -> `PARTIALLY_PAID` (RULE-15-05, RULE-16-02). |
| **Payment** / `SUCCESS` | **Order** / `PAID` | `PaymentSucceeded` | Khi thanh toán đơn hàng thành công, Order chuyển sang `PAID` (RULE-14-04). |
| **Payment** / `PARTIALLY_REFUNDED` | **Invoice** / `PARTIALLY_PAID` | `PaymentPartiallyRefunded` | Cập nhật giảm trừ số tiền thực thu trên hóa đơn, giữ nguyên trạng thái `PARTIALLY_PAID` (RULE-15-05, RULE-17-02). |
| **Refund** / `COMPLETED` | **Payment** / `PARTIALLY_REFUNDED` hoặc `REFUNDED` | `RefundCompleted` | Cập nhật `RemainingRefundableAmount`. Nếu bằng 0 chuyển sang `REFUNDED`, ngược lại chuyển sang `PARTIALLY_REFUNDED` (RULE-17-02). |
| **Refund** / `COMPLETED` (tất cả payment) | **Invoice** / `REFUNDED` | `AllPaymentsRefunded` | Khi toàn bộ Payment thuộc Invoice đã được hoàn tiền 100%, Invoice chuyển sang `REFUNDED` (RULE-15-07). |
| **Refund** / `COMPLETED` | **Order** / `REFUNDED` | `RefundCompleted` | Khi tiền đơn hàng được hoàn trả đầy đủ, Order chuyển sang `REFUNDED` (RULE-14-04). |
| **Order** / `CANCELLED` (`ProcessOrderTimeout`) | **Inventory** / Giải phóng số lượng giữ chỗ | `OrderTimedOut` | Giải phóng số lượng giữ chỗ (Reserved Quantity) về lại tồn kho khả dụng (RULE-14-07, RULE-12-04). |
| **Order** / `CANCELLED` (`CancelOrderWithRefund`) | **Refund** / `REQUESTED` & **Inventory** / Hoàn kho | `OrderCancelledWithRefund` | Tự động tạo yêu cầu hoàn tiền cho khách và hoàn lại sản phẩm về tồn kho thực tế (RULE-14-03, RULE-17-01). |
| **StockTransfer** / `IN_TRANSIT` | **Inventory** (Kho nguồn) / Trừ tồn | `StockTransferShipped` | Xuất chuyển kho làm giảm tồn kho khả dụng của nguồn (RULE-12-04). |
| **StockTransfer** / `RECEIVED` | **Inventory** (Kho đích) / Tăng tồn | `StockTransferReceived` | Nhập chuyển kho làm tăng tồn kho khả dụng của đích (RULE-12-03). |
| **StockTransfer** / `DISCREPANCY` | **Inventory** / Tạo phiếu `InventoryAdjustment` | `StockTransferDiscrepancyReported` | Báo cáo sai lệch kích hoạt luồng lập phiếu kiểm kê điều chỉnh để cân bằng số liệu (RULE-12-02). |
| **PurchaseOrder** / `RECEIVED` | **Inventory** / Tăng tồn kho | `GoodsReceived` | Hàng nhận từ PO làm tăng số lượng tồn kho khả dụng tại Store nhận (RULE-13-05). |
| **PurchaseOrder** / `CLOSED` | **Procurement** / Giải phóng cam kết đặt hàng | `PurchaseOrderRemainingCancelled` | Đóng đơn đặt hàng, hủy cam kết công nợ cho phần hàng chưa giao (RULE-13-06). |
| **Grooming** / `COMPLETED` | **Invoice** / `AddServiceToInvoice` & **Appointment** / `CheckOutAppointment` | `GroomingCompleted` | Đồng bộ dịch vụ hoàn tất và phụ phí phát sinh vào hóa đơn thanh toán và kết thúc lịch hẹn (RULE-11-04, RULE-06-06). |
| **Package** / `ACTIVATED` | **Appointment** / Check-out | `PackagePartiallyConsumed` / `PackageFullyConsumed` | Trừ lượt sử dụng khi hoàn thành dịch vụ trong gói (RULE-20-02, RULE-20-05). |

---

### Kiến trúc Triển khai Event Bridges & Đảm bảo Tính nhất quán Dữ liệu (Transactional Outbox Pattern)

Nhằm đảm bảo tính nhất quán dữ liệu tuyệt đối giữa các Aggregates trong kiến trúc Monolith của hệ sinh thái Pet Care (đặc biệt giữa Tiền, Hóa đơn, Đơn hàng và Tồn kho), hệ thống quy định nguyên tắc kiến trúc sau:

1. **Giao dịch Nội vùng (Intra-Aggregate / Synchronous Transaction):**
   - Đối với các thao tác thanh toán trực tiếp tại quầy bằng tiền mặt (`RecordCashPayment`), tiếp tân thực hiện ghi nhận trong cùng một ranh giới `@Transactional` duy nhất bao gồm: Cập nhật Payment (`SUCCESS`), cập nhật Invoice (`PAID`), cập nhật Order (`PAID`) và hoàn tất dịch vụ/xuất kho.

2. **Giao dịch Ngoại vùng & Không đồng bộ (Cross-Aggregate / Event-Driven via Transactional Outbox):**
   - Đối với các sự kiện phát sinh từ bên ngoài (Webhook thanh toán Online, Cron Job hủy timeout 15 phút, yêu cầu hoàn tiền tự động qua cổng thanh toán), hệ thống **BẮT BUỘC** sử dụng **Transactional Outbox Pattern**:
     - *Local Commit:* Bản ghi thay đổi trạng thái Aggregate nguồn và bản ghi Sự kiện Outbox (`outbox_events`) phải được ghi vào Database trong cùng một Transaction cục bộ (ACID).
     - *Outbox Relay Worker:* Một tiến trình nền (Scheduled Worker / CDC) đọc các sự kiện chưa gửi trong bảng Outbox và phát đi các Domain Event tương ứng với đảm bảo **At-Least-Once Delivery**.
     - *Idempotent Consumers:* Các Consumer xử lý Domain Event (như `ReleaseReservedInventoryListener`, `OrderPaidListener`, `InvoiceRefundedListener`) bắt buộc phải kiểm tra khóa Idempotency Key (`event_id` hoặc `payment_transaction_id`) để loại trừ trùng lặp khi xử lý lại.
   - Tuyệt đối **KHÔNG** sử dụng `@TransactionalEventListener(phase = AFTER_COMMIT)` phát trực tiếp trong bộ nhớ mà không lưu vết Outbox, nhằm loại bỏ triệt để rủi ro mất mát sự kiện khi hệ thống gặp sự cố crash ngay sau khi commit transaction nguồn.
