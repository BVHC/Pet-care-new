import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { workforceApi } from '../../shared/api/workforce.api';
import { useSession } from '../../shared/stores/session.store';
import { MySchedulePage } from './MySchedulePage';

// Giờ cố định thứ Sáu 06/11/2026 09:00 (giờ VN); lịch xem mặc định 06/11–19/11. Dữ liệu mẫu ở shared/api/mock/workforce.mock.ts:
//   doctor@store1.vn (BS. Minh Anh): hôm nay ca chiều, 08/11 ca sáng, 09/11 ca chiều có yêu cầu nghỉ #1 đang chờ, yêu cầu #5 đã rút.
//   caretaker@store1.vn (Thu Hà): 07/11 ca sáng 07:30 (còn dưới 24 giờ), 10/11 ca sáng có yêu cầu đổi ca #2 đang chờ.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-06T09:00:00+07:00'));
});
afterEach(() => vi.useRealTimers());

async function renderPage(email = 'doctor@store1.vn') {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MySchedulePage />
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const scheduleRows = async () => within(await screen.findByRole('table', { name: 'Lịch làm việc của tôi' })).getAllByRole('row').slice(1);
const requestRows = async () => within(await screen.findByRole('table', { name: 'Yêu cầu của tôi' })).getAllByRole('row').slice(1);

describe('MySchedulePage: lịch của tôi (#8)', () => {
  it('chỉ hiện ca của chính mình trong 14 ngày từ hôm nay, kèm cờ yêu cầu đang chờ', async () => {
    await renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Lịch làm việc' })).toBeInTheDocument();
    const rows = await scheduleRows();
    expect(screen.getByLabelText('Từ ngày')).toHaveValue('2026-11-06');
    expect(screen.getByLabelText('Đến ngày')).toHaveValue('2026-11-19');
    expect(rows.length).toBeGreaterThan(5);
    expect(rows[0]).toHaveTextContent('Ca chiều');
    expect(rows[0]).toHaveTextContent('13:00–17:30');
    // 09/11 đã có yêu cầu chờ: hiện cờ, không còn nút gửi thêm.
    const pending = rows.find((r) => r.querySelector('time')?.getAttribute('datetime') === '2026-11-09')!;
    expect(within(pending).getByText('Đã gửi yêu cầu, chờ duyệt')).toBeInTheDocument();
    expect(within(pending).queryByRole('button')).not.toBeInTheDocument();
  });

  it('khoảng ngày quá 62 ngày: hiện lỗi BR-NS-03 của máy chủ', async () => {
    await renderPage();
    await scheduleRows();
    fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2026-06-01' } });
    expect(await screen.findByText('Không tải được lịch làm việc')).toBeInTheDocument();
    expect(screen.getByText(/Khoảng ngày xem lịch tối đa 62 ngày/)).toBeInTheDocument();
  });
});

describe('MySchedulePage: gửi yêu cầu (#9)', () => {
  it('xin nghỉ: ca có cờ chờ duyệt và yêu cầu hiện ở danh sách của tôi', async () => {
    const user = await renderPage();
    const before = (await requestRows()).length;
    await user.click(await screen.findByRole('button', { name: 'Xin nghỉ hoặc đổi ca: Ca sáng, 2026-11-11' }));
    const dialog = await screen.findByRole('dialog', { name: 'Xin nghỉ / đổi ca' });
    await user.click(within(dialog).getByRole('button', { name: 'Gửi yêu cầu' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Nhập lý do.');
    await user.type(within(dialog).getByLabelText('Lý do'), 'Việc gia đình');
    await user.click(within(dialog).getByRole('button', { name: 'Gửi yêu cầu' }));
    expect(await screen.findByText(/^Đã gửi yêu cầu xin nghỉ Ca sáng ngày 2026-11-11/)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Xin nghỉ hoặc đổi ca: Ca sáng, 2026-11-11' })).not.toBeInTheDocument());
    await waitFor(async () => expect(await requestRows()).toHaveLength(before + 1));
    expect((await requestRows())[0]).toHaveTextContent('Chờ duyệt');
  });

  it('đổi ca: ca đích lấy từ các ca khác trong lịch của tôi', async () => {
    const user = await renderPage();
    await user.click(await screen.findByRole('button', { name: 'Xin nghỉ hoặc đổi ca: Ca sáng, 2026-11-11' }));
    const dialog = await screen.findByRole('dialog', { name: 'Xin nghỉ / đổi ca' });
    await user.click(within(dialog).getByLabelText('Đổi ca'));
    const target = within(dialog).getByLabelText('Đổi sang ca');
    expect(within(target).getAllByRole('option').map((o) => o.textContent)).toEqual(['Chọn ca', 'Ca chiều (13:00–17:30)']);
    await user.click(within(dialog).getByRole('button', { name: 'Gửi yêu cầu' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Chọn ca muốn đổi sang.');
    await user.selectOptions(target, 'Ca chiều (13:00–17:30)');
    await user.type(within(dialog).getByLabelText('Lý do'), 'Sáng bận việc riêng');
    await user.click(within(dialog).getByRole('button', { name: 'Gửi yêu cầu' }));
    expect(await screen.findByText(/^Đã gửi yêu cầu đổi ca Ca sáng ngày 2026-11-11/)).toBeInTheDocument();
    await waitFor(async () => expect((await requestRows())[0]).toHaveTextContent('Đổi Ca sáng sang Ca chiều'));
  });

  it('ca bắt đầu trong chưa đầy 24 giờ: máy chủ từ chối BR-NS-04, lỗi hiện trong hộp', async () => {
    const user = await renderPage('caretaker@store1.vn');
    await user.click(await screen.findByRole('button', { name: 'Xin nghỉ hoặc đổi ca: Ca sáng, 2026-11-07' }));
    const dialog = await screen.findByRole('dialog', { name: 'Xin nghỉ / đổi ca' });
    await user.type(within(dialog).getByLabelText('Lý do'), 'Xin nghỉ gấp');
    await user.click(within(dialog).getByRole('button', { name: 'Gửi yêu cầu' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Phải gửi yêu cầu trước giờ bắt đầu ca ít nhất 24 giờ');
    expect(screen.getByRole('dialog', { name: 'Xin nghỉ / đổi ca' })).toBeInTheDocument();
  });
});

describe('MySchedulePage: yêu cầu của tôi (#10–11)', () => {
  it('liệt kê yêu cầu mới nhất trước, lọc theo trạng thái, chỉ yêu cầu chờ duyệt có nút Rút', async () => {
    const user = await renderPage();
    const rows = await requestRows();
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('Xin nghỉ Ca chiều');
    expect(rows[0]).toHaveTextContent('Chờ duyệt');
    expect(within(rows[0]).getByRole('button', { name: /^Rút yêu cầu/ })).toBeInTheDocument();
    expect(rows[1]).toHaveTextContent('Đã rút');
    expect(within(rows[1]).queryByRole('button')).not.toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Lọc trạng thái yêu cầu'), 'WITHDRAWN');
    await waitFor(async () => expect(await requestRows()).toHaveLength(1));
  });

  it('rút yêu cầu đang chờ: chuyển sang Đã rút và ca được gửi yêu cầu lại', async () => {
    const user = await renderPage();
    await user.click(await screen.findByRole('button', { name: /^Rút yêu cầu: Xin nghỉ Ca chiều/ }));
    expect(await screen.findByText(/^Đã rút yêu cầu: Xin nghỉ Ca chiều/)).toBeInTheDocument();
    await waitFor(async () => expect((await requestRows())[0]).toHaveTextContent('Đã rút'));
    expect(await screen.findByRole('button', { name: 'Xin nghỉ hoặc đổi ca: Ca chiều, 2026-11-09' })).toBeInTheDocument();
  });

  it('yêu cầu đã được xử lý trước khi rút (409): hiện lỗi và danh sách tải lại', async () => {
    const user = await renderPage();
    const button = await screen.findByRole('button', { name: /^Rút yêu cầu: Xin nghỉ Ca chiều/ });
    await workforceApi.withdrawShiftRequest(1);
    await user.click(button);
    expect(await screen.findByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái');
    await waitFor(() => expect(screen.queryByRole('button', { name: /^Rút yêu cầu/ })).not.toBeInTheDocument());
  });
});

describe('MySchedulePage: vai trò', () => {
  it('quản lý chi nhánh không có lịch cá nhân: chỉ thấy lời giải thích', async () => {
    await renderPage('manager@store1.vn');
    expect(screen.getByText('Chức năng dành cho lễ tân, bác sĩ và nhân viên chăm sóc')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('bác sĩ chưa được phân ca (doctor3): báo trống, không lỗi', async () => {
    await renderPage('doctor3@store1.vn');
    expect(await screen.findByText('Chưa có ca nào trong khoảng này')).toBeInTheDocument();
    expect(await screen.findByText('Chưa có yêu cầu nào')).toBeInTheDocument();
  });
});
