import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Booking } from '@/shared/types/boarding';
import { BookingDetailDialog } from './BookingDetailDialog';

// Hàng Order của hộp "Chi tiết lưu trú": BE chỉ trả `activeOrderId` cho Order OPEN / PENDING (chưa thu); Order đã PAID thì null.
// Vì vậy hàng chỉ hiện khi có id, nhãn "Order chờ thu"; null thì không hiện hàng (không còn "—" làm tưởng chưa từng có Order).

const base: Booking = {
  bookingId: 7,
  code: 'LT-2611-00001',
  customerId: 9002,
  customerName: 'Vũ Thanh Hà',
  petId: 2,
  petName: 'Cốm',
  branchId: 1,
  kennelTypeId: 5,
  kennelTypeName: 'Chuồng chó nhỏ',
  kennelId: 3,
  kennelCode: 'C-03',
  checkInDate: '2026-11-01',
  checkOutDate: '2026-11-03',
  actualCheckInAt: '2026-11-01T09:00:00+07:00',
  nightlyPrice: 200000,
  status: 'CHECKED_IN',
  channel: 'COUNTER',
  lateCancel: false,
  missingCareLogToday: false,
};

function open(overrides: Partial<Booking>) {
  render(<BookingDetailDialog booking={{ ...base, ...overrides }} onClose={vi.fn()} />);
  return screen.getByRole('dialog', { name: 'Chi tiết lưu trú LT-2611-00001' });
}

describe('BookingDetailDialog — hàng Order', () => {
  it('có activeOrderId: hiện "Order chờ thu" và #<id>', () => {
    const dialog = open({ activeOrderId: 14 });
    const row = within(dialog).getByText('Order chờ thu', { exact: true }).closest('div')!;
    expect(row).toHaveTextContent('#14');
  });

  it('activeOrderId null: không có hàng Order nào, cũng không còn nhãn "Order lưu trú"', () => {
    const dialog = open({ activeOrderId: null });
    expect(within(dialog).queryByText('Order chờ thu')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('Order lưu trú')).not.toBeInTheDocument();
    expect(dialog).not.toHaveTextContent('Order');
  });

  it('đặt chỗ đã giao thú (CHECKED_OUT, Order đã PAID nên activeOrderId null): không hàng Order', () => {
    const dialog = open({
      status: 'CHECKED_OUT',
      endReason: 'RETURNED',
      actualCheckOutAt: '2026-11-03T10:00:00+07:00',
      activeOrderId: null,
    });
    expect(dialog).toHaveTextContent('Đã trả thú');
    expect(within(dialog).queryByText('Order chờ thu')).not.toBeInTheDocument();
    expect(within(dialog).queryByText('Order lưu trú')).not.toBeInTheDocument();
    expect(dialog).not.toHaveTextContent('Order');
  });

  it('activeOrderId không có hẳn (undefined): không hàng Order', () => {
    const dialog = open({ activeOrderId: undefined });
    expect(dialog).not.toHaveTextContent('Order');
  });
});
