import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { salesApi } from '../../shared/api/sales.api';
import { RequireRole } from '../../shared/components/auth/RequireRole';
import { rolesForPath } from '../../shared/constants/staff-menu';
import type { CashierShift } from '../../shared/types/sales';
import { pinClock, signInAs } from '../../test/cashier-harness';
import { AdminPaymentsPage } from './AdminPaymentsPage';

// Mock cashier.mock.ts, đồng hồ ghim thứ Năm 22/10/2026 10:00. Chi nhánh 1 (manager@store1.vn), mới nhất trước:
// ca #3 (21/10, Lan Chi) CLOSED chờ đối soát: tiền mặt 576.000 / chuyển khoản 300.000, thực đếm 570.000, 3 phiếu
// · ca #2 (20/10, "Lễ tân mới") CLOSED tự chốt, chưa có số thực đếm: tiền mặt 200.000 / chuyển khoản 80.000, 2 phiếu
// · ca #1 (19/10, Lan Chi) RECONCILED: tiền mặt 108.000 / chuyển khoản 350.000, thực đếm 100.000, lệch -8.000.
// Chi nhánh 2 (manager2@store2.vn): ca #4 CLOSED, tiền mặt 900.000, thực đếm 900.000.

beforeAll(() => installMockApi(apiClient));

beforeEach(() => {
  localStorage.clear();
  pinClock();
});

afterEach(() => vi.useRealTimers());

function renderPage(path = '/admin/payments') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route element={<RequireRole roles={rolesForPath('/admin/payments')} />}>
            <Route path="/admin/payments" element={<AdminPaymentsPage />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const viewButtons = () => screen.findAllByRole('button', { name: /^Xem ca #/ });
const shiftIds = async () => (await viewButtons()).map((b) => b.getAttribute('aria-label')!.replace('Xem ca ', ''));
const rowOf = async (id: number) => within((await screen.findByRole('button', { name: `Xem ca #${id}` })).closest('tr')!);

describe('AdminPaymentsPage — Ca thu ngân (UC72)', () => {
  it('quản lý liệt kê ca của chi nhánh mới nhất trước, đủ cột tiền, loại ca, trạng thái và thẻ Tự chốt', async () => {
    await signInAs('manager@store1.vn');
    renderPage();

    expect(await shiftIds()).toEqual(['#3', '#2', '#1']);
    const closed = await rowOf(3);
    expect(closed.getByText('Lan Chi')).toBeInTheDocument();
    expect(closed.getByText('Ca thường')).toBeInTheDocument();
    expect(closed.getByText('Đã chốt')).toBeInTheDocument();
    expect(closed.getByText(/576\.000\s*₫/)).toBeInTheDocument();
    expect(closed.getByText(/300\.000\s*₫/)).toBeInTheDocument();
    expect(closed.getByText(/570\.000\s*₫/)).toBeInTheDocument();
    // BE chỉ ghi difference khi đối soát: ca CLOSED hiện chênh lệch tự tính, ghi rõ là tạm tính.
    expect(closed.getByText(/-6\.000\s*₫ \(thiếu\)/)).toBeInTheDocument();
    expect(closed.getByText('tạm tính')).toBeInTheDocument();
    expect(closed.queryByText('Tự chốt')).not.toBeInTheDocument();

    const auto = await rowOf(2);
    expect(auto.getByText('Tự chốt')).toBeInTheDocument();
    expect(auto.getByText(/200\.000\s*₫/)).toBeInTheDocument();
    expect(auto.getByText('—', { selector: 'td[data-col="counted"]' })).toBeInTheDocument();

    const done = await rowOf(1);
    expect(done.getByText('Đã đối soát')).toBeInTheDocument();
    expect(done.getByText(/-8\.000\s*₫ \(thiếu\)/)).toBeInTheDocument();
    expect(done.queryByText('tạm tính')).not.toBeInTheDocument();
  });

  it('lọc theo trạng thái và ngày nghiệp vụ; không có ca nào thì nói rõ, không hiện phân trang', async () => {
    await signInAs('manager@store1.vn');
    const user = renderPage();
    await viewButtons();

    await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), 'RECONCILED');
    await waitFor(async () => expect(await shiftIds()).toEqual(['#1']));

    await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), 'OPEN');
    expect(await screen.findByText('Không có ca nào khớp bộ lọc.')).toBeInTheDocument();
    expect(screen.queryByText(/^Trang /)).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), '');
    await waitFor(async () => expect(await shiftIds()).toEqual(['#3', '#2', '#1']));
    const day = (await salesApi.getShift(3)).businessDate;
    await user.type(screen.getByLabelText('Ngày nghiệp vụ'), day);
    await waitFor(async () => expect(await shiftIds()).toEqual(['#3']));
  });

  it('phân trang theo trang 0-based, 20 ca một trang', async () => {
    await signInAs('manager@store1.vn');
    const shift = (shiftId: number): CashierShift => ({
      shiftId, branchId: 1, cashierId: 4, cashierName: 'Lan Chi', isAfterHours: false, businessDate: '2026-10-21', status: 'RECONCILED',
      openedAt: '2026-10-21T01:00:00Z', closedAt: '2026-10-21T11:00:00Z', autoClosed: false, expectedCash: 1, expectedTransfer: 0, countedCash: 1,
      difference: 0, reconcileNote: 'ok', paymentCount: 1,
    });
    const list = vi
      .spyOn(salesApi, 'listShifts')
      .mockImplementation(async (q) => ({ content: [shift(q.page === 1 ? 21 : 1)], page: q.page ?? 0, size: 20, totalElements: 41, totalPages: 3 }));
    const user = renderPage();

    expect(await screen.findByText('Trang 1/3 · 41 ca')).toBeInTheDocument();
    expect(list).toHaveBeenCalledWith(expect.objectContaining({ page: 0, size: 20 }));
    expect(screen.getByRole('button', { name: 'Trước' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Sau' }));
    expect(await screen.findByText('Trang 2/3 · 41 ca')).toBeInTheDocument();
    expect(list).toHaveBeenCalledWith(expect.objectContaining({ page: 1, size: 20 }));
    expect(await screen.findByRole('button', { name: 'Xem ca #21' })).toBeInTheDocument();
  });

  it('xem chi tiết một ca: ghi chú đối soát của ca đã đối soát, không có nút Đối soát', async () => {
    await signInAs('manager@store1.vn');
    const user = renderPage();
    await user.click(await screen.findByRole('button', { name: 'Xem ca #1' }));

    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText(/Thiếu 8\.000đ, lễ tân xác nhận trả nhầm tiền thừa/)).toBeInTheDocument();
    expect(within(dialog).getByText('Đã đối soát')).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: /Đối soát/ })).not.toBeInTheDocument();
  });

  it('nút Đối soát chỉ có ở ca đã chốt: ca đang mở và ca đã đối soát không có', async () => {
    await signInAs('reception@store1.vn');
    await salesApi.openShift({ afterHours: false });
    await signInAs('manager@store1.vn');
    renderPage();

    expect(await shiftIds()).toEqual(['#101', '#3', '#2', '#1']);
    expect(screen.getByRole('button', { name: 'Đối soát ca #3' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Đối soát ca #2' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Đối soát ca #101' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Đối soát ca #1' })).not.toBeInTheDocument();
    expect((await rowOf(101)).getByText('Đang mở')).toBeInTheDocument();
  });

  it('quản lý chi nhánh 2 chỉ thấy ca của chi nhánh 2', async () => {
    await signInAs('manager2@store2.vn');
    renderPage();

    expect(await shiftIds()).toEqual(['#4']);
    expect((await rowOf(4)).getByText('Vũ Hải Yến')).toBeInTheDocument();
  });

  it('lễ tân không mở được trang (403) và không gửi lệnh gọi danh sách ca', async () => {
    const list = vi.spyOn(salesApi, 'listShifts');
    await signInAs('reception@store1.vn');
    renderPage();

    expect(await screen.findByText('Bạn không có quyền vào trang này')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Xem ca #/ })).not.toBeInTheDocument();
    expect(list).not.toHaveBeenCalled();
  });
});

describe('đối soát ca (UC72; BR-TG-04, BR-TG-05)', () => {
  it('ca thường: số thực đếm điền sẵn, chênh lệch hiện ngay khi sửa, ghi chú bắt buộc; xong ca thành Đã đối soát với chênh lệch của BE', async () => {
    await signInAs('manager@store1.vn');
    const reconcile = vi.spyOn(salesApi, 'reconcileShift');
    const user = renderPage();
    await user.click(await screen.findByRole('button', { name: 'Đối soát ca #3' }));

    const dialog = await screen.findByRole('dialog');
    const counted = within(dialog).getByLabelText('Tiền mặt thực đếm');
    expect(counted).toHaveValue('570000');
    expect(within(dialog).getByText(/Tiền mặt theo phiếu thu: 576\.000\s*₫/)).toBeInTheDocument();
    expect(within(dialog).getByText(/Chênh lệch: -6\.000\s*₫ \(thiếu\)/)).toBeInTheDocument();
    await user.clear(counted);
    await user.type(counted, '580000');
    expect(within(dialog).getByText(/Chênh lệch: \+4\.000\s*₫ \(thừa\)/)).toBeInTheDocument();
    await user.clear(counted);
    await user.type(counted, '570000');

    await user.click(within(dialog).getByRole('button', { name: 'Đối soát' }));
    expect(await within(dialog).findByText('Nhập ghi chú đối soát.')).toBeInTheDocument();
    expect(reconcile).not.toHaveBeenCalled();

    await user.type(within(dialog).getByLabelText('Ghi chú đối soát'), '  Thiếu 6.000đ, lễ tân trả nhầm tiền thừa  ');
    await user.click(within(dialog).getByRole('button', { name: 'Đối soát' }));

    // Số thực đếm không đổi so với lúc lễ tân chốt thì không gửi (BE giữ nguyên).
    await waitFor(() => expect(reconcile).toHaveBeenCalledWith(3, { note: 'Thiếu 6.000đ, lễ tân trả nhầm tiền thừa' }));
    await waitFor(() => expect(within(screen.getByRole('dialog')).getByText('Đã đối soát')).toBeInTheDocument());
    expect(within(screen.getByRole('dialog')).getByText(/-6\.000\s*₫ \(thiếu\)/)).toBeInTheDocument();
    expect((await salesApi.getShift(3)).difference).toBe(-6_000);
    expect(screen.queryByRole('button', { name: 'Đối soát ca #3' })).not.toBeInTheDocument();
  });

  it('sửa số thực đếm khi đối soát thì gửi số mới (ghi đè)', async () => {
    await signInAs('manager@store1.vn');
    const reconcile = vi.spyOn(salesApi, 'reconcileShift');
    const user = renderPage();
    await user.click(await screen.findByRole('button', { name: 'Đối soát ca #3' }));

    const dialog = await screen.findByRole('dialog');
    const counted = within(dialog).getByLabelText('Tiền mặt thực đếm');
    await user.clear(counted);
    await user.type(counted, '576000');
    await user.type(within(dialog).getByLabelText('Ghi chú đối soát'), 'Đếm lại, khớp');
    await user.click(within(dialog).getByRole('button', { name: 'Đối soát' }));

    await waitFor(() => expect(reconcile).toHaveBeenCalledWith(3, { countedCash: 576_000, note: 'Đếm lại, khớp' }));
    await waitFor(() => expect(within(screen.getByRole('dialog')).getByText('Đã đối soát')).toBeInTheDocument());
    expect(within(screen.getByRole('dialog')).getByText(/0\s*₫ \(khớp\)/)).toBeInTheDocument();
  });

  it('ca tự chốt chưa có số thực đếm: bắt buộc nhập (BR-TG-05), không gửi lên BE khi còn thiếu', async () => {
    await signInAs('manager@store1.vn');
    const reconcile = vi.spyOn(salesApi, 'reconcileShift');
    const user = renderPage();
    await user.click(await screen.findByRole('button', { name: 'Đối soát ca #2' }));

    const dialog = await screen.findByRole('dialog');
    const counted = within(dialog).getByLabelText('Tiền mặt thực đếm');
    expect(counted).toHaveValue('');
    expect(within(dialog).getByText(/Ca này tự chốt nên chưa có số tiền thực đếm/)).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Ghi chú đối soát'), 'Lễ tân quên chốt, đếm lại két');
    await user.click(within(dialog).getByRole('button', { name: 'Đối soát' }));

    expect(await within(dialog).findByText('Ca tự chốt chưa có số thực đếm: nhập số tiền mặt thực đếm để đối soát.')).toBeInTheDocument();
    expect(reconcile).not.toHaveBeenCalled();

    await user.type(counted, '195000');
    expect(within(dialog).getByText(/Chênh lệch: -5\.000\s*₫ \(thiếu\)/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Đối soát' }));

    await waitFor(() => expect(reconcile).toHaveBeenCalledWith(2, { countedCash: 195_000, note: 'Lễ tân quên chốt, đếm lại két' }));
    expect((await salesApi.getShift(2))).toMatchObject({ status: 'RECONCILED', countedCash: 195_000, difference: -5_000 });
  });

  it('số thực đếm sai kiểu và ghi chú quá 1000 ký tự bị chặn trước khi gửi', async () => {
    await signInAs('manager@store1.vn');
    const reconcile = vi.spyOn(salesApi, 'reconcileShift');
    const user = renderPage();
    await user.click(await screen.findByRole('button', { name: 'Đối soát ca #3' }));
    const dialog = await screen.findByRole('dialog');
    const counted = within(dialog).getByLabelText('Tiền mặt thực đếm');

    await user.clear(counted);
    await user.type(counted, '12.5');
    await user.click(within(dialog).getByRole('button', { name: 'Đối soát' }));
    expect(await within(dialog).findByText('Số tiền thực đếm là số nguyên từ 0.')).toBeInTheDocument();

    await user.clear(counted);
    await user.type(counted, '570000');
    await user.click(within(dialog).getByLabelText('Ghi chú đối soát'));
    await user.paste('a'.repeat(1001));
    expect(within(dialog).getByText('1001/1000')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Đối soát' }));
    expect(await within(dialog).findByText('Ghi chú tối đa 1000 ký tự.')).toBeInTheDocument();
    expect(reconcile).not.toHaveBeenCalled();
  });

  it('ca vừa được đối soát ở nơi khác: 409 báo bằng câu của ca thu ngân, hộp thoại còn để đọc', async () => {
    await signInAs('manager@store1.vn');
    const user = renderPage();
    await user.click(await screen.findByRole('button', { name: 'Đối soát ca #3' }));
    const dialog = await screen.findByRole('dialog');
    await salesApi.reconcileShift(3, { note: 'Đã đối soát ở máy khác' });

    await user.type(within(dialog).getByLabelText('Ghi chú đối soát'), 'Đối soát lần hai');
    await user.click(within(dialog).getByRole('button', { name: 'Đối soát' }));

    const alert = await within(dialog).findByRole('alert');
    expect(alert).toHaveTextContent('chỉ đối soát được ca đã chốt');
    expect(alert).not.toHaveTextContent('Order');
    expect(alert).not.toHaveTextContent('BR-');
  });
});
