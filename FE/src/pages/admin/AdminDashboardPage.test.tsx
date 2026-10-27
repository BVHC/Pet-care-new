import type { AxiosAdapter, AxiosError, InternalAxiosRequestConfig } from 'axios';
import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { formatCurrency } from '../../lib/utils';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { reportApi } from '../../shared/api/report.api';
import { salesApi } from '../../shared/api/sales.api';
import { visitApi } from '../../shared/api/visit.api';
import { useSession } from '../../shared/stores/session.store';
import type { OrderSummary } from '../../shared/types/sales';
import type { RevenueReport } from '../../shared/types/report';
import type { Visit, VisitQueueQuery } from '../../shared/types/visit';
import { makeVisit, pageOf, renderDesk, startDesk } from '../../test/visit-harness';
import { AdminDashboardPage } from './AdminDashboardPage';

// `useReports` giữ tham chiếu `reportApi.revenue` lúc nạp module nên không spyOn được thuộc tính: bọc hàm bằng vi.fn chạy tiếp hàm thật (mock hợp đồng) cho tới khi test thay.
vi.mock('../../shared/api/report.api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api/report.api')>();
  return { ...actual, reportApi: { ...actual.reportApi, revenue: vi.fn(actual.reportApi.revenue) } };
});

// T49: bảng điều khiển chạy trên API hợp đồng (visit-v1 #2, sales-v1 #2, report-v1 #1), không còn clinic-db. Đồng hồ ghim thứ Năm 22/10/2026 10:40 giờ Việt Nam.

beforeAll(() => installMockApi(apiClient));
afterEach(() => {
  vi.restoreAllMocks();
  vi.mocked(reportApi.revenue).mockReset(); // vitest 3: về hàm gốc đã truyền vào vi.fn
  vi.useRealTimers();
  useSession.getState().clear();
});

const ACCOUNTS = {
  SUPER_MANAGER: 'chuoi@petcare.vn',
  BRANCH_MANAGER: 'manager@store1.vn',
  RECEPTIONIST: 'reception@store1.vn',
  VET: 'doctor@store1.vn',
  CARETAKER: 'caretaker@store1.vn',
} as const;

// ─── Ghi lại request thật đi qua mock để biết trang gọi những endpoint nào và bị trả mã gì ───

function recordCalls() {
  const inner = apiClient.defaults.adapter as AxiosAdapter;
  const calls: { key: string; status: number }[] = [];
  const keyOf = (config: InternalAxiosRequestConfig) => `${(config.method ?? 'get').toUpperCase()} ${new URL(config.url ?? '', 'http://mock').pathname}`;
  apiClient.defaults.adapter = async (config) => {
    try {
      const response = await inner(config);
      calls.push({ key: keyOf(config), status: response.status });
      return response;
    } catch (error) {
      calls.push({ key: keyOf(config), status: (error as AxiosError).response?.status ?? 0 });
      throw error;
    }
  };
  return { calls, stop: () => { apiClient.defaults.adapter = inner; } };
}

/** Testing Library gộp khoảng trắng (kể cả NBSP của Intl) trong văn bản DOM, nên chuỗi cần tìm cũng phải gộp. */
const money = (amount: number) => formatCurrency(amount).replace(/\s/g, ' ');

const VISITS = 'GET /api/visits';
const ORDERS = 'GET /api/orders';
const REVENUE = 'GET /api/reports/revenue';

describe('tải trang không bị 403 ở vai trò nào được vào /admin/dashboard, và chỉ gọi endpoint vai trò đó được phép', () => {
  // SUPER_MANAGER: visit-v1 #2 và sales-v1 #2 chỉ mở cho A05–A08 (BE trả 403), chỉ report-v1 #1 mở cho A04.
  // RECEPTIONIST, VET, CARETAKER: report-v1 chỉ mở cho A04, A05.
  it.each([
    ['SUPER_MANAGER', [REVENUE]],
    ['BRANCH_MANAGER', [ORDERS, REVENUE, VISITS]],
    ['RECEPTIONIST', [ORDERS, VISITS]],
    ['VET', [ORDERS, VISITS]],
    ['CARETAKER', [ORDERS, VISITS]],
  ] as const)('%s', async (role, expected) => {
    await startDesk(ACCOUNTS[role]);
    const { calls, stop } = recordCalls();
    try {
      const { client } = renderDesk(<AdminDashboardPage />);
      await waitFor(() => expect(calls.length).toBeGreaterThanOrEqual(expected.length));
      await waitFor(() => expect(client.isFetching()).toBe(0));
    } finally {
      stop();
    }

    expect([...new Set(calls.map((c) => c.key))].sort()).toEqual([...expected]);
    expect(calls.filter((c) => c.status === 403 || c.status === 401)).toEqual([]);
  });
});

// ─── Số liệu: nguồn là API, không phải mảng cứng hay clinic-db ───

const order = (orderId: number): OrderSummary => ({
  orderId,
  code: `DH-2610-0000${orderId}`,
  source: 'VISIT',
  status: 'PENDING',
  customerId: orderId,
  customerName: `Khách ${orderId}`,
  branchId: 1,
  totalAmount: 50_000,
  createdAt: '2026-10-22T03:00:00Z',
  pendingAt: '2026-10-22T03:05:00Z',
});

const TODAY_VISITS: Visit[] = [
  makeVisit(1, { serviceGroup: 'MEDICAL', status: 'WAITING' }),
  makeVisit(2, { serviceGroup: 'MEDICAL', status: 'WAITING' }),
  makeVisit(3, { serviceGroup: 'GROOMING', status: 'IN_PROGRESS' }),
  makeVisit(4, { serviceGroup: 'MEDICAL', status: 'COMPLETED' }),
  makeVisit(5, { serviceGroup: 'GROOMING', status: 'CANCELLED' }),
];
// Hàng đợi không `date`: lượt chưa kết thúc của mọi ngày, kể cả lượt dở từ hôm qua (id 6) không có trong danh sách hôm nay.
const OPEN_VISITS: Visit[] = [TODAY_VISITS[0], TODAY_VISITS[1], TODAY_VISITS[2], makeVisit(6, { status: 'IN_PROGRESS', checkedInAt: '2026-10-21T03:00:00Z' })];

const REVENUE_REPORT: RevenueReport = { from: '2026-10-22', to: '2026-10-22', rows: [], totalAmount: 1_250_000 };

function stubApis(revenue: RevenueReport | Error = REVENUE_REPORT) {
  const listQueue = vi.spyOn(visitApi, 'listQueue').mockImplementation(async (query: VisitQueueQuery = {}) => (query.date ? TODAY_VISITS : OPEN_VISITS));
  const listOrders = vi.spyOn(salesApi, 'listOrders').mockResolvedValue(pageOf([order(1), order(2), order(3)]));
  const report = vi.mocked(reportApi.revenue);
  if (revenue instanceof Error) report.mockRejectedValue(revenue);
  else report.mockResolvedValue(revenue);
  return { listQueue, listOrders, report };
}

/** Giá trị của thẻ KPI theo nhãn. */
const valueOf = (label: string) => within(screen.getByText(label).parentElement!);

describe('số liệu của chi nhánh (BRANCH_MANAGER)', () => {
  it('đếm lượt hôm nay, đang chờ / đang làm, đơn chờ thu và doanh thu từ API; nhóm dịch vụ không tính lượt đã hủy', async () => {
    await startDesk(ACCOUNTS.BRANCH_MANAGER);
    const { listQueue, listOrders, report } = stubApis();
    renderDesk(<AdminDashboardPage />);

    expect(await valueOf('Lượt tiếp nhận hôm nay').findByText('5')).toBeInTheDocument();
    expect(valueOf('Đã thu hôm nay').getByText(money(1_250_000))).toBeInTheDocument();
    // Đang chờ / đang làm lấy từ hàng đợi không `date` (có lượt dở hôm qua), không suy từ danh sách hôm nay (sẽ ra "2 / 1").
    expect(valueOf('Đang chờ / đang làm').getByText('2 / 2')).toBeInTheDocument();
    expect(valueOf('Đơn chờ thu tiền').getByText('3')).toBeInTheDocument();

    expect(valueOf('Khám/Tiêm').getByText('3 lượt')).toBeInTheDocument();
    expect(valueOf('Thẩm mỹ').getByText('1 lượt')).toBeInTheDocument();

    expect(listQueue).toHaveBeenCalledWith({ date: '2026-10-22' });
    expect(listQueue).toHaveBeenCalledWith({});
    expect(listOrders).toHaveBeenCalledWith({ status: 'PENDING', size: 100 });
    expect(report).toHaveBeenCalledWith({ from: '2026-10-22', to: '2026-10-22' });
  });

  it('không còn nhật ký hoạt động (không có nguồn hợp đồng)', async () => {
    await startDesk(ACCOUNTS.BRANCH_MANAGER);
    stubApis();
    renderDesk(<AdminDashboardPage />);

    await screen.findByText('Dịch vụ hôm nay');
    expect(screen.queryByText('Hoạt động gần đây')).not.toBeInTheDocument();
  });

  it('hỏi theo ngày Việt Nam: 03:30 ngày 23/10 giờ Việt Nam (còn là 22/10 theo UTC) vẫn hỏi ngày 23/10', async () => {
    await startDesk(ACCOUNTS.BRANCH_MANAGER, '2026-10-22T20:30:00Z');
    const { listQueue, report } = stubApis();
    renderDesk(<AdminDashboardPage />);

    await waitFor(() => expect(report).toHaveBeenCalledWith({ from: '2026-10-23', to: '2026-10-23' }));
    expect(listQueue).toHaveBeenCalledWith({ date: '2026-10-23' });
  });

  it('báo cáo doanh thu hỏng thì chỉ thẻ "Đã thu hôm nay" hiện "–" kèm Thử lại, các thẻ khác vẫn có số', async () => {
    await startDesk(ACCOUNTS.BRANCH_MANAGER);
    const { report } = stubApis(new ApiError('RESOURCE_NOT_FOUND', 'Chưa có báo cáo', 404));
    const { user } = renderDesk(<AdminDashboardPage />);

    const revenue = valueOf('Đã thu hôm nay');
    expect(await revenue.findByText('–')).toBeInTheDocument();
    expect(revenue.getByRole('alert')).toHaveTextContent('Không tải được');
    expect(valueOf('Đơn chờ thu tiền').getByText('3')).toBeInTheDocument();
    expect(valueOf('Lượt tiếp nhận hôm nay').getByText('5')).toBeInTheDocument();
    expect(screen.getAllByRole('alert')).toHaveLength(1);

    report.mockResolvedValue(REVENUE_REPORT);
    await user.click(revenue.getByRole('button', { name: 'Thử lại' }));
    expect(await valueOf('Đã thu hôm nay').findByText(money(1_250_000))).toBeInTheDocument();
  });

  it('hàng đợi hỏng thì thẻ lượt hôm nay và nhóm dịch vụ hiện Thử lại, doanh thu vẫn có số', async () => {
    await startDesk(ACCOUNTS.BRANCH_MANAGER);
    const { listQueue } = stubApis();
    listQueue.mockImplementation(async (query: VisitQueueQuery = {}) => {
      if (query.date) throw new ApiError('RESOURCE_NOT_FOUND', 'Hàng đợi tạm lỗi', 404);
      return OPEN_VISITS;
    });
    renderDesk(<AdminDashboardPage />);

    const group = (await screen.findByText('Dịch vụ hôm nay')).closest('.card')!;
    expect(await within(group as HTMLElement).findByRole('button', { name: 'Thử lại' })).toBeInTheDocument();
    expect(valueOf('Lượt tiếp nhận hôm nay').getByText('–')).toBeInTheDocument();
    expect(valueOf('Đã thu hôm nay').getByText(money(1_250_000))).toBeInTheDocument();
  });
});

describe('số liệu theo vai trò', () => {
  it('SUPER_MANAGER chỉ thấy doanh thu toàn chuỗi: không thẻ hàng đợi, đơn chờ thu, nhóm dịch vụ, lối tắt bàn làm việc', async () => {
    await startDesk(ACCOUNTS.SUPER_MANAGER);
    const { listQueue, listOrders, report } = stubApis();
    renderDesk(<AdminDashboardPage />);

    expect(await valueOf('Đã thu hôm nay (toàn chuỗi)').findByText(money(1_250_000))).toBeInTheDocument();
    expect(report).toHaveBeenCalledWith({ from: '2026-10-22', to: '2026-10-22' });
    for (const label of ['Lượt tiếp nhận hôm nay', 'Đang chờ / đang làm', 'Đơn chờ thu tiền', 'Dịch vụ hôm nay', 'Mở bàn làm việc']) {
      expect(screen.queryByText(label)).not.toBeInTheDocument();
    }
    expect(listQueue).not.toHaveBeenCalled();
    expect(listOrders).not.toHaveBeenCalled();
  });

  it.each(['RECEPTIONIST', 'VET', 'CARETAKER'] as const)('%s thấy số liệu hàng đợi và đơn chờ thu nhưng không thấy doanh thu', async (role) => {
    await startDesk(ACCOUNTS[role]);
    const { report } = stubApis();
    renderDesk(<AdminDashboardPage />);

    expect(await valueOf('Đơn chờ thu tiền').findByText('3')).toBeInTheDocument();
    expect(valueOf('Lượt tiếp nhận hôm nay').getByText('5')).toBeInTheDocument();
    expect(screen.queryByText(/Đã thu hôm nay/)).not.toBeInTheDocument();
    expect(report).not.toHaveBeenCalled();
  });

  it.each([
    ['RECEPTIONIST', [['Lễ tân', '/staff/reception']]],
    ['BRANCH_MANAGER', [['Lễ tân', '/staff/reception']]],
    ['VET', [['Phòng khám', '/staff/doctor']]],
    ['CARETAKER', [['Grooming', '/staff/grooming']]],
  ] as const)('%s có lối tắt vào bàn làm việc của mình', async (role, links) => {
    await startDesk(ACCOUNTS[role]);
    stubApis();
    renderDesk(<AdminDashboardPage />);

    const card = (await screen.findByText('Mở bàn làm việc')).closest('.card') as HTMLElement;
    expect(within(card).getAllByRole('link').map((a) => [a.textContent, a.getAttribute('href')])).toEqual(links);
  });
});
