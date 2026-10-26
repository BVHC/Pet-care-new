// Dùng chung cho test màn hình tiếp nhận, hàng đợi, gọi lượt (T36): đồng hồ ghim thứ Năm 22/10/2026 10:40 giờ Việt Nam
// (chi nhánh 1 đang mở cửa; lịch mẫu #1 Mochi hẹn 11:00 đã vào cửa sổ check-in 10:30–11:30), đăng nhập bằng mock.
// Gọi `pinClock` TRƯỚC khi mock nạp dữ liệu lần đầu (dữ liệu mẫu tính theo giờ nạp).
import type { ReactElement } from 'react';
import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import type { StaffResponse } from '../shared/types/identity';
import type { Visit } from '../shared/types/visit';
import { pinClock, signInAs } from './cashier-harness';

export { pinClock, signInAs };

export const THURSDAY_1040 = '2026-10-22T10:40:00+07:00';

/** Mốc giờ trong cùng ngày thứ Năm 22/10/2026 (giờ Việt Nam). */
export const at = (hhmm: string) => `2026-10-22T${hhmm}:00+07:00`;

/** Đồng hồ ghim + xóa dữ liệu mock của lần trước + đăng nhập. */
export async function startDesk(email: string, clock: string = THURSDAY_1040) {
  localStorage.clear();
  pinClock(clock);
  await signInAs(email);
}

/** Vẽ vào QueryClient mới (không thử lại) trong MemoryRouter; userEvent dùng timer thật. */
export function renderDesk(ui: ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
  return { user: userEvent.setup(), client };
}

/** Một lượt đủ 22 trường của BE (chưa gán, WAITING, walk-in); `over` ghi đè từng trường. */
export const makeVisit = (visitId: number, over: Partial<Visit> = {}): Visit => ({
  visitId,
  code: `TN-2610-${String(visitId).padStart(5, '0')}`,
  branchId: 1,
  customerId: 1,
  customerName: 'Khách',
  petId: visitId,
  petName: `Thú ${visitId}`,
  species: 'DOG',
  appointmentId: null,
  serviceGroup: 'MEDICAL',
  status: 'WAITING',
  priorityClass: 'WALK_IN',
  queueSortKey: '2026-10-22T03:00:00Z',
  assigneeId: null,
  assigneeName: null,
  needsReassign: false,
  orderId: visitId,
  checkedInAt: '2026-10-22T03:00:00Z',
  calledAt: null,
  completedAt: null,
  cancelledAt: null,
  cancelReason: null,
  ...over,
});

export const makeStaff = (accountId: number, over: Partial<StaffResponse> = {}): StaffResponse => ({
  accountId,
  email: `nv${accountId}@store1.vn`,
  phone: '0900000000',
  fullName: `Nhân viên ${accountId}`,
  role: 'VET',
  status: 'ACTIVE',
  isLocked: false,
  branchId: 1,
  mustChangePassword: false,
  online: true,
  ...over,
});

export const pageOf = <T,>(content: T[]) => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1 });
