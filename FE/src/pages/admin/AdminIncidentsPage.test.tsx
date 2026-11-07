import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { complaintApi } from '../../shared/api/complaint.api';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminIncidentsPage } from './AdminIncidentsPage';

// Giờ cố định 06/11/2026 09:00 (giờ VN). Dữ liệu mẫu ở shared/api/mock/complaint.mock.ts, đang xử lý (chờ nhận + đang xử lý):
//   chi nhánh 1 (manager@store1.vn thấy 5): #1 Làm đẹp (chờ nhận), #2 Chất lượng dịch vụ (đang xử lý), #7 Mua hàng (chờ nhận, quá hạn), #10 Chất lượng dịch vụ
//   (lễ tân Lan Chi tạo thay), #12 Thái độ nhân viên (đang xử lý, quá hạn); thêm #9 chi nhánh 2 và #11 không gắn chi nhánh: quản lý chuỗi (chuoi@petcare.vn) thấy 7.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-06T09:00:00+07:00'));
});
afterEach(() => vi.useRealTimers());

async function renderPage(email = 'manager@store1.vn') {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AdminIncidentsPage />
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const dataRows = async () => within(await screen.findByRole('table', { name: 'Danh sách khiếu nại' })).getAllByRole('row').slice(1);
/** Hàng của khiếu nại theo nội dung cột Chủ đề + Trạng thái. */
const rowOf = async (topic: string, status: string) => (await dataRows()).find((r) => r.textContent?.includes(topic) && r.textContent.includes(status))!;
const openRow = async (user: Awaited<ReturnType<typeof renderPage>>, row: HTMLElement) => {
  await user.click(within(row).getByRole('button', { name: /^Xem khiếu nại/ }));
  return screen.findByRole('dialog', { name: /^Khiếu nại KN-/ });
};

describe('AdminIncidentsPage: danh sách (#8)', () => {
  it('quản lý chi nhánh 1 thấy 5 khiếu nại cần xử lý, hai ca quá hạn có nhãn', async () => {
    await renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Khiếu nại' })).toBeInTheDocument();
    const rows = await dataRows();
    expect(rows).toHaveLength(5);
    expect(screen.getByRole('tab', { name: 'Cần xử lý' })).toHaveAttribute('aria-selected', 'true');
    expect(rows.filter((r) => r.textContent?.includes('Quá hạn'))).toHaveLength(2);
    expect(rows.every((r) => r.textContent?.includes('Chờ nhận') || r.textContent?.includes('Đang xử lý'))).toBe(true);
  });

  it('lọc chỉ ca quá hạn và lọc theo trạng thái', async () => {
    const user = await renderPage();
    await dataRows();
    await user.click(screen.getByLabelText('Chỉ khiếu nại quá hạn (đã leo thang)'));
    await waitFor(async () => expect(await dataRows()).toHaveLength(2));
    await user.click(screen.getByLabelText('Chỉ khiếu nại quá hạn (đã leo thang)'));
    await user.click(screen.getByRole('tab', { name: 'Đã đóng' }));
    await waitFor(async () => expect((await dataRows()).every((r) => r.textContent?.includes('Đã đóng'))).toBe(true));
  });

  it('quản lý chuỗi thấy mọi chi nhánh và khiếu nại không gắn chi nhánh (7)', async () => {
    await renderPage('chuoi@petcare.vn');
    const rows = await dataRows();
    expect(rows).toHaveLength(7);
    expect(rows.some((r) => r.textContent?.includes('Không gắn chi nhánh'))).toBe(true);
  });
});

describe('AdminIncidentsPage: xử lý (#9–12)', () => {
  it('nhận xử lý rồi kết luận: trạng thái đổi theo từng bước', async () => {
    const user = await renderPage();
    const dialog = await openRow(user, await rowOf('Làm đẹp (grooming)', 'Chờ nhận'));
    expect(within(dialog).getByText(/Bé Mochi bị cắt tỉa lông quá ngắn/)).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Kết luận' })).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Nhận xử lý' }));
    expect(await within(dialog).findByText('Bạn đã nhận xử lý khiếu nại này.')).toBeInTheDocument();
    await waitFor(() => expect(within(dialog).queryByRole('button', { name: 'Nhận xử lý' })).not.toBeInTheDocument());
    await user.click(within(dialog).getByRole('button', { name: 'Kết luận' }));
    await user.click(within(dialog).getByRole('button', { name: 'Lưu kết luận' }));
    expect(await within(dialog).findByText('Nhập nội dung trước khi gửi.')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Kết luận xử lý'), 'Đã xin lỗi khách và hoàn phí');
    await user.click(within(dialog).getByRole('button', { name: 'Lưu kết luận' }));
    await waitFor(() => expect(within(dialog).getByText('Đã giải quyết')).toBeInTheDocument());
    expect(within(dialog).getByText('Đã xin lỗi khách và hoàn phí')).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Kết luận' })).not.toBeInTheDocument();
  });

  it('trả lời khách: tin nhắn hiện trong trao đổi', async () => {
    const user = await renderPage();
    const dialog = await openRow(user, await rowOf('Chất lượng dịch vụ', 'Đang xử lý'));
    await user.type(within(dialog).getByLabelText('Trả lời khách'), 'Chi nhánh đã đối chiếu camera, sẽ gọi chị chiều nay');
    await user.click(within(dialog).getByRole('button', { name: 'Gửi trả lời' }));
    expect(await within(dialog).findByText('Chi nhánh đã đối chiếu camera, sẽ gọi chị chiều nay')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Trả lời khách')).toHaveValue('');
  });

  it('người khác đã nhận trước (409): hiện lỗi, chi tiết tải lại thành Đang xử lý', async () => {
    const user = await renderPage();
    const dialog = await openRow(user, await rowOf('Mua hàng', 'Chờ nhận'));
    const take = await within(dialog).findByRole('button', { name: 'Nhận xử lý' });
    const id = (await complaintApi.listComplaints({ escalated: true })).content.find((c) => c.topic === 'PURCHASE')!.complaintId;
    await complaintApi.takeComplaint(id);
    await user.click(take);
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái khiếu nại');
    await waitFor(() => expect(within(dialog).queryByRole('button', { name: 'Nhận xử lý' })).not.toBeInTheDocument());
    expect(within(dialog).getByRole('button', { name: 'Kết luận' })).toBeInTheDocument();
  });

  it('kết luận lệch trạng thái: khiếu nại đã được giải quyết trước (409)', async () => {
    const user = await renderPage();
    const dialog = await openRow(user, await rowOf('Chất lượng dịch vụ', 'Đang xử lý'));
    await user.click(await within(dialog).findByRole('button', { name: 'Kết luận' }));
    await user.type(within(dialog).getByLabelText('Kết luận xử lý'), 'Đã xử lý xong');
    const id = (await complaintApi.listComplaints({ status: 'IN_PROGRESS' })).content.find((c) => c.topic === 'SERVICE_QUALITY')!.complaintId;
    await complaintApi.resolveComplaint(id, { resolution: 'Người khác kết luận trước' });
    await user.click(within(dialog).getByRole('button', { name: 'Lưu kết luận' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái khiếu nại');
  });
});

describe('AdminIncidentsPage: vai trò', () => {
  it('lễ tân chỉ trả lời được: thấy khiếu nại do mình tạo, không có Nhận xử lý / Kết luận', async () => {
    const user = await renderPage('reception@store1.vn');
    const rows = await dataRows();
    expect(rows).toHaveLength(1);
    const dialog = await openRow(user, rows[0]);
    expect(within(dialog).getByText(/Khách phản ánh tại quầy/)).toBeInTheDocument();
    expect(within(dialog).getByText('Lê Hoàng Long (lễ tân tạo thay)')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Trả lời khách')).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Nhận xử lý' })).not.toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Kết luận' })).not.toBeInTheDocument();
  });

  it('khiếu nại đã đóng: không còn ô trả lời và nút xử lý', async () => {
    const user = await renderPage();
    await dataRows();
    await user.click(screen.getByRole('tab', { name: 'Đã đóng' }));
    const row = await waitFor(async () => {
      const r = (await dataRows()).find((x) => x.textContent?.includes('Đã đóng'));
      expect(r).toBeDefined();
      return r!;
    });
    const dialog = await openRow(user, row);
    expect(within(dialog).queryByLabelText('Trả lời khách')).not.toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Nhận xử lý' })).not.toBeInTheDocument();
  });
});
