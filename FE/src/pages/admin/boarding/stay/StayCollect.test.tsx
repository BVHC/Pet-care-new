import { screen, waitFor, within } from '@testing-library/react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { CheckoutPanel } from '@/components/staff/reception/CheckoutPanel';
import { boardingApi } from '@/shared/api/boarding.api';
import { salesApi } from '@/shared/api/sales.api';
import { usePosStore } from '@/shared/stores/pos.store';
import type { Booking } from '@/shared/types/boarding';
import { CheckoutDialog } from './CheckoutDialog';
import { ACCOUNTS, bookingOf, installStayMock, renderDialog, rexInStay, signIn, startDay } from './stay-harness';

// A11 (T48): Order BOARDING PENDING sinh từ start-checkout phải HIỆN trong "Order chờ thu" của bàn Lễ tân và THU ĐƯỢC ở đó (ca mở → POST /payments → PAID),
// rồi hộp thoại Trả thú mở lại cho giao thú. Chạy bằng chính CheckoutPanel của bàn Lễ tân (không giả) + CheckoutDialog + store usePOS.
// Thú dùng: Rex (chó khách #1 Nguyễn Thu Trang, có trong mock khách) nhận hôm nay 23/10 09:00 → trả hôm nay = 1 đêm = 200.000 đ.

beforeAll(installStayMock);
beforeEach(startDay);
afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

/** Trang lưu trú giả: giữ `booking` đã cũ (activeOrderId null) như danh sách chưa làm mới, mở / đóng hộp thoại Trả thú. */
function StayHost({ booking }: { booking: Booking }) {
  const [open, setOpen] = useState(true);
  return open ? <CheckoutDialog booking={booking} onClose={() => setOpen(false)} /> : <p>Đã đóng hộp thoại trả thú</p>;
}

function DeskPage() {
  return (
    <>
      <CheckoutPanel hotkeysEnabled />
      <Link to="/admin/boarding">Về trang lưu trú</Link>
    </>
  );
}

const money = (n: string) => new RegExp(`${n.replace(/\./g, '\\.')}\\s*₫`);

describe('A11 — thu tiền Order lưu trú ở bàn Lễ tân', () => {
  it('Order BOARDING PENDING hiện trong Order chờ thu của khách (payable-orders) với nguồn Lưu trú và đúng số tiền', async () => {
    await signIn(ACCOUNTS.desk);
    const rex = await rexInStay();
    const { orderId, amount } = await boardingApi.startCheckout(rex.bookingId);
    const payable = await salesApi.listPayableOrders(rex.customerId);
    expect(payable).toEqual(expect.arrayContaining([expect.objectContaining({ orderId, source: 'BOARDING', status: 'PENDING', totalAmount: amount, customerId: 1 })]));
  });

  it('vòng đủ chiều: Bắt đầu → Thu tiền tại bàn Lễ tân → phiếu thu liệt kê Order Lưu trú → thu → về lại thì Giao thú bật → giao', async () => {
    await signIn(ACCOUNTS.desk);
    const rex = await rexInStay();
    await salesApi.openShift({ afterHours: false });
    const stale = await bookingOf(rex.bookingId);
    expect(stale.activeOrderId).toBeNull();
    const { user } = renderDialog(<StayHost booking={stale} />, { routes: { '/staff/reception': <DeskPage /> } });

    // 1) bắt đầu trả thú
    await user.click(await screen.findByRole('button', { name: 'Bắt đầu trả thú' }));
    await screen.findByRole('button', { name: 'Thu tiền tại bàn Lễ tân' });
    const dialog = screen.getByRole('dialog', { name: 'Trả thú' });
    const code = within(dialog).getByText(/DH-\d{4}-\d{5}/).textContent!;
    expect(within(dialog).getByText(money('200.000'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Giao thú' })).toBeDisabled();

    // 2) sang bàn Lễ tân: khách + Order đã nằm trong phiếu thu
    await user.click(screen.getByRole('button', { name: 'Thu tiền tại bàn Lễ tân' }));
    const box = await screen.findByRole('checkbox', { name: `Thu Order ${code}` });
    expect(box).toBeChecked();
    expect(screen.getByTestId('where')).toHaveTextContent('/staff/reception');
    const list = screen.getByRole('list', { name: 'Order chờ thu của khách' });
    expect(within(list).getByText(code)).toBeInTheDocument();
    expect(within(list).getByText('Lưu trú')).toBeInTheDocument();
    expect(within(list).getByText(money('200.000'))).toBeInTheDocument();

    // 3) thu tiền (ca đang mở): POST /payments → Order PAID
    await user.click(await screen.findByRole('button', { name: /^Thu 200\.000\s*₫/ }));
    const receipt = await screen.findByRole('dialog');
    expect(within(receipt).getByText(money('Đã thu 200.000'))).toBeInTheDocument();
    expect(within(receipt).getByText(code)).toBeInTheDocument();
    await user.click(within(receipt).getByRole('button', { name: 'Xong' }));
    expect((await salesApi.getOrder((await bookingOf(rex.bookingId)).activeOrderId!)).status).toBe('PAID');
    await waitFor(() => expect(usePosStore.getState().orderIds).toEqual([]));

    // 4) quay lại trang lưu trú (danh sách vẫn giữ bản cũ): hộp thoại mở lại thấy PAID và bật Giao thú
    await user.click(screen.getByRole('link', { name: 'Về trang lưu trú' }));
    const handOver = await screen.findByRole('button', { name: 'Giao thú' });
    await waitFor(() => expect(handOver).toBeEnabled());
    expect(screen.getByText('Đã thanh toán')).toBeInTheDocument();
    await user.click(handOver);
    expect(await screen.findByText('Đã đóng hộp thoại trả thú')).toBeInTheDocument();
    expect(await bookingOf(rex.bookingId)).toMatchObject({ status: 'CHECKED_OUT', endReason: 'RETURNED' });
  });

  // Đặt chỗ mẫu Cốm / Mực thuộc khách tại quầy 9002 / 9003 chưa có hồ sơ trong mock khách: mock tra được họ theo id (registerCounterGuests) nên GET /customers/9002/payable-orders
  it('Order lưu trú của đặt chỗ mẫu Cốm (khách 9002) hiện trong Order chờ thu của khách', async () => {
    await signIn(ACCOUNTS.desk);
    const { orderId } = await boardingApi.startCheckout(2);
    const payable = await salesApi.listPayableOrders(9002);
    expect(payable.map((o) => o.orderId)).toContain(orderId);
  });
});
