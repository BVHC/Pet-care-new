import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../../shared/api/axios';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { salesApi } from '../../../shared/api/sales.api';
import { usePosStore } from '../../../shared/stores/pos.store';
import { api, asAccount, pinClock, signInAs } from '../../../test/cashier-harness';
import { CheckoutPanel } from './CheckoutPanel';
import { CloseShiftControl, ShiftBadge } from './ShiftControls';

// Mock: lễ tân #4 (reception@store1.vn, chi nhánh 1) chưa có ca OPEN. Chi nhánh 1 mở thứ Hai–thứ Bảy 08:00–12:00 và 13:30–19:00,
// chủ nhật 08:00–12:00, có cờ cấp cứu ngoài giờ. Đồng hồ ghim ở thứ Năm 22/10/2026 10:00 (+07:00).

beforeAll(() => installMockApi(apiClient));

beforeEach(() => {
  localStorage.clear();
  pinClock();
  usePosStore.getState().clear();
});

afterEach(() => vi.useRealTimers());

/** Khung tối thiểu của bàn lễ tân: chip ca + nút chốt ca ở đầu trang, phiếu thu bên dưới. */
async function renderDesk(email = 'reception@store1.vn') {
  await signInAs(email);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ShiftBadge />
        <CloseShiftControl />
        <CheckoutPanel hotkeysEnabled />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

describe('mở ca thu ngân (UC71; BR-TG-01, BR-TG-05, BR-CN-05)', () => {
  it('chưa có ca: ẩn chức năng thu tiền, nhắc mở ca; 10:00 trong giờ mở cửa chỉ có ca thường', async () => {
    await renderDesk();

    expect(await screen.findByRole('button', { name: 'Mở ca thường' })).toBeEnabled();
    expect(screen.getByText('Mở ca thu ngân để bắt đầu thu tiền.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Mở ca ngoài giờ' })).not.toBeInTheDocument();
    // BR-TG-01: không có phương thức thanh toán, không có nút thu, không có ô tìm khách để thu.
    expect(screen.queryByRole('radiogroup', { name: 'Phương thức thanh toán' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Thu / })).not.toBeInTheDocument();
    expect(screen.queryByPlaceholderText(/SĐT, họ tên hoặc email/)).not.toBeInTheDocument();
    expect(screen.getByText('Chưa mở ca thu ngân', { selector: '.ws-chip' })).toBeInTheDocument();
  });

  it('chưa có ca nhưng Order đã được đưa sang từ tab Chờ thu: vẫn thấy khách và Order (không mất), chưa có nút thu', async () => {
    usePosStore.getState().attachOrder(2, 1);
    await renderDesk();

    expect(await screen.findByRole('checkbox', { name: /DH-\d{4}-00002/ })).toBeChecked();
    expect(await screen.findByText('Nguyễn Thu Trang')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Mở ca thường' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Thu / })).not.toBeInTheDocument();
    expect(screen.queryByRole('radiogroup', { name: 'Phương thức thanh toán' })).not.toBeInTheDocument();
  });

  it('20:30 ngoài giờ mở cửa: chỉ có ca ngoài giờ (chi nhánh 1 có cờ cấp cứu)', async () => {
    pinClock('2026-10-22T20:30:00+07:00');
    await renderDesk();

    expect(await screen.findByRole('button', { name: 'Mở ca ngoài giờ' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Mở ca thường' })).not.toBeInTheDocument();
  });

  it('chủ nhật chiều chỉ có buổi sáng: 15:00 chỉ mở được ca ngoài giờ', async () => {
    pinClock('2026-10-25T15:00:00+07:00');
    await renderDesk();

    expect(await screen.findByRole('button', { name: 'Mở ca ngoài giờ' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Mở ca thường' })).not.toBeInTheDocument();
  });

  it('nghỉ trưa 12:30 tính là ngoài giờ mở cửa', async () => {
    pinClock('2026-10-22T12:30:00+07:00');
    await renderDesk();

    expect(await screen.findByRole('button', { name: 'Mở ca ngoài giờ' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Mở ca thường' })).not.toBeInTheDocument();
  });

  it('ngày nghỉ của chi nhánh: giữa giờ làm vẫn chỉ mở được ca ngoài giờ', async () => {
    await asAccount('manager@store1.vn', 'reception@store1.vn', () =>
      api.post('/api/branches/1/holidays', { holidayDate: '2026-10-22', reason: 'Bảo trì' }),
    );
    await renderDesk();

    expect(await screen.findByRole('button', { name: 'Mở ca ngoài giờ' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Mở ca thường' })).not.toBeInTheDocument();
  });

  it('chi nhánh không có cờ cấp cứu: ngoài giờ không có nút nào và nói rõ lý do', async () => {
    await asAccount('chuoi@petcare.vn', 'reception@store1.vn', () =>
      api.patch('/api/branches/1', { acceptsAfterHoursEmergency: false }),
    );
    pinClock('2026-10-22T20:30:00+07:00');
    await renderDesk();

    expect(await screen.findByText(/không nhận cấp cứu ngoài giờ nên chưa mở được ca/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Mở ca/ })).not.toBeInTheDocument();
  });

  it('mở ca thường: chip đổi sang "Ca mở lúc … · 0 phiếu thu" và hiện phần thu tiền', async () => {
    const user = await renderDesk();
    await user.click(await screen.findByRole('button', { name: 'Mở ca thường' }));

    expect(await screen.findByText(/^Ca mở lúc \d{2}:\d{2} · 0 phiếu thu$/)).toBeInTheDocument();
    expect(screen.queryByText('Ca ngoài giờ')).not.toBeInTheDocument();
    expect(await screen.findByPlaceholderText(/SĐT, họ tên hoặc email/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Mở ca thường' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Chốt ca' })).toBeInTheDocument();
  });

  it('mở ca ngoài giờ: chip có thẻ "Ca ngoài giờ"', async () => {
    pinClock('2026-10-22T20:30:00+07:00');
    const user = await renderDesk();
    await user.click(await screen.findByRole('button', { name: 'Mở ca ngoài giờ' }));

    expect(await screen.findByText(/^Ca mở lúc \d{2}:\d{2} · 0 phiếu thu$/)).toBeInTheDocument();
    expect(screen.getByText('Ca ngoài giờ')).toBeInTheDocument();
  });

  it('BE vẫn là chốt chặn: đồng hồ qua giờ đóng cửa sau khi nút hiện thì lỗi BR-TG-05 của BE hiện bằng lời thường (không mã rule)', async () => {
    const user = await renderDesk();
    const open = await screen.findByRole('button', { name: 'Mở ca thường' });
    pinClock('2026-10-22T20:30:00+07:00');
    await user.click(open);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Chỉ mở được ca thường trong giờ mở cửa của chi nhánh');
    expect(alert).not.toHaveTextContent('BR-TG-05');
    expect(screen.queryByText(/^Ca mở lúc/)).not.toBeInTheDocument();
  });

  it('đã có ca mở ở tab khác: 409 báo bằng câu của ca thu ngân (không dùng câu của Order)', async () => {
    const user = await renderDesk();
    const open = await screen.findByRole('button', { name: 'Mở ca thường' });
    await api.post('/api/cashier-shifts', { afterHours: false });
    await user.click(open);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('bạn đã có ca đang mở');
    expect(alert).not.toHaveTextContent('Order');
  });
});

/** Ca đang mở và đã thu 266.000 tiền mặt (Order #2) bằng API. */
async function shiftWithOneCashPayment() {
  await signInAs('reception@store1.vn');
  const shift = await salesApi.openShift({ afterHours: false });
  await salesApi.createPayment({ orderIds: [2], method: 'CASH', amount: 266_000 });
  return shift;
}

describe('chốt ca thu ngân (UC71; BR-TG-05)', () => {
  it('nhập tiền mặt thực đếm → tóm tắt: tiền mặt / chuyển khoản theo phiếu, thực đếm, chênh lệch tự tính; xong thì về trạng thái chưa mở ca', async () => {
    await shiftWithOneCashPayment();
    const user = await renderDesk();
    await user.click(await screen.findByRole('button', { name: 'Chốt ca' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Chốt xong không thu thêm tiền được nữa. Muốn thu tiếp phải mở ca mới.')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Tiền mặt thực đếm'), '260000');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận chốt ca' }));

    expect(await within(dialog).findByText('Đã chốt ca thu ngân')).toBeInTheDocument();
    const row = (label: string) => within(dialog).getByText(label).parentElement!;
    expect(row('Tiền mặt theo phiếu thu')).toHaveTextContent(/266\.000\s*₫/);
    expect(row('Chuyển khoản theo phiếu thu')).toHaveTextContent(/^Chuyển khoản theo phiếu thu0\s*₫$/);
    expect(row('Tiền mặt thực đếm')).toHaveTextContent(/260\.000\s*₫/);
    expect(row('Chênh lệch tiền mặt')).toHaveTextContent(/-6\.000\s*₫ \(thiếu\)/);
    expect(within(dialog).getByText('Chờ quản lý đối soát')).toBeInTheDocument();
    // BE ghi difference = null tới khi đối soát; FE tự tính từ countedCash − expectedCash.
    expect((await salesApi.getShift(101)).difference).toBeNull();

    await user.click(within(dialog).getByRole('button', { name: 'Xong' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByRole('button', { name: 'Mở ca thường' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Chốt ca' })).not.toBeInTheDocument();
    expect(screen.getByText('Chưa mở ca thu ngân', { selector: '.ws-chip' })).toBeInTheDocument();
  });

  it('số thực đếm là số nguyên từ 0: bỏ trống hoặc sai kiểu thì báo ngay, không gọi API; 0 là hợp lệ', async () => {
    await signInAs('reception@store1.vn');
    await salesApi.openShift({ afterHours: false });
    const close = vi.spyOn(salesApi, 'closeShift');
    const user = await renderDesk();
    await user.click(await screen.findByRole('button', { name: 'Chốt ca' }));
    const dialog = await screen.findByRole('dialog');
    const input = within(dialog).getByLabelText('Tiền mặt thực đếm');
    const submit = within(dialog).getByRole('button', { name: 'Xác nhận chốt ca' });

    await user.click(submit);
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Nhập số tiền mặt thực đếm');
    for (const bad of ['-5', '1.5', 'abc']) {
      await user.clear(input);
      await user.type(input, bad);
      await user.click(submit);
      expect(within(dialog).getByRole('alert')).toHaveTextContent('Nhập số tiền mặt thực đếm');
    }
    expect(close).not.toHaveBeenCalled();

    await user.clear(input);
    await user.type(input, '0');
    await user.click(submit);
    expect(await within(dialog).findByText('Đã chốt ca thu ngân')).toBeInTheDocument();
    expect(within(dialog).getByText('Chênh lệch tiền mặt').parentElement).toHaveTextContent(/0\s*₫ \(khớp\)/);
    expect(close).toHaveBeenCalledWith(101, { countedCash: 0 });
  });

  it('ca đã được chốt ở nơi khác: lỗi 409 báo bằng câu của ca thu ngân và hộp thoại vẫn còn để đọc', async () => {
    await signInAs('reception@store1.vn');
    const shift = await salesApi.openShift({ afterHours: false });
    const user = await renderDesk();
    await user.click(await screen.findByRole('button', { name: 'Chốt ca' }));
    const dialog = await screen.findByRole('dialog');
    await salesApi.closeShift(shift.shiftId, { countedCash: 0 });

    await user.type(within(dialog).getByLabelText('Tiền mặt thực đếm'), '5000');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận chốt ca' }));

    const alert = await within(dialog).findByRole('alert');
    expect(alert).toHaveTextContent('Không thể chuyển trạng thái ca thu ngân');
    expect(alert).not.toHaveTextContent('Order');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('chip sau khi thu: "Ca mở lúc … · 1 phiếu thu", chưa hiện tiền (ca đang mở chưa có expected*)', async () => {
    await shiftWithOneCashPayment();
    await renderDesk();

    expect(await screen.findByText(/^Ca mở lúc \d{2}:\d{2} · 1 phiếu thu$/)).toBeInTheDocument();
    expect(screen.queryByText(/266\.000/)).not.toBeInTheDocument();
  });
});

describe('quản lý chi nhánh trong bàn lễ tân (A05 không thu tiền, không mở / chốt ca)', () => {
  it('thấy lời nhắc kèm đường dẫn tới Ca thu ngân và không gửi lệnh gọi thu ngân nào (không 403)', async () => {
    const myShift = vi.spyOn(salesApi, 'getMyShift');
    const payable = vi.spyOn(salesApi, 'listPayableOrders');
    const preview = vi.spyOn(salesApi, 'previewPayment');
    usePosStore.getState().attachOrder(2, 1);
    await renderDesk('manager@store1.vn');

    expect(await screen.findByText('Thu tiền và mở / chốt ca là việc của lễ tân. Đối soát ca ở trang Ca thu ngân.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ca thu ngân' })).toHaveAttribute('href', '/admin/payments');
    expect(screen.queryByRole('button', { name: /Mở ca|Chốt ca|^Thu / })).not.toBeInTheDocument();
    expect(screen.queryByRole('radiogroup', { name: 'Phương thức thanh toán' })).not.toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 50));
    expect(myShift).not.toHaveBeenCalled();
    expect(payable).not.toHaveBeenCalled();
    expect(preview).not.toHaveBeenCalled();
    // Chip ca cũng không hiện cho quản lý.
    expect(within(document.body).queryByText(/ca thu ngân/i, { selector: '.ws-chip' })).not.toBeInTheDocument();
    await waitFor(() => expect(usePosStore.getState().orderIds).toEqual([2]));
  });
});
