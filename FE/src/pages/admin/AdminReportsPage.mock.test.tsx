import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminReportsPage } from './AdminReportsPage';

// Trang báo cáo chạy trên mock báo cáo thật (shared/api/mock/report.mock.ts): vai trò, phạm vi chi nhánh, kỳ và lỗi 403 đi qua đúng đường mock → axios → hook.
// Số liệu mẫu của mock tính theo "số ngày trước hôm nay" nên không dò số cứng, chỉ dò quan hệ (phạm vi, tổng, tỷ lệ khớp tử/mẫu). Đồng hồ ghim 27/10/2026 10:00 giờ VN.
beforeAll(() => installMockApi(apiClient));
// Máy đang tải nặng (nhiều agent cùng chạy) thì bốn vòng qua sáu tab có thể vượt 5 giây mặc định.
vi.setConfig({ testTimeout: 20_000 });

beforeEach(async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-10-27T10:00:00+07:00'));
  await signInAs('chuoi@petcare.vn');
});

afterEach(() => vi.useRealTimers());

const signInAs = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AdminReportsPage />
    </QueryClientProvider>,
  );
}

const TABS = ['Doanh thu', 'Lượt dịch vụ', 'NO_SHOW và hủy muộn', 'Tái chủng', 'Tồn kho', 'Feedback'];
const tab = (name: string) => screen.getByRole('tab', { name });
const loaded = () => waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument());
const rowsOf = async (name: string) => within(await screen.findByRole('table', { name })).getAllByRole('row').slice(1);
const cells = (row: HTMLElement) => within(row).getAllByRole('cell').map((c) => c.textContent ?? '');
const digits = (text: string) => Number(text.replace(/\D/g, ''));
const REVENUE = 'Doanh thu theo chi nhánh và nguồn';

describe.each([
  ['chuoi@petcare.vn', 'SUPER_MANAGER'],
  ['manager@store1.vn', 'BRANCH_MANAGER chi nhánh 1'],
  ['manager2@store2.vn', 'BRANCH_MANAGER chi nhánh 2'],
])('%s (%s)', (email) => {
  it('mở trang và qua đủ sáu tab: không có lỗi nào (không 403, không 404 thiếu mock, không 400)', async () => {
    await signInAs(email);
    const user = userEvent.setup();
    renderPage();
    for (const name of TABS) {
      await user.click(tab(name));
      await waitFor(() => expect(tab(name)).toHaveAttribute('aria-selected', 'true'));
      await loaded();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    }
  });
});

describe('phạm vi dữ liệu theo vai trò (BR-BC-01)', () => {
  it('SUPER_MANAGER thấy nhiều chi nhánh; chọn một chi nhánh thì chỉ còn chi nhánh đó, về "Toàn chuỗi" thì lại đủ', async () => {
    const user = userEvent.setup();
    renderPage();
    const branchesIn = async () => new Set((await rowsOf(REVENUE)).map((r) => cells(r)[0]));
    expect([...(await branchesIn())]).toEqual(expect.arrayContaining(['PetCare Cầu Giấy', 'PetCare Hà Đông']));

    await waitFor(() => expect(within(screen.getByLabelText('Chi nhánh')).getAllByRole('option')).toHaveLength(4));
    await user.selectOptions(screen.getByLabelText('Chi nhánh'), 'PetCare Hà Đông');
    await waitFor(async () => expect(await branchesIn()).toEqual(new Set(['PetCare Hà Đông'])));

    await user.selectOptions(screen.getByLabelText('Chi nhánh'), 'Toàn chuỗi');
    await waitFor(async () => expect([...(await branchesIn())]).toEqual(expect.arrayContaining(['PetCare Cầu Giấy', 'PetCare Hà Đông'])));
  });

  it.each([
    ['manager@store1.vn', 'PetCare Cầu Giấy'],
    ['manager2@store2.vn', 'PetCare Hà Đông'],
  ])('BRANCH_MANAGER %s chỉ thấy chi nhánh mình ở mọi báo cáo theo chi nhánh', async (email, branch) => {
    await signInAs(email);
    const user = userEvent.setup();
    renderPage();
    expect(screen.queryByLabelText('Chi nhánh')).not.toBeInTheDocument();
    expect(new Set((await rowsOf(REVENUE)).map((r) => cells(r)[0]))).toEqual(new Set([branch]));

    for (const [label, table] of [
      ['Lượt dịch vụ', 'Lượt dịch vụ theo dịch vụ'],
      ['NO_SHOW và hủy muộn', 'NO_SHOW và hủy muộn theo chi nhánh'],
      ['Tái chủng', 'Tái chủng theo loại vaccine'],
    ]) {
      await user.click(tab(label));
      const rows = await rowsOf(table);
      expect(rows.length).toBeGreaterThan(0);
      expect(new Set(rows.map((r) => cells(r)[0]))).toEqual(new Set([branch]));
    }
  });
});

describe('số liệu trên mock thật', () => {
  it('Doanh thu: "Tổng doanh thu" bằng tổng các dòng; Order thất thu khớp thẻ tổng', async () => {
    renderPage();
    const rows = await rowsOf(REVENUE);
    const total = rows.reduce((sum, r) => sum + digits(cells(r)[3]), 0);
    expect(total).toBeGreaterThan(0);
    const revenue = screen.getByRole('region', { name: 'Doanh thu' });
    expect(digits(within(revenue).getByText('Tổng doanh thu').nextElementSibling!.textContent!)).toBe(total);
    expect(digits(within(revenue).getByText('Số Order đã thanh toán').nextElementSibling!.textContent!)).toBe(rows.reduce((sum, r) => sum + digits(cells(r)[2]), 0));
    await loaded();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('Tháng trước và 30 ngày qua có dữ liệu doanh thu, nút chọn nhanh đổi đúng kỳ', async () => {
    const user = userEvent.setup();
    renderPage();
    await rowsOf(REVENUE);
    await user.click(screen.getByRole('button', { name: 'Tháng trước' }));
    await waitFor(() => expect(screen.getByLabelText('Từ ngày')).toHaveValue('2026-09-01'));
    expect(screen.getByLabelText('Đến ngày')).toHaveValue('2026-09-30');
    expect((await rowsOf(REVENUE)).length).toBeGreaterThan(0);
    await user.click(screen.getByRole('button', { name: '30 ngày qua' }));
    await waitFor(() => expect(screen.getByLabelText('Từ ngày')).toHaveValue('2026-09-28'));
    expect((await rowsOf(REVENUE)).length).toBeGreaterThan(0);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('NO_SHOW và hủy muộn: tỷ lệ hiện khớp tử số / mẫu số của dòng', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(tab('NO_SHOW và hủy muộn'));
    const rows = await rowsOf('NO_SHOW và hủy muộn theo chi nhánh');
    expect(rows.length).toBeGreaterThan(0);
    expect(rows.some((r) => digits(cells(r)[1]) > 0)).toBe(true); // ít nhất một dòng có mẫu số để kiểm tỷ lệ
    for (const row of rows) {
      const [, due, noShow, noShowPct, late, latePct] = cells(row);
      const percent = (text: string) => Number(text.replace('%', '').replace(',', '.'));
      if (digits(due) === 0) {
        // Chi nhánh không có lịch đến hạn trong kỳ: chưa có tỷ lệ, không phải NaN hay 0,0%.
        expect([noShowPct, latePct]).toEqual(['—', '—']);
        continue;
      }
      expect(Math.abs(percent(noShowPct) - (digits(noShow.split('/')[0]) / digits(due)) * 100)).toBeLessThanOrEqual(0.06);
      expect(Math.abs(percent(latePct) - (digits(late.split('/')[0]) / digits(due)) * 100)).toBeLessThanOrEqual(0.06);
    }
  });

  it('kỳ không có dữ liệu: doanh thu / thất thu rỗng; NO_SHOW mẫu số 0 hiện "—" chứ không phải 0,0%', async () => {
    const user = userEvent.setup();
    renderPage();
    await rowsOf(REVENUE);
    fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2020-01-01' } });
    fireEvent.change(screen.getByLabelText('Đến ngày'), { target: { value: '2020-01-31' } });
    const revenue = screen.getByRole('region', { name: 'Doanh thu' });
    expect(await within(revenue).findByText('Không có dữ liệu trong kỳ')).toBeInTheDocument();
    expect(await within(screen.getByRole('region', { name: 'Thất thu' })).findByText('Không có dữ liệu trong kỳ')).toBeInTheDocument();

    await user.click(tab('NO_SHOW và hủy muộn'));
    const rows = await rowsOf('NO_SHOW và hủy muộn theo chi nhánh');
    expect(cells(rows[0]).slice(1)).toEqual(['0', '0 / 0', '—', '0 / 0', '—']);
  });

  it('Tồn kho: tăng số ngày không làm giảm số lô được liệt kê; 1 ngày ≤ 365 ngày', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(tab('Tồn kho'));
    await loaded();
    const input = screen.getByLabelText('Lô hết hạn trong vòng (ngày)');
    const lotCount = async () => {
      await loaded();
      const table = screen.queryByRole('table', { name: 'Lô sắp hết hạn và đã hết hạn' });
      return table ? within(table).getAllByRole('row').length - 1 : 0;
    };
    fireEvent.change(input, { target: { value: '1' } });
    await loaded();
    const few = await lotCount();
    fireEvent.change(input, { target: { value: '365' } });
    await waitFor(async () => expect(await lotCount()).toBeGreaterThanOrEqual(few));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('lỗi của server qua mock thật', () => {
  it('vai trò không được xem báo cáo (lễ tân) vào trang: 403 hiện nguyên câu của server kèm "Thử lại", không crash', async () => {
    await signInAs('reception@store1.vn');
    renderPage();
    const alerts = await screen.findAllByRole('alert', {}, { timeout: 5000 });
    expect(alerts[0]).toHaveTextContent('Không có quyền thực hiện thao tác này');
    expect(within(alerts[0]).getByRole('button', { name: 'Thử lại' })).toBeInTheDocument();
  });
});
