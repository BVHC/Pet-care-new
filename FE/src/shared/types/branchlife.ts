// Vòng đời chi nhánh (UC13) — docs/api/branchlife-v1.md, openapi/branchlife-v1.yaml. Tầng 3, BE chưa cài; kiểu bám schema yaml.
// `DRAFT → ACTIVE` ở branch-v1 (`POST /branches/{id}/activate`), module này lo ACTIVE ⇄ SUSPENDED và → CLOSED.

/** 03 #2 và #20. */
export type BranchLifecycleStatus = 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | 'CLOSED';

export const BRANCH_LIFECYCLE_LABELS: Record<BranchLifecycleStatus, string> = {
  DRAFT: 'Nháp',
  ACTIVE: 'Đang hoạt động',
  SUSPENDED: 'Tạm ngừng',
  CLOSED: 'Đã đóng cửa',
};

/** `maxLength` của `reason` trong SuspendBranchRequest / CloseBranchRequest. */
export const BRANCH_LIFECYCLE_LIMITS = { reason: 300 } as const;

/** #1: tồn đọng của chi nhánh. `*Blockers` rỗng = làm được ngay (BR-CN-08). */
export interface ClosureImpact {
  /** Lịch hẹn và đặt chỗ lưu trú `BOOKED` (hủy hàng loạt được, BR-CN-09). */
  bookedAppointments: number;
  /** Thú đang trong chuồng (`CHECKED_IN` / `OVERDUE`). */
  activeBoardingStays: number;
  openVisits: number;
  openCashierShifts: number;
  /** Order `OPEN` / `PENDING`. */
  openOrders: number;
  unfinishedOnlineOrders: number;
  openCareTasks: number;
  pendingRefunds: number;
  inTransitTransfers: number;
  openStockCounts: number;
  stockItemCount: number;
  stockTotalQuantity: number;
  activeStaff: number;
  suspendBlockers: string[];
  closeBlockers: string[];
}

export interface SuspendBranchRequest {
  reason: string;
  expectedResumeOn?: string | null;
  cancelBookings: boolean;
  careTaskTargetBranchId?: number | null;
}

export interface CloseBranchRequest {
  reason: string;
  cancelBookings: boolean;
  careTaskTargetBranchId?: number | null;
}

export interface BranchLifecycle {
  branchId: number;
  status: BranchLifecycleStatus;
  suspendedAt?: string | null;
  expectedResumeOn?: string | null;
  closedAt?: string | null;
  reason?: string | null;
}
