import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { resetClinicDb, seedClinicDb } from '../../shared/api/clinic-db';
import { apiClient } from '../../shared/api/axios';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { salesApi } from '../../shared/api/sales.api';
import { usePosStore } from '../../shared/stores/pos.store';
import { pinClock, signInAs } from '../../test/cashier-harness';
import { ReceptionWorkspacePage } from './ReceptionWorkspacePage';

// Mock sales / cashier: Order #2 (khách #1 Nguyễn Thu Trang) đang chờ thu 266.000. Lễ tân #4 chưa có ca OPEN. Đồng hồ ghim thứ Năm 22/10/2026 10:00.
// Trong jsdom `matchMedia` luôn false nên bàn lễ tân ở dạng điện thoại: phiếu thu chỉ hiện ở tab "Phiếu thu".

beforeAll(() => installMockApi(apiClient));

beforeEach(() => {
  localStorage.clear();
  pinClock();
  resetClinicDb(seedClinicDb());
  usePosStore.getState().clear();
});

afterEach(() => vi.useRealTimers());

function renderDesk() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ReceptionWorkspacePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

async function receptionWithShift() {
  await signInAs('reception@store1.vn');
  await salesApi.openShift({ afterHours: false });
}

describe('reception desk — thu tiền (T35)', () => {
  it('Ctrl+Enter thu tiền khi phiếu thu đang hiện và đủ điều kiện', async () => {
    await receptionWithShift();
    usePosStore.getState().attachOrder(2, 1);
    const user = renderDesk();
    await screen.findByText(/^Ca mở lúc/);
    await user.click(screen.getByRole('button', { name: /^Phiếu thu/ }));
    expect(await screen.findByRole('button', { name: /^Thu 266\.000/ })).toBeEnabled();

    await user.keyboard('{Control>}{Enter}{/Control}');

    expect(await screen.findByRole('dialog')).toHaveTextContent(/Đã thu 266\.000/);
    expect((await salesApi.getOrder(2)).status).toBe('PAID');
  });

  it('never takes payment while the check-in dialog is open', async () => {
    await receptionWithShift();
    usePosStore.getState().attachOrder(2, 1);
    const user = renderDesk();
    await screen.findByText(/^Ca mở lúc/);
    await user.click(screen.getByRole('button', { name: /^Phiếu thu/ }));
    expect(await screen.findByRole('button', { name: /^Thu 266\.000/ })).toBeEnabled();

    await user.click(screen.getByRole('button', { name: /Tiếp nhận khách/ }));
    await screen.findByRole('dialog');
    await user.keyboard('{Control>}{Enter}{/Control}');
    await new Promise((r) => setTimeout(r, 100));

    expect((await salesApi.getOrder(2)).status).toBe('PENDING');
  });

  it('đầu trang có chip ca và nút Chốt ca; thẻ "Phiếu thu" đếm số Order đang chọn', async () => {
    await receptionWithShift();
    usePosStore.getState().attachOrder(2, 1);
    renderDesk();

    expect(await screen.findByText(/^Ca mở lúc \d{2}:\d{2} · 0 phiếu thu$/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Chốt ca' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Phiếu thu/ })).toHaveTextContent('1');
  });

  it('chưa mở ca: chip nhắc mở ca, phiếu thu chỉ có nút mở ca', async () => {
    await signInAs('reception@store1.vn');
    const user = renderDesk();

    expect(await screen.findByText('Chưa mở ca thu ngân', { selector: '.ws-chip' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /^Phiếu thu/ }));
    expect(await screen.findByRole('button', { name: 'Mở ca thường' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Chốt ca' })).not.toBeInTheDocument();
  });

  // T34: bán lẻ đi qua Order gắn hồ sơ khách (UC66, sales-v1 A1), không thả hàng thẳng vào phiếu thu nữa.
  it('starts a retail sale from a customer profile, never straight onto the receipt', async () => {
    await signInAs('reception@store1.vn');
    renderDesk();
    expect(await screen.findByPlaceholderText('SĐT, họ tên hoặc email của khách')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText('Tìm sản phẩm, Enter để thêm')).not.toBeInTheDocument();
    expect('retail' in usePosStore.getState()).toBe(false);
  });
});

describe('reception desk — quản lý chi nhánh (A05)', () => {
  it('thấy lời nhắc thay cho phiếu thu, danh sách Chờ thu không có nút Thu tiền, và không gửi lệnh gọi thu ngân nào (không 403)', async () => {
    const myShift = vi.spyOn(salesApi, 'getMyShift');
    const payable = vi.spyOn(salesApi, 'listPayableOrders');
    await signInAs('manager@store1.vn');
    usePosStore.getState().attachOrder(2, 1);
    const user = renderDesk();

    await user.click(screen.getByRole('tab', { name: /Chờ thu/ }));
    await screen.findByText(/DH-\d{4}-00002/);
    await user.click(screen.getByRole('button', { name: /^Phiếu thu/ }));
    expect(await screen.findByText('Thu tiền và mở / chốt ca là việc của lễ tân. Đối soát ca ở trang Ca thu ngân.')).toBeInTheDocument();

    expect(screen.queryByRole('button', { name: /Thu tiền|Chốt ca|Mở ca/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/ca thu ngân/i, { selector: '.ws-chip' })).not.toBeInTheDocument();
    expect(myShift).not.toHaveBeenCalled();
    expect(payable).not.toHaveBeenCalled();
    await waitFor(() => expect(within(screen.getByRole('tab', { name: /Chờ thu/ })).getByText('3')).toBeInTheDocument());
  });
});
