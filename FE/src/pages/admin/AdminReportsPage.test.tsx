import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { formatCurrency } from '../../lib/utils';
import { AdminReportsPage } from './AdminReportsPage';

// Giao diện được kiểm với số liệu dựng sẵn: chặn `apiClient.get` ở các URL /api/reports/* (đi qua đúng hook thật, đúng `enabled`, đúng tham số),
// còn đăng nhập và danh sách chi nhánh chạy trên mock thật (3 chi nhánh: #1 Cầu Giấy, #2 Hà Đông, #3 Long Biên). Đồng hồ ghim ở thứ Ba 27/10/2026
// 10:00 giờ Việt Nam. Ca chạy trên mock báo cáo thật nằm ở AdminReportsPage.mock.test.tsx.
beforeAll(() => installMockApi(apiClient));
// Máy đang tải nặng (nhiều agent cùng chạy) thì bốn vòng qua sáu tab có thể vượt 5 giây mặc định.
vi.setConfig({ testTimeout: 20_000 });

const pin = (iso: string) => vi.setSystemTime(new Date(iso));

beforeEach(async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  pin('2026-10-27T10:00:00+07:00');
  await signInAs('chuoi@petcare.vn');
});

afterEach(() => vi.useRealTimers());

const signInAs = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

type Params = Record<string, unknown>;
type Handler = (params: Params) => unknown;

const FIXTURES: Record<string, Handler> = {
  revenue: () => ({
    from: '2026-10-01',
    to: '2026-10-27',
    totalAmount: 23_500_000,
    rows: [
      { branchId: 1, branchName: 'PetCare Cầu Giấy', source: 'VISIT', orderCount: 12, amount: 15_000_000 },
      { branchId: 1, branchName: 'PetCare Cầu Giấy', source: 'RETAIL', orderCount: 5, amount: 2_500_000 },
      { branchId: 2, branchName: 'PetCare Hà Đông', source: 'BOARDING', orderCount: 3, amount: 6_000_000 },
    ],
  }),
  'lost-revenue': () => ({
    from: '2026-10-01',
    to: '2026-10-27',
    count: 1,
    totalAmount: 450_000,
    rows: [
      { orderId: 101, orderCode: 'ORD-0101', branchId: 1, source: 'VISIT', amount: 450_000, reason: 'Khách bỏ về không thanh toán', cancelledAt: '2026-10-20T03:15:00Z' },
    ],
  }),
  'completed-services': () => ({
    from: '2026-10-01',
    to: '2026-10-27',
    rows: [
      { branchId: 1, serviceGroup: 'MEDICAL', serviceId: 11, serviceName: 'Khám tổng quát', count: 20 },
      { branchId: 1, serviceGroup: 'MEDICAL', serviceId: 12, serviceName: 'Tiêm phòng dại', count: 9 },
      { branchId: 1, serviceGroup: 'GROOMING', serviceId: 21, serviceName: 'Tắm sấy', count: 14 },
      { branchId: 2, serviceGroup: 'GROOMING', serviceId: 21, serviceName: 'Tắm sấy', count: 6 },
    ],
  }),
  'no-show': () => ({
    from: '2026-10-01',
    to: '2026-10-27',
    rows: [
      { branchId: 1, dueCount: 7, noShowCount: 2, lateCancelCount: 1, noShowRate: 0.2857, lateCancelRate: 0.1429 },
      { branchId: 2, dueCount: 0, noShowCount: 0, lateCancelCount: 0, noShowRate: 0, lateCancelRate: 0 },
    ],
  }),
  'revaccination-return': () => ({
    from: '2026-10-01',
    to: '2026-10-27',
    rows: [
      { branchId: 1, vaccineTypeId: 3, vaccineTypeName: 'Dại', dueCount: 10, returnedCount: 4, rate: 0.4 },
      { branchId: 2, vaccineTypeId: 4, vaccineTypeName: '5 bệnh cho chó', dueCount: 8, returnedCount: 8, rate: 1 },
    ],
  }),
  'stock-alerts': () => ({
    lowStock: [{ branchId: 1, productId: 7, productName: 'Pate mèo', availableQuantity: 3, minQuantity: 10 }],
    expiringLots: [
      { branchId: 1, productId: 8, productName: 'Vaccine Dại', stockLotId: 55, lotNumber: 'LOT-A1', expiryDate: '2026-11-10', quantity: 5, expired: false },
      { branchId: 2, productId: 7, productName: 'Pate mèo', stockLotId: 56, lotNumber: null, expiryDate: '2026-10-01', quantity: 2, expired: true },
    ],
  }),
  feedback: () => ({
    from: '2026-10-01',
    to: '2026-10-27',
    rows: [
      { branchId: 1, branchName: 'PetCare Cầu Giấy', count: 8, averageRating: 4.5 },
      { branchId: 2, branchName: 'PetCare Hà Đông', count: 2, averageRating: null },
      { branchId: null, branchName: null, count: 3, averageRating: 3.8 },
    ],
  }),
};

const EMPTY: Record<string, Handler> = {
  revenue: () => ({ from: '', to: '', rows: [], totalAmount: 0 }),
  'lost-revenue': () => ({ from: '', to: '', rows: [], count: 0, totalAmount: 0 }),
  'completed-services': () => ({ from: '', to: '', rows: [] }),
  'no-show': () => ({ from: '', to: '', rows: [] }),
  'revaccination-return': () => ({ from: '', to: '', rows: [] }),
  'stock-alerts': () => ({ lowStock: [], expiringLots: [] }),
  feedback: () => ({ from: '', to: '', rows: [] }),
};

/** Chặn `/api/reports/*`; mọi URL khác đi tiếp vào mock thật. Handler ném (hoặc trả Promise lỗi) = lỗi API. */
function stubReports(overrides: Record<string, Handler> = {}) {
  const realGet = apiClient.get.bind(apiClient);
  return vi.spyOn(apiClient, 'get').mockImplementation(((url: string, config?: { params?: Params }) => {
    if (!url.startsWith('/api/reports/')) return realGet(url, config);
    const name = url.slice('/api/reports/'.length);
    const handler = overrides[name] ?? FIXTURES[name];
    return new Promise((resolve) => resolve(handler(config?.params ?? {}))).then((data) => ({ data: { data } }));
  }) as typeof apiClient.get);
}

/** Các request báo cáo đã gửi, theo thứ tự. */
const sent = (get: ReturnType<typeof stubReports>) =>
  get.mock.calls
    .filter(([url]) => String(url).startsWith('/api/reports/'))
    .map(([url, config]) => ({ name: String(url).slice('/api/reports/'.length), params: ((config as { params?: Params } | undefined)?.params ?? {}) as Params }));
const names = (get: ReturnType<typeof stubReports>) => sent(get).map((c) => c.name);

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AdminReportsPage />
    </QueryClientProvider>,
  );
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 40));
const tab = (name: string) => screen.getByRole('tab', { name });
const region = (name: string) => screen.findByRole('region', { name });
/** Intl dùng khoảng trắng không ngắt trước "₫"; toHaveTextContent chỉ chuẩn hóa phía phần tử, nên chuẩn hóa nốt phía mong đợi. */
const money = (n: number) => formatCurrency(n).replace(/\u00a0/g, ' ');
const revenueTable = () => screen.findByRole('table', { name: 'Doanh thu theo chi nhánh và nguồn' });
const setDates = (from: string, to: string) => {
  fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: from } });
  fireEvent.change(screen.getByLabelText('Đến ngày'), { target: { value: to } });
};

describe('khung trang', () => {
  it('có <h1> "Báo cáo theo phạm vi" và đúng 6 tab của danh mục BR-BC-03', async () => {
    stubReports();
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Báo cáo theo phạm vi' })).toBeInTheDocument();
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual([
      'Doanh thu',
      'Lượt dịch vụ',
      'NO_SHOW và hủy muộn',
      'Tái chủng',
      'Tồn kho',
      'Feedback',
    ]);
    expect(tab('Doanh thu')).toHaveAttribute('aria-selected', 'true');
    await revenueTable();
  });

  it('KHÔNG có nút xuất file, báo cáo tùy biến hay lưu báo cáo ở bất kỳ tab nào (BR-BC-03)', async () => {
    stubReports();
    const user = userEvent.setup();
    renderPage();
    for (const name of ['Doanh thu', 'Lượt dịch vụ', 'NO_SHOW và hủy muộn', 'Tái chủng', 'Tồn kho', 'Feedback']) {
      await user.click(tab(name));
      await waitFor(() => expect(tab(name)).toHaveAttribute('aria-selected', 'true'));
      await settle();
      expect(screen.queryByText(/xuất|export|tùy biến|lưu báo cáo|in báo cáo|tải về|pdf|excel/i)).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /xuất|export|lưu|tùy biến|xem trước/i })).not.toBeInTheDocument();
    }
  });
});

describe('phạm vi theo vai trò (BR-BC-01)', () => {
  it('SUPER_MANAGER: có ô chọn chi nhánh, mặc định "Toàn chuỗi"; tải trang không phát sinh lỗi và không gửi branchId', async () => {
    const get = stubReports();
    renderPage();
    const select = screen.getByLabelText('Chi nhánh') as HTMLSelectElement;
    await waitFor(() =>
      expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(['Toàn chuỗi', 'PetCare Cầu Giấy', 'PetCare Hà Đông', 'PetCare Long Biên']),
    );
    expect(select.selectedOptions[0].textContent).toBe('Toàn chuỗi');
    await revenueTable();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(sent(get).map((c) => c.params.branchId)).toEqual([undefined, undefined]);
  });

  it.each([
    ['manager@store1.vn', 'PetCare Cầu Giấy'],
    ['manager2@store2.vn', 'PetCare Hà Đông'],
  ])('BRANCH_MANAGER %s: không có ô chọn chi nhánh, hiện tên chi nhánh mình bằng chữ, không 403', async (email, branch) => {
    const get = stubReports();
    await signInAs(email);
    renderPage();
    await revenueTable();
    const filters = screen.getByRole('region', { name: 'Bộ lọc báo cáo' });
    expect(within(filters).queryByLabelText('Chi nhánh')).not.toBeInTheDocument();
    expect(within(filters).queryByRole('combobox')).not.toBeInTheDocument();
    expect(await within(filters).findByText(branch)).toBeInTheDocument();
    expect(within(filters).queryByText('Toàn chuỗi')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(sent(get).length).toBeGreaterThan(0);
    expect(sent(get).every((c) => c.params.branchId === undefined)).toBe(true);
  });

  it('BRANCH_MANAGER: sáu tab đều không gửi branchId', async () => {
    const get = stubReports();
    await signInAs('manager@store1.vn');
    const user = userEvent.setup();
    renderPage();
    for (const name of ['Lượt dịch vụ', 'NO_SHOW và hủy muộn', 'Tái chủng', 'Tồn kho', 'Feedback']) {
      await user.click(tab(name));
      await waitFor(() => expect(tab(name)).toHaveAttribute('aria-selected', 'true'));
    }
    await settle();
    expect(new Set(names(get))).toEqual(new Set(Object.keys(FIXTURES)));
    expect(sent(get).every((c) => !('branchId' in c.params) || c.params.branchId === undefined)).toBe(true);
  });

  it('SUPER_MANAGER chọn chi nhánh thì request mang branchId; về "Toàn chuỗi" thì bỏ branchId (không gửi 0)', async () => {
    const get = stubReports();
    const user = userEvent.setup();
    renderPage();
    await revenueTable();
    await waitFor(() => expect(within(screen.getByLabelText('Chi nhánh')).getAllByRole('option')).toHaveLength(4));

    await user.selectOptions(screen.getByLabelText('Chi nhánh'), 'PetCare Hà Đông');
    await waitFor(() => expect(sent(get).some((c) => c.name === 'revenue' && c.params.branchId === 2)).toBe(true));
    expect(sent(get).filter((c) => c.params.branchId === 2).map((c) => c.name).sort()).toEqual(['lost-revenue', 'revenue']);

    await user.selectOptions(screen.getByLabelText('Chi nhánh'), 'Toàn chuỗi');
    await settle();
    const last = sent(get).slice(-2);
    expect(last.map((c) => c.name).sort()).toEqual(['lost-revenue', 'revenue']);
    expect(last.every((c) => c.params.branchId === undefined)).toBe(true);
    expect(sent(get).some((c) => c.params.branchId === 0)).toBe(false);
  });

  it('chi nhánh đã chọn cũng áp cho tab khác, kể cả Tồn kho', async () => {
    const get = stubReports();
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(within(screen.getByLabelText('Chi nhánh')).getAllByRole('option')).toHaveLength(4));
    await user.selectOptions(screen.getByLabelText('Chi nhánh'), 'PetCare Long Biên');
    await user.click(tab('Tồn kho'));
    await screen.findByRole('table', { name: 'Hàng dưới tồn tối thiểu' });
    expect(sent(get).at(-1)).toEqual({ name: 'stock-alerts', params: { branchId: 3, withinDays: 30 } });
    await user.click(tab('Feedback'));
    await screen.findByRole('table', { name: 'Feedback theo chi nhánh' });
    expect(sent(get).at(-1)?.params).toMatchObject({ branchId: 3, from: '2026-10-01', to: '2026-10-27' });
  });
});

describe('kỳ báo cáo', () => {
  it('mặc định "Tháng này" (mùng 1 đến hôm nay giờ Việt Nam) và gửi đúng kỳ đó', async () => {
    const get = stubReports();
    renderPage();
    await revenueTable();
    expect(screen.getByLabelText('Từ ngày')).toHaveValue('2026-10-01');
    expect(screen.getByLabelText('Đến ngày')).toHaveValue('2026-10-27');
    expect(screen.getByRole('button', { name: 'Tháng này' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Hôm nay' })).toHaveAttribute('aria-pressed', 'false');
    expect(names(get).sort()).toEqual(['lost-revenue', 'revenue']);
    for (const call of sent(get)) expect(call.params).toEqual({ branchId: undefined, from: '2026-10-01', to: '2026-10-27' });
  });

  it.each([
    ['Hôm nay', '2026-10-27', '2026-10-27'],
    ['7 ngày qua', '2026-10-21', '2026-10-27'],
    ['30 ngày qua', '2026-09-28', '2026-10-27'],
    ['Tháng này', '2026-10-01', '2026-10-27'],
    ['Tháng trước', '2026-09-01', '2026-09-30'],
  ])('nút "%s" đặt kỳ %s → %s và request mang đúng kỳ đó', async (label, from, to) => {
    const get = stubReports();
    const user = userEvent.setup();
    renderPage();
    await revenueTable();
    await user.click(screen.getByRole('button', { name: label }));
    await waitFor(() => expect(sent(get).at(-1)?.params).toMatchObject({ from, to }));
    expect(screen.getByLabelText('Từ ngày')).toHaveValue(from);
    expect(screen.getByLabelText('Đến ngày')).toHaveValue(to);
    expect(screen.getByRole('button', { name: label })).toHaveAttribute('aria-pressed', 'true');
  });

  it('00:01 ngày 1/11 giờ Việt Nam (vẫn là 31/10 theo UTC): mặc định và "Hôm nay" đều là 1/11', async () => {
    pin('2026-11-01T00:01:00+07:00');
    const get = stubReports();
    const user = userEvent.setup();
    renderPage();
    await revenueTable();
    expect(screen.getByLabelText('Từ ngày')).toHaveValue('2026-11-01');
    expect(screen.getByLabelText('Đến ngày')).toHaveValue('2026-11-01');

    await user.click(screen.getByRole('button', { name: '7 ngày qua' }));
    await waitFor(() => expect(sent(get).at(-1)?.params).toMatchObject({ from: '2026-10-26', to: '2026-11-01' }));
    await user.click(screen.getByRole('button', { name: 'Hôm nay' }));
    await waitFor(() => expect(sent(get).at(-1)?.params).toMatchObject({ from: '2026-11-01', to: '2026-11-01' }));
    await user.click(screen.getByRole('button', { name: 'Tháng trước' }));
    await waitFor(() => expect(sent(get).at(-1)?.params).toMatchObject({ from: '2026-10-01', to: '2026-10-31' }));
  });

  it('23:59 ngày 31/10 giờ Việt Nam: "Tháng này" 1–31/10, "Tháng trước" cả tháng 9', async () => {
    pin('2026-10-31T23:59:00+07:00');
    const get = stubReports();
    const user = userEvent.setup();
    renderPage();
    await revenueTable();
    expect(screen.getByLabelText('Đến ngày')).toHaveValue('2026-10-31');
    await user.click(screen.getByRole('button', { name: 'Tháng trước' }));
    await waitFor(() => expect(sent(get).at(-1)?.params).toMatchObject({ from: '2026-09-01', to: '2026-09-30' }));
  });

  it('tự nhập ngày thì không nút chọn nhanh nào được bấm sẵn', async () => {
    stubReports();
    renderPage();
    await revenueTable();
    setDates('2026-10-02', '2026-10-27');
    expect(screen.queryAllByRole('button', { pressed: true })).toHaveLength(0);
  });

  describe('kỳ sai: hiện câu báo và KHÔNG gửi request', () => {
    it.each([
      ['từ sau đến', '2026-10-28', '2026-10-27', 'Ngày bắt đầu không được sau ngày kết thúc.'],
      ['quá 12 tháng', '2025-10-01', '2026-10-27', 'Kỳ báo cáo tối đa 12 tháng.'],
      ['thiếu ngày bắt đầu', '', '2026-10-27', 'Chọn đủ ngày bắt đầu và ngày kết thúc.'],
      ['thiếu ngày kết thúc', '2026-10-01', '', 'Chọn đủ ngày bắt đầu và ngày kết thúc.'],
    ])('%s', async (_case, from, to, message) => {
      const get = stubReports();
      renderPage();
      await revenueTable();
      const before = sent(get).length;

      setDates(from, to);
      expect(await screen.findByRole('alert')).toHaveTextContent(message);
      await settle();
      expect(sent(get)).toHaveLength(before);
      // Số liệu của kỳ trước không được hiện lại như thể là của kỳ sai.
      expect(screen.queryByRole('table', { name: 'Doanh thu theo chi nhánh và nguồn' })).not.toBeInTheDocument();
      expect(screen.getAllByText('Chọn kỳ báo cáo hợp lệ để xem số liệu.')).toHaveLength(2); // doanh thu + thất thu
    });

    it('sửa lại về kỳ hợp lệ thì tải lại và hết báo lỗi', async () => {
      const get = stubReports();
      renderPage();
      await revenueTable();
      setDates('2026-10-28', '2026-10-27');
      await screen.findByRole('alert');
      const before = sent(get).length;

      setDates('2026-10-20', '2026-10-27');
      await revenueTable();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      expect(sent(get).length).toBeGreaterThan(before);
      expect(sent(get).at(-1)?.params).toMatchObject({ from: '2026-10-20', to: '2026-10-27' });
    });

    it('đúng 12 tháng (ngày kết thúc = bắt đầu + 12 tháng) vẫn được gửi', async () => {
      const get = stubReports();
      renderPage();
      await revenueTable();
      setDates('2025-10-27', '2026-10-27');
      await waitFor(() => expect(sent(get).at(-1)?.params).toMatchObject({ from: '2025-10-27', to: '2026-10-27' }));
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('kỳ sai ở tab khác cũng chặn tab đó; tab Tồn kho (không theo kỳ) vẫn chạy với withinDays 30', async () => {
      const get = stubReports();
      const user = userEvent.setup();
      renderPage();
      await revenueTable();
      setDates('2026-10-28', '2026-10-27');
      await user.click(tab('Feedback'));
      expect(await screen.findByText('Chọn kỳ báo cáo hợp lệ để xem số liệu.')).toBeInTheDocument();
      expect(names(get)).not.toContain('feedback');

      await user.click(tab('Tồn kho'));
      await screen.findByRole('table', { name: 'Hàng dưới tồn tối thiểu' });
      expect(sent(get).at(-1)).toEqual({ name: 'stock-alerts', params: { branchId: undefined, withinDays: 30 } });
      // Tồn kho không theo kỳ: không có ô ngày, không báo lỗi kỳ.
      expect(screen.queryByLabelText('Từ ngày')).not.toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });
  });
});

describe('mỗi lần chỉ tab đang mở được truy vấn', () => {
  it('tải trang chỉ gọi doanh thu + thất thu; mỗi tab khác chỉ gọi đúng endpoint của nó', async () => {
    const get = stubReports();
    const user = userEvent.setup();
    renderPage();
    await revenueTable();
    await settle();
    expect(names(get).sort()).toEqual(['lost-revenue', 'revenue']);

    const expected: [string, string, string][] = [
      ['Lượt dịch vụ', 'completed-services', 'Lượt dịch vụ theo dịch vụ'],
      ['NO_SHOW và hủy muộn', 'no-show', 'NO_SHOW và hủy muộn theo chi nhánh'],
      ['Tái chủng', 'revaccination-return', 'Tái chủng theo loại vaccine'],
      ['Tồn kho', 'stock-alerts', 'Hàng dưới tồn tối thiểu'],
      ['Feedback', 'feedback', 'Feedback theo chi nhánh'],
    ];
    for (const [label, endpoint, table] of expected) {
      const mark = sent(get).length;
      await user.click(tab(label));
      await screen.findByRole('table', { name: table });
      await settle();
      expect(names(get).slice(mark)).toEqual([endpoint]);
    }
  });
});

describe('nội dung các báo cáo', () => {
  it('Doanh thu + Thất thu: thẻ tổng, bảng chi nhánh × nguồn, bảng Order thất thu', async () => {
    stubReports();
    renderPage();
    const table = await revenueTable();
    const revenue = await region('Doanh thu');
    expect(within(revenue).getByText('Tổng doanh thu').nextElementSibling).toHaveTextContent(money(23_500_000));
    expect(within(revenue).getByText('Số Order đã thanh toán').nextElementSibling).toHaveTextContent('20');
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent('PetCare Cầu Giấy');
    expect(rows[0]).toHaveTextContent('Khám / dịch vụ');
    expect(rows[0]).toHaveTextContent(money(15_000_000));
    expect(rows[1]).toHaveTextContent('Bán lẻ');
    expect(rows[2]).toHaveTextContent('PetCare Hà Đông');
    expect(rows[2]).toHaveTextContent('Lưu trú');
    expect(rows[2]).toHaveTextContent(money(6_000_000));

    const lost = await screen.findByRole('table', { name: 'Order thất thu' });
    const row = within(lost).getAllByRole('row')[1];
    expect(row).toHaveTextContent('ORD-0101');
    expect(row).toHaveTextContent('PetCare Cầu Giấy'); // báo cáo thất thu chỉ có branchId, tên tra từ danh sách chi nhánh
    expect(row).toHaveTextContent('Khám / dịch vụ');
    expect(row).toHaveTextContent(money(450_000));
    expect(row).toHaveTextContent('Khách bỏ về không thanh toán');
    expect(row).toHaveTextContent('10:15 20/10/2026'); // 03:15Z = 10:15 giờ Việt Nam
    const lostRegion = await region('Thất thu');
    expect(within(lostRegion).getByText('Giá trị thất thu').nextElementSibling).toHaveTextContent(money(450_000));
    expect(within(lostRegion).getByText('Order thất thu', { selector: 'p' }).nextElementSibling).toHaveTextContent('1');
  });

  it('Lượt dịch vụ: tổng theo nhóm Khám/Tiêm và Thẩm mỹ, bảng theo dịch vụ', async () => {
    stubReports();
    const user = userEvent.setup();
    renderPage();
    await user.click(tab('Lượt dịch vụ'));
    const table = await screen.findByRole('table', { name: 'Lượt dịch vụ theo dịch vụ' });
    expect(screen.getByText('Nhóm Khám/Tiêm').nextElementSibling).toHaveTextContent('29');
    expect(screen.getByText('Nhóm Thẩm mỹ').nextElementSibling).toHaveTextContent('20');
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(4);
    expect(rows[0]).toHaveTextContent('PetCare Cầu Giấy');
    expect(rows[0]).toHaveTextContent('Khám/Tiêm');
    expect(rows[0]).toHaveTextContent('Khám tổng quát');
    expect(rows[3]).toHaveTextContent('PetCare Hà Đông');
    expect(rows[3]).toHaveTextContent('Thẩm mỹ');
    expect(rows[3]).toHaveTextContent('Tắm sấy');
  });

  it('NO_SHOW và hủy muộn: tử số / mẫu số kèm % (tỷ lệ 0–1 × 100, làm tròn 1 chữ số); mẫu số 0 hiện "—"', async () => {
    stubReports();
    const user = userEvent.setup();
    renderPage();
    await user.click(tab('NO_SHOW và hủy muộn'));
    const table = await screen.findByRole('table', { name: 'NO_SHOW và hủy muộn theo chi nhánh' });
    const [first, second] = within(table).getAllByRole('row').slice(1);
    const cells = within(first).getAllByRole('cell').map((c) => c.textContent);
    expect(cells).toEqual(['PetCare Cầu Giấy', '7', '2 / 7', '28,6%', '1 / 7', '14,3%']);
    const none = within(second).getAllByRole('cell').map((c) => c.textContent);
    expect(none).toEqual(['PetCare Hà Đông', '0', '0 / 0', '—', '0 / 0', '—']);
  });

  it('Tái chủng: đã quay lại / đến hạn theo loại vaccine kèm %', async () => {
    stubReports();
    const user = userEvent.setup();
    renderPage();
    await user.click(tab('Tái chủng'));
    const table = await screen.findByRole('table', { name: 'Tái chủng theo loại vaccine' });
    const [first, second] = within(table).getAllByRole('row').slice(1);
    expect(within(first).getAllByRole('cell').map((c) => c.textContent)).toEqual(['PetCare Cầu Giấy', 'Dại', '4 / 10', '40,0%']);
    expect(within(second).getAllByRole('cell').map((c) => c.textContent)).toEqual(['PetCare Hà Đông', '5 bệnh cho chó', '8 / 8', '100,0%']);
  });

  it('Feedback: số lượng + điểm trung bình; null → "—"; dòng không gắn chi nhánh có nhãn riêng', async () => {
    stubReports();
    const user = userEvent.setup();
    renderPage();
    await user.click(tab('Feedback'));
    const table = await screen.findByRole('table', { name: 'Feedback theo chi nhánh' });
    const rows = within(table).getAllByRole('row').slice(1).map((r) => within(r).getAllByRole('cell').map((c) => c.textContent));
    expect(rows).toEqual([
      ['PetCare Cầu Giấy', '8', '4,5 / 5'],
      ['PetCare Hà Đông', '2', '—'],
      ['Không gắn chi nhánh', '3', '3,8 / 5'],
    ]);
    expect(table).not.toHaveTextContent('null');
  });

  describe('Tồn kho', () => {
    it('bảng dưới ngưỡng và bảng lô hết hạn / sắp hết hạn', async () => {
      stubReports();
      const user = userEvent.setup();
      renderPage();
      await user.click(tab('Tồn kho'));
      const low = await screen.findByRole('table', { name: 'Hàng dưới tồn tối thiểu' });
      expect(within(low).getAllByRole('row')[1]).toHaveTextContent('PetCare Cầu Giấy');
      expect(within(low).getAllByRole('row')[1]).toHaveTextContent('Pate mèo');
      expect(within(low).getAllByRole('row')[1]).toHaveTextContent('3');
      expect(within(low).getAllByRole('row')[1]).toHaveTextContent('10');

      const lots = screen.getByRole('table', { name: 'Lô sắp hết hạn và đã hết hạn' });
      const [soon, expired] = within(lots).getAllByRole('row').slice(1);
      expect(soon).toHaveTextContent('Vaccine Dại');
      expect(soon).toHaveTextContent('LOT-A1');
      expect(soon).toHaveTextContent('10/11/2026');
      expect(soon).toHaveTextContent('Sắp hết hạn');
      expect(expired).toHaveTextContent('PetCare Hà Đông');
      expect(expired).toHaveTextContent('01/10/2026');
      expect(expired).toHaveTextContent('Đã hết hạn');
      expect(expired).toHaveTextContent('—'); // không số lô
    });

    it('withinDays mặc định 30; đổi thành 45 thì gửi 45', async () => {
      const get = stubReports();
      const user = userEvent.setup();
      renderPage();
      await user.click(tab('Tồn kho'));
      await screen.findByRole('table', { name: 'Hàng dưới tồn tối thiểu' });
      const input = screen.getByLabelText('Lô hết hạn trong vòng (ngày)');
      expect(input).toHaveValue(30);
      expect(sent(get).at(-1)?.params.withinDays).toBe(30);

      fireEvent.change(input, { target: { value: '45' } });
      await waitFor(() => expect(sent(get).at(-1)?.params.withinDays).toBe(45));
    });

    it.each(['0', '-5', '2.5', ''])('giá trị sai "%s": báo lỗi tại ô và không gửi request', async (value) => {
      const get = stubReports();
      const user = userEvent.setup();
      renderPage();
      await user.click(tab('Tồn kho'));
      await screen.findByRole('table', { name: 'Hàng dưới tồn tối thiểu' });
      const before = sent(get).length;

      fireEvent.change(screen.getByLabelText('Lô hết hạn trong vòng (ngày)'), { target: { value } });
      expect(await screen.findByText('Nhập số ngày là số nguyên dương.')).toBeInTheDocument();
      expect(screen.getByText('Nhập số ngày hợp lệ để xem cảnh báo tồn kho.')).toBeInTheDocument();
      expect(screen.queryByRole('table', { name: 'Hàng dưới tồn tối thiểu' })).not.toBeInTheDocument();
      await settle();
      expect(sent(get)).toHaveLength(before);
    });
  });
});

describe('trạng thái tải / lỗi / rỗng', () => {
  it('đang tải: khung chờ trong từng khối cho tới khi dữ liệu về', async () => {
    let answer!: (data: unknown) => void;
    stubReports({ revenue: () => new Promise((resolve) => (answer = resolve)) });
    renderPage();
    expect(await screen.findByRole('status', { name: 'Đang tải báo cáo Doanh thu' })).toBeInTheDocument();
    answer(FIXTURES.revenue({}));
    await revenueTable();
    expect(screen.queryByRole('status', { name: 'Đang tải báo cáo Doanh thu' })).not.toBeInTheDocument();
  });

  it.each([
    [400, 'BUSINESS_RULE_VIOLATION', 'Kỳ báo cáo tối đa 12 tháng (BR-BC-01)', 'Kỳ báo cáo tối đa 12 tháng'],
    [403, 'ACCESS_DENIED_SCOPE_MISMATCH', 'Không có quyền truy cập dữ liệu này', 'Không có quyền truy cập dữ liệu này'],
    [404, 'RESOURCE_NOT_FOUND', 'Chưa có mock cho GET /api/reports/revenue.', 'Chưa có mock cho GET /api/reports/revenue.'],
  ] as const)('lỗi %i của server: hiện nguyên câu của server kèm "Thử lại", bấm thì tải lại được', async (status, code, message, shown) => {
    let fail = true;
    stubReports({
      revenue: (p) => {
        if (fail) throw new ApiError(code, message, status);
        return FIXTURES.revenue(p);
      },
    });
    const user = userEvent.setup();
    renderPage();
    const revenue = await region('Doanh thu');
    const alert = await within(revenue).findByRole('alert');
    expect(alert).toHaveTextContent('Không tải được báo cáo Doanh thu');
    expect(alert).toHaveTextContent(shown);
    expect(alert).not.toHaveTextContent('BR-BC-01');

    // Khối thất thu độc lập: vẫn hiện dù khối doanh thu lỗi.
    expect(await screen.findByRole('table', { name: 'Order thất thu' })).toBeInTheDocument();

    fail = false;
    await user.click(within(alert).getByRole('button', { name: 'Thử lại' }));
    await revenueTable();
    expect(within(revenue).queryByRole('alert')).not.toBeInTheDocument();
  });

  it('lỗi ở tab khác cũng hiện câu của server', async () => {
    stubReports({
      'no-show': () => {
        throw new ApiError('BUSINESS_RULE_VIOLATION', 'Kỳ báo cáo tối đa 12 tháng (BR-BC-01)', 400);
      },
    });
    const user = userEvent.setup();
    renderPage();
    await user.click(tab('NO_SHOW và hủy muộn'));
    expect(await screen.findByRole('alert')).toHaveTextContent('Kỳ báo cáo tối đa 12 tháng');
  });

  it.each([
    ['Doanh thu', ['Doanh thu', 'Thất thu']],
    ['Lượt dịch vụ', ['Lượt dịch vụ hoàn tất']],
    ['NO_SHOW và hủy muộn', ['NO_SHOW và hủy muộn']],
    ['Tái chủng', ['Tái chủng']],
    ['Feedback', ['Feedback']],
  ])('rỗng ở tab "%s": "Không có dữ liệu trong kỳ", không bảng, không lỗi', async (label, sections) => {
    stubReports(EMPTY);
    const user = userEvent.setup();
    renderPage();
    await user.click(tab(label));
    for (const name of sections) {
      expect(await within(await region(name)).findByText('Không có dữ liệu trong kỳ')).toBeInTheDocument();
    }
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('rỗng ở tab Tồn kho', async () => {
    stubReports(EMPTY);
    const user = userEvent.setup();
    renderPage();
    await user.click(tab('Tồn kho'));
    expect(await screen.findByText('Không có hàng dưới ngưỡng và không có lô sắp hết hạn')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('tab Tồn kho chỉ rỗng một nửa: bảng còn lại vẫn hiện, nửa rỗng có câu riêng', async () => {
    const { expiringLots } = FIXTURES['stock-alerts']({}) as { expiringLots: unknown[] };
    stubReports({ 'stock-alerts': () => ({ lowStock: [], expiringLots }) });
    const user = userEvent.setup();
    renderPage();
    await user.click(tab('Tồn kho'));
    expect(await screen.findByRole('table', { name: 'Lô sắp hết hạn và đã hết hạn' })).toBeInTheDocument();
    expect(screen.getByText('Không có hàng nào dưới tồn tối thiểu.')).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: 'Hàng dưới tồn tối thiểu' })).not.toBeInTheDocument();
  });
});
