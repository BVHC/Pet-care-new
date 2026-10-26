import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { salesApi } from '../../shared/api/sales.api';
import { visitApi } from '../../shared/api/visit.api';
import { usePosStore } from '../../shared/stores/pos.store';
import { pinClock, signInAs } from '../../test/cashier-harness';
import { at } from '../../test/visit-harness';
import { ReceptionWorkspacePage } from './ReceptionWorkspacePage';

// Mock sales / cashier: Order #2 (khách #1 Nguyễn Thu Trang) đang chờ thu 266.000. Lễ tân #4 chưa có ca OPEN. Đồng hồ ghim thứ Năm 22/10/2026 10:00.
// Trong jsdom `matchMedia` luôn false nên bàn lễ tân ở dạng điện thoại: phiếu thu chỉ hiện ở tab "Phiếu thu".

beforeAll(async () => {
  installMockApi(apiClient);
  // Hộp thoại tiếp nhận tải lười: nạp trước để test mở hộp thoại không phải chờ bước biên dịch lần đầu.
  await import('../../components/staff/reception/CheckInDialog');
});

beforeEach(() => {
  localStorage.clear();
  pinClock();
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

// T36: tiếp nhận, hàng chờ, phím tắt. Lịch hẹn mẫu #1 (Mochi, Nguyễn Thu Trang, Khám tổng quát 11:00) vào cửa sổ check-in từ 10:30.
describe('reception desk — tiếp nhận và hàng chờ (T36, UC44–UC45)', () => {
  it('phím N mở hộp thoại tiếp nhận khách vãng lai; Esc đóng', async () => {
    await signInAs('reception@store1.vn');
    const user = renderDesk();
    await screen.findByRole('button', { name: /Tiếp nhận khách/ });

    await user.keyboard('n');
    expect(await screen.findByRole('dialog', { name: 'Tiếp nhận khách vãng lai' })).toBeInTheDocument();

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('phím F1 sang khu bán hàng và đưa con trỏ vào ô tìm khách', async () => {
    await signInAs('reception@store1.vn');
    const user = renderDesk();
    await screen.findByRole('button', { name: /Tiếp nhận khách/ });

    await user.keyboard('{F1}');

    await waitFor(() => expect(screen.getByPlaceholderText('SĐT, họ tên hoặc email của khách')).toHaveFocus());
  });

  it('"Tiếp nhận" ở lịch hẹn mở hộp thoại điền sẵn; tiếp nhận xong lượt có trong hàng chờ và số trên tab tăng', async () => {
    pinClock(at('10:40'));
    await signInAs('reception@store1.vn');
    const checkIn = vi.spyOn(visitApi, 'checkIn');
    const user = renderDesk();

    await waitFor(() => expect(screen.getByRole('tab', { name: /Hàng chờ/ })).toHaveTextContent('5'));
    await user.click(await screen.findByRole('button', { name: 'Tiếp nhận' }));
    const dialog = within(await screen.findByRole('dialog', { name: 'Tiếp nhận lịch hẹn' }));
    expect(dialog.getByText('Mochi')).toBeInTheDocument();
    expect(dialog.getByText('Khám tổng quát')).toBeInTheDocument();
    await user.click(dialog.getByRole('button', { name: 'Tiếp nhận' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(checkIn).toHaveBeenCalledWith({ appointmentId: 1, petId: 1 });
    await waitFor(() => expect(screen.getByRole('tab', { name: /Hàng chờ/ })).toHaveTextContent('6'));
    await waitFor(() => expect(screen.getByRole('tab', { name: /Đón tiếp/ })).toHaveTextContent('0'));
  });

  it('chỉ có quản lý chi nhánh: không có nút "Tiếp nhận khách", phím N không mở gì, không gửi lệnh tiếp nhận hay hàng đợi của tôi', async () => {
    const checkIn = vi.spyOn(visitApi, 'checkIn');
    const myQueue = vi.spyOn(visitApi, 'getMyQueue');
    await signInAs('manager@store1.vn');
    const user = renderDesk();
    await screen.findByRole('tab', { name: /Hàng chờ/ });

    await user.keyboard('n');
    await new Promise((r) => setTimeout(r, 50));

    expect(screen.queryByRole('button', { name: /Tiếp nhận khách/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(checkIn).not.toHaveBeenCalled();
    expect(myQueue).not.toHaveBeenCalled();
  });
});
