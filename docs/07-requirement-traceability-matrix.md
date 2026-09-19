# Pet Care Ecosystem — Requirement Traceability Matrix

> Ma trận truy vết đầy đủ cho toàn bộ Requirement (FR/NFR/SEC) tại `docs/00-requirements.md`: `Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD`. Nhóm theo 25 module nghiệp vụ (khớp `docs/01-business-operations.md`) cộng thêm nhóm riêng cho Non-Functional & Security Requirements. `Source` ghi số hiệu module trong `01-business-operations.md`. Khi không có nguồn hỗ trợ tương ứng ở một cột, ghi `UNTRACED`.
>
> Tài liệu này bổ trợ cho `docs/00-requirements.md` (Source of Truth cho nội dung Requirement) — không tự định nghĩa Requirement mới ở đây.

---

## 1. Module 01 — Authentication & OTP

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-ACC-001 | 01 §1 | RULE-01-01, RULE-01-03 | `RegisterAccount` | FSM 1 | `accounts` |
| REQ-ACC-002 | 01 §1 | RULE-01-02 | `SendRegistrationOTP` | FSM 1 | `otps` |
| REQ-ACC-003 | 01 §1 | RULE-01-02, RULE-01-08 | `CheckOTP`, `ExpireOTP` | FSM 1 | `otps` |
| REQ-ACC-004 | 01 §1 | RULE-01-04 | `ResendOTP` | FSM 1 | `otps` |
| REQ-ACC-005 | 01 §1 | RULE-01-05 | `CheckOTP`, `ResendOTP` | FSM 1 | `otps` |
| REQ-ACC-006 | 01 §1 | RULE-01-03, RULE-02-05 (D-04) | `CreateStaff` | FSM 1 | `accounts.must_change_password` |
| REQ-ACC-007 | 01 §1 | RULE-01-01 | `Login` | FSM 1 | `accounts.status` |
| REQ-ACC-008 | 01 §1 | RULE-01-07 | `AutoLockAccount` (System trigger, không có Command Candidate riêng trong `01`) | FSM 1 | `accounts.failed_login_attempts` |
| REQ-ACC-009 | 01 §1 | RULE-01-06 | `Logout` | FSM 1 | UNTRACED (không có bảng Session tường minh trong `06`) |
| REQ-ACC-010 | 02 §2 | RULE-02-04, RULE-02-07 | `LockAccount`, `DeactivateAccount` | FSM 1 | `accounts.status` |
| REQ-ACC-011 | 02 §2 | RULE-01-07, RULE-02-04 | `UnlockAccount`, `AutoUnlockAccount` | FSM 1 | `accounts.status`, `accounts.lock_reason`, `accounts.locked_until` |
| REQ-ACC-012 | 02 §2 | RULE-02-05, RULE-02-07 | `DeactivateAccount`, `ReactivateAccount` | FSM 1 | `accounts.status` |
| REQ-ACC-013 | 01 §1 | RULE-01-08 | `ExpireOTP` | FSM 1 | `otps.expires_at` |
| REQ-ACC-014 | 01 §1 | RULE-01-10 (mới — Phase 5; sửa 2026-09-13: email thay phone làm danh tính chính) | `RegisterAccount`, `CreateStaff` | FSM 1 | `accounts.email` (`uq_accounts_email`, NOT NULL), `accounts.phone` (`uq_accounts_phone`, optional) |

## 2. Module 02 — Identity & Access Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-IAM-001 | 02 §2 | RULE-02-01 | mọi Command (kiểm tra chéo) | N/A | `user_roles`, `role_permissions` |
| REQ-IAM-002 | 02 §2 | RULE-02-02 | `ManageRole` | N/A | `roles` |
| REQ-IAM-003 | 02 §2 | RULE-02-03 | `AssignPermission`, `ManageRole` | N/A | `role_permissions` |
| REQ-IAM-004 | 02 §2 | RULE-02-05 | `ManageUser` | N/A | `users` |
| REQ-IAM-005 | 02 §2 | RULE-02-06 | `ManageCustomerProfile` | N/A | `users` |
| REQ-IAM-006 | 04 §4 | RULE-02-06, RULE-04-02 | `ManageCustomerProfile`, `SearchCustomerPet` | N/A | `users` |

## 3. Module 03 — Organization & Store Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-ORG-001 | 03 §3 | RULE-03-01 | `CreateOrganization`, `UpdateOrganization` | N/A | `organizations`, `stores` |
| REQ-ORG-002 | 03 §3 | RULE-02-01, RULE-03-01 | N/A | N/A | `organization_id` FK trên toàn bộ bảng theo Tenant |
| REQ-ORG-003 | 03 §3 | RULE-03-01, RULE-03-02 | `CreateStore`, `ActivateStore` | FSM 2 | `stores.status` |
| REQ-ORG-004 | 03 §3 | RULE-03-02 | N/A (guard áp dụng cho `BookAppointment`, `RegisterQueueEntry`, `CreateOrder`) | FSM 2 | `stores.status` |
| REQ-ORG-005 | 03 §3 | RULE-03-03, RULE-03-04 | `SuspendStore`, `DeactivateStore` | FSM 2 | `stores.status` |
| REQ-ORG-006 | 03 §3 | RULE-03-06 | `ArchiveStore` | FSM 2 | `stores.status` |
| REQ-ORG-007 | 03 §3 | RULE-03-03, RULE-03-09, RULE-03-10 | `ManageOrganizationPolicy` (đã thiết kế), `ConfigureStorePolicy` (đã thiết kế) | N/A | `organization_policies` (nửa Organization), `store_policies` (nửa Store) |
| REQ-ORG-008 | 03 §6 | RULE-03-07 | `ConfigureOperatingHour` | N/A | `operating_hours` |
| REQ-ORG-009 | 03 §3 | RULE-03-08 | `ConfigureStoreResource` | N/A | `store_resources` |

## 4. Module 04 — Customer & Pet Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-PET-001 | 04 §4 | RULE-04-01 | N/A | N/A | `pets.owner_id` |
| REQ-PET-002 | 04 §4 | RULE-04-03 | `UpdatePet`, `ManagePetOwnership` | N/A | `pets` |
| REQ-PET-003 | 04 §4 | RULE-04-02, RULE-04-03 | `AddPet`, `UpdatePet` | N/A | `pets` |
| REQ-PET-004 | 04 §4 | RULE-04-02 | `SearchCustomerPet` | N/A | `pets`, `users` |
| REQ-PET-005 | 04 §4 | RULE-04-04 | `InviteCaregiver`, `RevokeCaregiver` | FSM 3 | `pet_caregiver_delegations` |
| REQ-PET-006 | 04 §4 | RULE-04-05 | `ProcessInvitationExpiry` | FSM 3 | `pet_caregiver_delegations.expires_at` |
| REQ-PET-007 | 04 §4 | RULE-04-06 | `AcceptCaregiverInvitation`, `RejectCaregiverInvitation` | FSM 3 | `pet_caregiver_delegations.status` |
| REQ-PET-008 | 04 §4 | RULE-04-07 | `ProcessDelegationExpiry` | FSM 3 | `pet_caregiver_delegations` |
| REQ-PET-009 | 04 §4 | RULE-04-08 | `RevokeCaregiver` | FSM 3 | `pet_caregiver_delegations.status` |
| REQ-PET-010 | 04 §4 | RULE-04-09 | `PerformDelegatedAction`, `ViewPet` | FSM 3 | UNTRACED (không có bảng ghi phạm vi quyền chi tiết `DelegatedPermissionSet`) |
| REQ-PET-011 | 04 §4 | RULE-04-04 | N/A (negative constraint) | FSM 3 | N/A |
| REQ-PET-012 | 04 §4 | RULE-04-10 | `ManagePetOwnership` | N/A (không thể hiện trong FSM 3 mermaid, chỉ trong bảng rule) | `pets.owner_id`, `pet_caregiver_delegations` |
| REQ-PET-013 | 04 §4 | RULE-04-11 | `UpdatePet` | N/A (prose invariant, không có mermaid FSM riêng) | `pets.status`, `pet_status_enum` |

## 5. Module 05 — Service & Product Catalog

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-CAT-001 | 05 §5 | RULE-05-01, RULE-05-03 | `ManageProduct`, `ManageService` | N/A | `products`, `services` |
| REQ-CAT-002 | 05 §5 | RULE-05-02 | `ManageProduct` | N/A | `products` (`UNIQUE(organization_id, sku)`) |
| REQ-CAT-003 | 05 §5, 03 §3 | RULE-05-04, RULE-03-05 (hợp nhất — xem `02` ghi chú tham chiếu chéo) | `ConfigureServiceAvailability` (Command duy nhất, đã hợp nhất với `ConfigureStoreService` cũ) | N/A | `store_services.is_active` |
| REQ-CAT-004 | 05 §5 | RULE-05-05 | `ConfigureServicePrice`, `ConfigureProductPrice` | N/A | `store_services.price`, `store_products.price` |
| REQ-CAT-005 | 05 §5 | RULE-05-07 | N/A (System trigger khi `ActivateStore`) | N/A | `store_products`, `store_services` |
| REQ-CAT-006 | 05 §5 | RULE-05-06 | `ViewProduct`, `ViewService` | N/A | `products.is_active`, `store_services.is_active` |

## 6. Module 06 — Appointment & Scheduling

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-APT-001 | 06 §6 | RULE-06-01 | `HoldSlot` | FSM 4.1 | `booking_holds` |
| REQ-APT-002 | 06 §6 | RULE-06-01 | `ReleaseHold` | FSM 4.1 | `booking_holds.status` |
| REQ-APT-003 | 06 §6 | RULE-06-01 | `ExpireHold` | FSM 4.1 | `booking_holds.expires_at` |
| REQ-APT-004 | 06 §6 | RULE-06-02, RULE-06-10, RULE-06-11 | `BookAppointment` | FSM 4.2 | `appointments` |
| REQ-APT-005 | 06 §6 | RULE-06-11 | `BookAppointment`, `RescheduleAppointment` | FSM 4.2 | `appointments` |
| REQ-APT-006 | 06 §6 | RULE-06-10 | `ConfirmAppointment`, `CheckAvailability` | FSM 4.2 | `appointments`, `store_resources` |
| REQ-APT-007 | 06 §6 | RULE-06-04 | `RescheduleAppointment` | FSM 4.2 | `appointments` |
| REQ-APT-008 | 06 §6 | RULE-06-05 | `CancelAppointment` | FSM 4.2 | `appointments.cancellation_reason` |
| REQ-APT-009 | 06 §6 | RULE-06-06 | `CheckInAppointment`, `StartAppointmentService` | FSM 4.2 (Technical Invariant #1) | `appointments.status` |
| REQ-APT-010 | 06 §6 | RULE-06-06, RULE-06-07 | `CheckOutAppointment` | FSM 4.2 | `appointments` |
| REQ-APT-011 | 06 §6 | RULE-06-08, RULE-21-01/02/03 | `AbortAppointment` | FSM 4.2 | `appointments.abort_reason`, `incident_reports` |
| REQ-APT-012 | 06 §6 | RULE-06-09 | `MarkNoShow` | FSM 4.2 | `appointments.status` |
| REQ-APT-013 | 06 §6 | RULE-06-13 | `SendAppointmentReminder` | N/A | `notification_tasks` |
| REQ-APT-014 | 06 §6 | RULE-06-14 | `ViewAppointment` | N/A | `appointments` |
| REQ-APT-015 | 06 §6 | RULE-06-12 | `AssignStaff`, `CoordinateSchedule` | N/A | `staff_work_schedules`, `shift_assignments` |
| REQ-APT-016 | 06 §6 | RULE-06-03 | `UpdateAppointment` | FSM 4.2 | `appointments` |

## 7. Module 07 — Walk-in & Queue Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-QUE-001 | 07 §7 | RULE-07-01 | `RegisterQueueEntry` | FSM 17 | `daily_queues`, `queue_entries` |
| REQ-QUE-002 | 07 §7 | RULE-07-02 | `ManageQueueOrder` | FSM 17 | `queue_entries.priority` |
| REQ-QUE-003 | 07 §7 | RULE-07-03 | `CallQueueEntry`, `SendTurnNotification` | FSM 17 | `queue_entries.status` |
| REQ-QUE-004 | 07 §7 | RULE-07-04 | `CancelQueueEntry` | FSM 17 | `queue_entries.status` |
| REQ-QUE-005 | 07 §7 | RULE-07-05 | `StartQueueService` | FSM 17, Event Bridge #14 | `queue_entries.appointment_id`, `appointments` |
| REQ-QUE-006 | 07 §7 | RULE-07-06 | `MarkQueueNoShow` | FSM 17 | `queue_entries.called_times` |
| REQ-QUE-007 | 07 §7 | RULE-07-07 | `CompleteQueueEntry` | FSM 17 | `queue_entries.status`, `appointments.status` |
| REQ-QUE-008 | 07 §7 | RULE-07-08 | `CoordinateQueue` | N/A | N/A |

## 8. Module 08 — Workforce Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-WFM-001 | 08 §8 | RULE-08-02 | `AssignStaffToStore` | N/A | `users.store_id` |
| REQ-WFM-002 | 08 §8 | RULE-08-03 | `ManageWorkSchedule` | N/A | `shift_assignments`, `staff_absences` |
| REQ-WFM-003 | 08 §8 | RULE-08-04 | `HandleStaffAbsence` | N/A | `staff_absences` |
| REQ-WFM-004 | 08 §8 | RULE-08-05 | `AssignStaffReplacement` | N/A | UNTRACED (không có bảng `staff_replacements` trong `06`) |
| REQ-WFM-005 | 08 §8 | RULE-08-06 | `ManageLeave` | N/A | `staff_absences.status` |
| REQ-WFM-006 | 08 §8 | RULE-08-07 | `ViewWorkSchedule` | N/A | `staff_work_schedules` |
| REQ-WFM-007 | 08 §8 | RULE-08-01 | `ManageStaff` | N/A | `users` |

## 9. Module 09 — Veterinary / Clinical Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-CLN-001 | 09 §9 | RULE-09-01 | `ExaminePet`, `CreateMedicalRecord`, `RecordSymptom` | N/A | `medical_records` |
| REQ-CLN-002 | 09 §9 | RULE-09-03 | `DiagnosePet` | N/A | `diagnoses` |
| REQ-CLN-003 | 09 §9 | RULE-09-05 | `CreatePrescription` | N/A | `prescriptions`, `prescription_items` |
| REQ-CLN-004 | 09 §9 | RULE-09-06 | `CreateFollowUp` | N/A | `follow_ups` |
| REQ-CLN-005 | 09 §9, 22 §22 | RULE-09-02, RULE-22-02, RULE-22-08 | `RequestCrossStoreConsent`, `VerifyCrossStoreConsentOTP` | FSM 16 | `cross_store_consents` |
| REQ-CLN-006 | 09 §9, 22 §22 | RULE-09-02, RULE-21-02, RULE-22-08 | `EmergencyOverrideAccess` | FSM 16 | `cross_store_consents.is_emergency`, `incident_reports` |
| REQ-CLN-007 | 09 §9 | RULE-09-07 | `ViewMedicalHistory` | N/A | `medical_records` |
| REQ-CLN-008 | 09 §9, 10 §10 | RULE-09-08, RULE-10-07 | N/A (boundary rule, không phải Command) | N/A | `medical_records` vs `vaccinations` |
| REQ-CLN-009 | 09 §9 | RULE-09-04 | `CreateTreatment` | N/A | `treatments` |
| REQ-CLN-010 | 09 §9 | RULE-09-09 | `CheckOutAppointment`, `UpdateMedicalRecord`, `LockMedicalRecord` | N/A (prose invariant, không có mermaid FSM riêng) | `medical_records.status`, `.finalized_at`, `.locked_at` |

## 10. Module 10 — Vaccination Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-VAC-001 | 10 §10 | RULE-10-01 | `ViewVaccinationSchedule` | N/A | `vaccinations`, `vaccination_schedules` |
| REQ-VAC-002 | 10 §10 | RULE-10-02 | `ManageVaccineExpiry` | N/A | `vaccine_batches.expiry_date` |
| REQ-VAC-003 | 10 §10 | RULE-10-03 | `CheckVaccinationSchedule` | N/A | `vaccination_schedules` |
| REQ-VAC-004 | 10 §10 | RULE-10-04, RULE-10-06 | `AdministerVaccine` | N/A | `vaccine_batches` |
| REQ-VAC-005 | 10 §10 | RULE-10-05, RULE-10-06 | `RecordVaccination`, `ScheduleNextVaccination` | N/A | `vaccinations`, `vaccine_batches.quantity_remaining` |
| REQ-VAC-006 | 10 §10 | RULE-10-08 | `AdjustInventory` (liên module 12) | FSM 11 (gián tiếp) | `inventory_adjustments` |
| REQ-VAC-007 | 10 §10 | RULE-10-02 | `ManageVaccine`, `ManageVaccineBatch` | N/A | `vaccine_batches` |

## 11. Module 11 — Grooming Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-GRM-001 | 11 §11 | RULE-11-01 | `CheckInGrooming`, `CancelGrooming` | FSM 15 | `grooming_sessions.status` |
| REQ-GRM-002 | 11 §11 | RULE-11-02 | `InspectPet` | FSM 15 | `health_inspection_reports` |
| REQ-GRM-003 | 11 §11 | RULE-11-03 | `AddGroomingService` | FSM 15 | `grooming_service_lines.is_addon` |
| REQ-GRM-004 | 11 §11 | RULE-11-03 (D-02) | `ConfirmAdditionalService` | FSM 15, Event Bridge #11 | `grooming_sessions.surcharge_invoice_id`, `invoices` |
| REQ-GRM-005 | 11 §11 | RULE-11-03 | `RejectAdditionalService` | FSM 15 | `grooming_service_lines.customer_approved` |
| REQ-GRM-006 | 11 §11 | RULE-11-04 | `UpdateGroomingResult` | FSM 15 | `grooming_sessions` |
| REQ-GRM-007 | 11 §11 | RULE-11-05 | `CompleteGrooming` | FSM 15 | `grooming_sessions.status` |
| REQ-GRM-008 | 11 §11 | RULE-11-06, RULE-21-03 | `AbortGrooming` | FSM 15, Event Bridge #13 | `grooming_sessions.abort_reason`, `incident_reports` |

## 12. Module 12 — Inventory & Warehouse Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-INV-001 | 12 §12 | RULE-12-01 | `TrackInventory` | N/A | `inventory_items` |
| REQ-INV-002 | 12 §12 | RULE-12-02 | `AdjustInventory` | N/A | `inventory_adjustments.reason` |
| REQ-INV-003 | 12 §12 | RULE-12-03 | `ApproveInventoryAdjustment` | N/A | `inventory_adjustments.created_by/approved_by` |
| REQ-INV-004 | 12 §12 | RULE-12-04 | `CreateStockTransfer` | FSM 11 | `stock_transfers` |
| REQ-INV-005 | 12 §12 | RULE-12-05 | `IssueInventory`, `ShipStockTransfer` | FSM 11 | `inventory_items.quantity_available` |
| REQ-INV-006 | 12 §12 | RULE-12-06 | `ApproveStockTransfer`, `RejectStockTransfer` | FSM 11 | `stock_transfers.created_by/approved_by` |
| REQ-INV-007 | 12 §12 | RULE-12-07 | `ShipStockTransfer` | FSM 11 | `stock_transfer_lines` |
| REQ-INV-008 | 12 §12 | RULE-12-08, RULE-12-09 | `ReceiveStockTransfer`, `ReceiveStockTransferWithDiscrepancy`, `ResolveStockTransferDiscrepancy` | FSM 11 | `stock_transfer_lines.damaged_quantity/lost_quantity` |
| REQ-INV-009 | 12 §12 | RULE-12-11 | `TrackBatch`, `TrackExpiry` | N/A | `vaccine_batches`, ERD ghi chú FEFO |
| REQ-INV-010 | 12 §12 | RULE-12-12 | `TriggerLowStockAlert`, `TriggerExpiryWarning` | N/A | `inventory_items.min_stock_level` |
| REQ-INV-011 | 12 §12 | RULE-12-13 | `ManageWarehouse`, `ReceiveAtWarehouse` | N/A | `stores.facility_type = 'CENTRAL_WAREHOUSE'` |
| REQ-INV-012 | 12 §12 | RULE-12-01 | `ReceiveInventory` | N/A | `inventory_items.quantity_physical` |
| REQ-INV-013 | 12 §12 | RULE-12-10 | `CancelStockTransfer` | FSM 11 | `stock_transfers.status` |

## 13. Module 13 — Procurement Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-PRC-001 | 13 §13 | RULE-13-01 | `CreatePurchaseRequest` | FSM 12 | `purchase_requests`, `purchase_request_lines` |
| REQ-PRC-002 | 13 §13 | RULE-13-02 | `ApprovePurchaseRequest`, `RejectPurchaseRequest` | FSM 12 | `purchase_requests` (Maker-Checker) |
| REQ-PRC-003 | 13 §13 | RULE-13-03 | `CancelPurchaseRequest` | FSM 12 | `purchase_requests.status` |
| REQ-PRC-004 | 13 §13 | RULE-13-04 | `CreatePurchaseOrder` | FSM 13 | `purchase_orders`, `purchase_order_lines` |
| REQ-PRC-005 | 13 §13 | RULE-13-05 | `InspectGoods`, `ReceiveGoods` | FSM 13 | `goods_receipts.inspected_by` |
| REQ-PRC-006 | 13 §13 | RULE-13-06 | `UpdateInventory` | FSM 13, Event Bridge #23 | `inventory_items` |
| REQ-PRC-007 | 13 §13 | RULE-13-07 | `CancelRemainingPurchaseOrder` | FSM 13 | `purchase_orders.status` |
| REQ-PRC-008 | 13 §13 | RULE-13-08 | `CancelPurchaseOrder` | FSM 13 | `purchase_orders.status` |
| REQ-PRC-009 | 13 §13 | RULE-13-04 | `ManageSupplier` | N/A | `suppliers` |

## 14. Module 14 — Order Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-ORD-001 | 14 §14 | RULE-14-01 | `CreateOrder`, `ViewOrder` | FSM 5 | `orders` |
| REQ-ORD-002 | 14 §14 | RULE-14-02 | `CreateOrder`, `CheckoutOrder` | FSM 5 | `inventory_items.quantity_available` |
| REQ-ORD-003 | 14 §14 | RULE-14-03 (D-03), RULE-14-05, RULE-14-06 | `CompleteStoreOrder`, `ConfirmOrder`, `ProcessOrder`, `PrepareProductOrder` | FSM 5 | `orders.channel` |
| REQ-ORD-004 | 14 §14 | RULE-14-04 | `CheckoutOrder`, `ProcessOrderTimeout` | FSM 5 | `inventory_reservations.expires_at` |
| REQ-ORD-005 | 14 §14 | RULE-14-07, RULE-14-08 | `CancelOrder` | FSM 5 | `orders.status` |
| REQ-ORD-006 | 14 §14 | RULE-14-07 | `CancelOrderWithRefund` | FSM 5, Event Bridge #9 | `orders.status`, `refunds` |
| REQ-ORD-007 | 14 §14 | RULE-14-08 | N/A (negative constraint) | FSM 5 | `orders.status` |
| REQ-ORD-008 | 14 §14 | RULE-14-07 (D-03) | Event `RefundCompleted` | FSM 5 | `orders.total_refunded_amount` |
| REQ-ORD-009 | 14 §14 | RULE-14-09 | `SendOrderNotification` | N/A | `notification_tasks` |

## 15. Module 15 — Billing & Invoice Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-BIL-001 | 15 §15 | RULE-15-01 | `CreateInvoice`, `ViewInvoice` | FSM 6 | `invoices` |
| REQ-BIL-002 | 15 §15 | RULE-15-02 | `AddServiceToInvoice`, `AddProductToInvoice`, `ApplyDiscount` | FSM 6 | `invoices.discount_amount/total_amount` |
| REQ-BIL-003 | 15 §15 | RULE-15-03 | `IssueInvoice` | FSM 6 | `invoices.status` |
| REQ-BIL-004 | 15 §15 | RULE-15-04 | `DiscardInvoice`, `VoidInvoice` | FSM 6 | `invoices.status` |
| REQ-BIL-005 | 15 §15 | RULE-15-04, RULE-15-07 (D-01) | N/A (negative constraint) | FSM 6 (Technical Invariant #2) | `invoices.status` |
| REQ-BIL-006 | 15 §15, 11 §11 | RULE-15-05 (D-02) | `IssueSurchargeInvoice` | FSM 6 | `invoices.invoice_type = 'SURCHARGE_INVOICE'` |
| REQ-BIL-007 | 15 §15 | RULE-15-06 | Event `FullPaymentSettled` | FSM 6 | `invoices.status`, `payments` |
| REQ-BIL-008 | 15 §15 | RULE-15-07 (D-01) | N/A | FSM 6 | `invoices.total_refunded_amount` |
| REQ-BIL-009 | 15 §15 | RULE-15-01 | `ViewInvoice` | N/A | `invoices` |
| REQ-BIL-010 | 15 §15 | RULE-15-08 | `ReconcileInvoice` | N/A | `invoices`, `payments`, `refunds` |

## 16. Module 16 — Payment Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-PAY-001 | 16 §16 | RULE-16-01 | `MakePayment`, `RecordCashPayment` | FSM 7 | `payments.invoice_id` |
| REQ-PAY-002 | 16 §16 | RULE-16-02 | `MakePayment`, `RecordCashPayment` | FSM 7 | `payments.payment_method` |
| REQ-PAY-003 | 16 §16 | RULE-16-03 | `ReceivePaymentCallback` | FSM 7 | `payments.idempotency_key` |
| REQ-PAY-004 | 16 §16 | RULE-16-04 | Event `PaymentSucceeded` | FSM 7, Event Bridge #3/#4 | `payments.status` |
| REQ-PAY-005 | 16 §16 | RULE-16-05 | `CancelPayment` | FSM 7 | `payments.status` |
| REQ-PAY-006 | 16 §16 | RULE-16-06 | Event `RefundCompleted` | FSM 7, Event Bridge #5 | `payments.status` |
| REQ-PAY-007 | 16 §16 | RULE-16-07 | `ReconcilePayment` | N/A | `payments` |

## 17. Module 17 — Refund Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-RFD-001 | 17 §17 | RULE-17-01 | `RequestRefund`, `CreateRefundRequest` | FSM 8 | `refunds.payment_id` |
| REQ-RFD-002 | 17 §17 | RULE-17-02 | N/A (guard) | FSM 8 | `refunds.amount` |
| REQ-RFD-003 | 17 §17 | RULE-17-03 | N/A (guard) | FSM 8 | `refunds.created_at` vs `payments.created_at` |
| REQ-RFD-004 | 17 §17 | RULE-17-04 | `ApproveRefund`, `RejectRefund` | FSM 8 | `refunds.created_by/approved_by` |
| REQ-RFD-005 | 17 §17 | RULE-17-05 | `ProcessRefund` | FSM 8 | `refunds.status` |
| REQ-RFD-006 | 17 §17 | RULE-17-07 | `RetryRefund`, `FailRefund` | FSM 8 | `refunds.retry_count` |
| REQ-RFD-007 | 17 §17 | RULE-17-08 | `ResolveRefundManually` | FSM 8 | `refund_execution_logs` |
| REQ-RFD-008 | 17 §17 | RULE-17-09 | `CompleteRefund` | FSM 8, Event Bridge #5/#6/#7 | `invoices.total_refunded_amount`, `payments.status`, `orders.total_refunded_amount` |
| REQ-RFD-009 | 17 §17 | RULE-17-10 | `SendRefundNotification` | N/A | `notification_tasks` |
| REQ-RFD-010 | 17 §17 | RULE-17-10 | `ReconcileRefund` | N/A | `refunds` |

## 18. Module 18 — Promotion & Voucher Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-PRM-001 | 18 §18 | RULE-18-01 | `CreatePromotion`, `ManagePromotion` | FSM 18 | `promotion_campaigns` |
| REQ-PRM-002 | 18 §18 | RULE-18-02 | `ConfigureStorePromotion` | N/A | UNTRACED (không có bảng `store_promotions` trong `06`) |
| REQ-PRM-003 | 18 §18 | RULE-18-03 | `CreateVoucher` | FSM 19 | `vouchers` |
| REQ-PRM-004 | 18 §18 | RULE-18-04 | `ValidateVoucher`, `UseVoucher` | N/A | `vouchers` |
| REQ-PRM-005 | 18 §18 | RULE-18-05 | `TrackVoucherUsage` | N/A | `vouchers.total_usage_limit/used_count`, `voucher_usages` |
| REQ-PRM-006 | 18 §18 | RULE-18-06 | `TrackVoucherUsage` | N/A | `voucher_usages` |
| REQ-PRM-007 | 18 §18 | RULE-18-07 | N/A (System trigger khi hủy đơn) | N/A | UNTRACED (không có cơ chế hoàn Voucher tường minh trong `06`) |
| REQ-PRM-008 | 18 §18 | RULE-18-08 | N/A (guard, tính toán checkout) | N/A | N/A |
| REQ-PRM-009 | 18 §18 | RULE-18-01, RULE-18-03 | `ManagePromotion`, `ManageVoucher`, `ProcessPromotionExpiry`, `ProcessVoucherExpiry` | FSM 18, FSM 19 | `promotion_campaigns.status`, `vouchers.status` |

## 19. Module 19 — Membership & Loyalty Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-MEM-001 | 19 §19 | RULE-19-01 | `RegisterMembership`, `ViewMembership`, `ViewLoyaltyPoint` | FSM 9 | `memberships` |
| REQ-MEM-002 | 19 §19 | RULE-19-03 | `RenewMembership` | FSM 9 | `memberships.valid_until` |
| REQ-MEM-003 | 19 §19 | RULE-19-04 | `UpgradeMembership` | FSM 9 | `memberships.tier` |
| REQ-MEM-004 | 19 §19 | RULE-19-02 | `ProcessMembershipExpiry` | FSM 9 (Technical Invariant #4) | `memberships.status` |
| REQ-MEM-005 | 19 §19 | RULE-19-05 | `AddLoyaltyPoint` | N/A | `loyalty_point_ledgers` |
| REQ-MEM-006 | 19 §19 | RULE-19-06, RULE-19-07 | `RedeemLoyaltyPoint`, `ExpireLoyaltyPoint` | N/A | `loyalty_point_ledgers` |
| REQ-MEM-007 | 19 §19 | RULE-19-08 | `AdjustLoyaltyPoint` | N/A | `loyalty_point_ledgers.transaction_type = 'MANUAL_ADJUSTMENT'` |
| REQ-MEM-008 | 19 §19 | RULE-19-10 | N/A (negative constraint) | N/A | `memberships.organization_id` |
| REQ-MEM-009 | 19 §19 | RULE-19-09 | N/A (guard, kích hoạt bởi `LockAccount`) | N/A | `memberships`, `accounts.status` |

## 20. Module 20 — Package Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-PKG-001 | 20 §20 | RULE-20-01 | `PurchasePackage` | FSM 10 | `service_packages` |
| REQ-PKG-002 | 20 §20 | RULE-20-01 | `ActivatePackage` | FSM 10 | `service_packages.status` |
| REQ-PKG-003 | 20 §20 | RULE-20-02 | `ConfirmPackageUsage` | FSM 10 | `service_packages.remaining_units` |
| REQ-PKG-004 | 20 §20 | RULE-20-03 | `ProcessPackageExpiry` | FSM 10 | `service_packages.expires_at` |
| REQ-PKG-005 | 20 §20 | RULE-20-04 | `TrackPackageUsage` | N/A | `package_usage_records` |
| REQ-PKG-006 | 20 §20 | RULE-20-06 | `CancelPackage` | FSM 10, Event Bridge #26 | `service_packages.status`, `refunds` |
| REQ-PKG-007 | 20 §20 | RULE-20-08 | `RefundPackageUnit` | FSM 10 (không thể hiện transition trong mermaid — chỉ mô tả bằng văn bản) | `package_usage_records.consumption_type` |
| REQ-PKG-008 | 20 §20 | RULE-20-07 | `AdjustPackage` | FSM 10 | `service_packages.remaining_units` |
| REQ-PKG-009 | 20 §20 | RULE-20-05 | `ViewPackage`, `ConfirmPackageUsage` | FSM 10 | `service_packages.user_id` |
| REQ-PKG-010 | 20 §20 | RULE-20-09 | `HoldSlot`, `BookAppointment` | N/A (prose invariant, không có mermaid transition riêng) | `booking_holds.service_package_id`, `appointments.service_package_id` |

## 21. Module 21 — Incident Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-INC-001 | 21 §21 | RULE-21-01 | `RecordIncident`, `RecordClinicalIncident`, `RecordGroomingIncident` | FSM 14 | `incident_reports.category` |
| REQ-INC-002 | 21 §21 | RULE-21-02, RULE-21-03 | N/A (System trigger từ `AbortAppointment`/`AbortGrooming`) | FSM 14 | `incident_reports` |
| REQ-INC-003 | 21 §21 | RULE-21-04 | `ClassifyIncident` | FSM 14 | `incident_reports.severity` |
| REQ-INC-004 | 21 §21 | RULE-21-05 | `SendIncidentNotification` | N/A | `notification_tasks` |
| REQ-INC-005 | 21 §21 | RULE-21-06 | `EscalateIncident` | FSM 14 | `incident_reports.status` |
| REQ-INC-006 | 21 §21 | RULE-21-07 | `InvestigateIncident`, `HandleIncident` | FSM 14 | `incident_reports` |
| REQ-INC-007 | 21 §21 | RULE-21-08 | `CloseIncident` | FSM 14 | `incident_reports.status` |

## 22. Module 22 — Consent & Privacy Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-CNS-001 | 22 §22 | RULE-22-01 | `GrantConsent`, `RevokeConsent` | N/A | UNTRACED (không có bảng `consents` chung, chỉ có `cross_store_consents` chuyên biệt) |
| REQ-CNS-002 | 22 §22 | RULE-22-07 | N/A (guard áp dụng cho mọi truy cập EMR) | N/A | N/A |
| REQ-CNS-003 | 22 §22, 09 §9 | RULE-22-02, RULE-22-08 | `RequestCrossStoreConsent`, `VerifyCrossStoreConsentOTP` | FSM 16 | `cross_store_consents` |
| REQ-CNS-004 | 22 §22 | RULE-22-03 | `RevokeCrossStoreConsent` | FSM 16 | `cross_store_consents.status` |
| REQ-CNS-005 | 22 §22 | RULE-09-02, RULE-21-02, RULE-22-08 | `EmergencyOverrideAccess` | FSM 16 | `cross_store_consents.is_emergency/emergency_reason` |
| REQ-CNS-006 | 22 §22 | RULE-22-09 | `RequestDataExport`, `ProcessDataExport` | N/A | UNTRACED (không có bảng `data_exports` trong `06`) |
| REQ-CNS-007 | 22 §22 | RULE-22-04 | `RequestDataDeletion`, `ProcessDataDeletion` | N/A | UNTRACED (không có bảng `data_deletion_requests` trong `06`) |
| REQ-CNS-008 | 22 §22 | RULE-22-05, RULE-22-06 | `ManagePrivacyPolicy`, `ManageRetentionPolicy` | N/A | UNTRACED (không có bảng policy tương ứng trong `06`, chỉ có `system_configs` tổng quát) |
| REQ-CNS-009 | 22 §22 | RULE-22-02, RULE-22-10 | `ProcessConsentExpiry` | FSM 16 | `cross_store_consents.expires_at` |

## 23. Module 23 — Notification Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-NOT-001 | 23 §23 | RULE-23-01 | `SendNotification` | N/A | `notification_tasks.recipient_user_id/channel` |
| REQ-NOT-002 | 23 §23 | RULE-23-02 | `SendAppointmentNotification`, `SendPaymentNotification`, `SendOrderNotification`, v.v. | N/A | `notification_tasks.event_type` |
| REQ-NOT-003 | 23 §23 | RULE-23-03 | `RetryNotification` | N/A | `notification_delivery_logs` |
| REQ-NOT-004 | 23 §23 | RULE-23-04 | `ViewNotification` | N/A | `notification_tasks` |
| REQ-NOT-005 | 23 §23 | RULE-23-05 | N/A (policy, liên module 22) | N/A | UNTRACED (không có cờ opt-in/opt-out trong `06`) |
| REQ-NOT-006 | 23 §23 | RULE-23-06 | N/A (negative constraint) | N/A | `notification_tasks.content` |

## 24. Module 24 — Reporting & Analytics

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-RPT-001 | 24 §24 | RULE-24-01 | `ViewRevenueReport`, `ViewAppointmentReport`, `ViewServiceReport`, `ViewInventoryReport`, `ViewStaffReport` | N/A | Read-only views (không có bảng vật lý trong `06`) |
| REQ-RPT-002 | 24 §24 | RULE-24-01, RULE-24-03, RULE-24-04 | `ViewOrganizationRevenue`, `CompareStoreRevenue` | N/A | UNTRACED |
| REQ-RPT-003 | 24 §24 | RULE-24-01 | `ViewPlatformReport` | N/A | UNTRACED |
| REQ-RPT-004 | 24 §24 | RULE-24-02 | N/A (negative constraint) | N/A | N/A |
| REQ-RPT-005 | 24 §24 | RULE-24-05 | `ReconcileRevenue` | N/A | `invoices`, `payments`, `refunds` |
| REQ-RPT-006 | 24 §24 | RULE-24-06 | `ViewCustomerPetReport` | N/A | UNTRACED |
| REQ-RPT-007 | 24 §24 | RULE-24-07 | N/A (negative constraint) | N/A | UNTRACED |

## 25. Module 25 — Audit Management

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| REQ-AUD-001 | 25 §25 | RULE-25-01 | `RecordAuditLog` | N/A | `audit_logs` |
| REQ-AUD-002 | 25 §25 | RULE-25-02, RULE-25-08 | N/A (negative constraint) | N/A | `audit_logs` |
| REQ-AUD-003 | 25 §25 | RULE-25-03 | `TrackPermissionChange` | N/A | `audit_logs.action` |
| REQ-AUD-004 | 25 §25 | RULE-25-04 | `TrackMedicalRecordAccess` | N/A | `audit_logs` |
| REQ-AUD-005 | 25 §25 | RULE-25-05 | `TrackPaymentRefundAudit` | N/A | `audit_logs` |
| REQ-AUD-006 | 25 §25 | RULE-25-06 | `TrackInventoryAudit` | N/A | `audit_logs` |
| REQ-AUD-007 | 25 §25 | RULE-25-07 | `ViewAuditLog` | N/A | `audit_logs` |

## 26. Non-Functional & Security Requirements

| Requirement | Source | Rule | Operation/UC | State Machine | Domain/ERD |
|---|---|---|---|---|---|
| NFR-REL-001 | 03 §19 | RULE-16-03 | N/A (cross-cutting) | N/A | `outbox_events` |
| NFR-REL-002 | 02 Module 14, 06 | RULE-14-04, RULE-06-10 | N/A | FSM 5, FSM 4.2 | `inventory_items.version`, `appointments.version` |
| NFR-REL-003 | 02 Module 06/14 | RULE-06-01, RULE-14-04 | `ExpireHold`, `ProcessOrderTimeout` | FSM 4.1, FSM 5 | `booking_holds.expires_at`, `inventory_reservations.expires_at` |
| NFR-PERF-001 | 02 Module 24 | RULE-24-08 | N/A | N/A | N/A |
| NFR-PERF-002 | 02 Module 14 | RULE-14-04 | `CreateOrder` (POS) | FSM 5 | N/A |
| NFR-AVAIL-001 | 02 Module 23, 03 §19 | RULE-23-03 | `RetryNotification` | N/A | `notification_tasks.status` |
| NFR-DATA-001 | 06 RHD-DB-02 | UNTRACED | N/A | N/A | `products.base_unit/purchase_unit_conversion_factor` |
| NFR-DATA-002 | 06 §1 | UNTRACED | N/A | N/A | `BaseEntity` (created_by/updated_by/deleted_at/version) |
| NFR-SCAL-001 | 02 Module 02/03 | RULE-02-01 | N/A | N/A | N/A |
| NFR-USE-001 | 02 (toàn bộ mã lỗi) | rải rác toàn bộ `02` | N/A | N/A | N/A |
| REQ-SEC-001 | 01 §1, 02 §2 | RULE-01-09 | `RegisterAccount`, `CreateStaff` | FSM 1 | `accounts.password_hash` |
| REQ-SEC-002 | 01/02 | RULE-01-06, RULE-02-04, RULE-02-07 | `Logout`, `LockAccount` | FSM 1 | N/A |
| REQ-SEC-003 | 01 | RULE-01-05, RULE-01-07 | `CheckOTP`, `Login` | FSM 1 | `accounts.failed_login_attempts` |
| REQ-SEC-004 | 02 | RULE-02-01 | N/A | N/A | N/A |
| REQ-SEC-005 | 04 §26.6 | RULE-12-03, RULE-12-06, RULE-13-02, RULE-17-04 | `ApproveRefund`, `ApproveStockTransfer`, `ApprovePurchaseRequest`, `ApproveInventoryAdjustment` | FSM 8, 11, 12 | 4 bảng tương ứng (`created_by`/`approved_by`) |
| REQ-SEC-006 | 22 | RULE-22-07 | N/A | N/A | N/A |
| REQ-SEC-007 | 09/21/22 | RULE-09-02, RULE-21-02, RULE-22-08 | `EmergencyOverrideAccess` | FSM 16 | `cross_store_consents`, `incident_reports`, `audit_logs` |
| REQ-SEC-008 | 16 | RULE-16-03 | `ReceivePaymentCallback` | FSM 7 | `payments.idempotency_key` |
| REQ-SEC-009 | 25 | RULE-25-02, RULE-25-08 | N/A | N/A | `audit_logs` |
| REQ-SEC-010 | 02/03/24 | RULE-02-01, RULE-03-01, RULE-24-02 | N/A | N/A | `organization_id` FK toàn hệ thống |
| REQ-SEC-011 | 23 | RULE-23-06 | N/A | N/A | `notification_tasks.content` |
| REQ-SEC-012 | 22 | RULE-22-04 | `ProcessDataDeletion` | N/A | UNTRACED |

---