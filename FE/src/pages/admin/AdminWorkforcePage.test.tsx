import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { workforceApi } from '../../shared/api/workforce.api';
import { useSession } from '../../shared/stores/session.store';
import { AdminWorkforcePage } from './AdminWorkforcePage';

// Giờ cố định thứ Sáu 06/11/2026 09:00 (giờ VN); tuần xem mặc định là 02/11–08/11. Dữ liệu mẫu ở shared/api/mock/workforce.mock.ts (chi nhánh 1):
//   5 ca (Ca đêm cũ ngừng dùng); nhân viên 4 Lan Chi, 5 BS. Minh Anh, 6 Thu Hà, 21 BS. Quốc Bảo luân phiên ca sáng (07:30) / ca chiều (13:00) / nghỉ.
//   Hôm nay: Lan Chi ca sáng (đã bắt đầu), BS. Minh Anh ca chiều (chưa bắt đầu). Yêu cầu chờ: BS. Minh Anh xin nghỉ 09/11, Thu Hà đổi ca 10/11.

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
      <AdminWorkforcePage />
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const openTab = (user: Awaited<ReturnType<typeof renderPage>>, name: string) => user.click(screen.getByRole('tab', { name }));
const bodyRows = async (name: string) => within(await screen.findByRole('table', { name })).getAllByRole('row').slice(1);

describe('AdminWorkforcePage: vai trò', () => {
  it('lễ tân không thấy chức năng nhân sự và không có tab nào', async () => {
    await renderPage('reception@store1.vn');
    expect(screen.getByText('Chức năng dành cho quản lý chi nhánh')).toBeInTheDocument();
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();
  });

  it('quản lý chuỗi cũng không có chức năng nhân sự', async () => {
    await renderPage('chuoi@petcare.vn');
    expect(screen.getByText('Chức năng dành cho quản lý chi nhánh')).toBeInTheDocument();
  });
});

describe('tab Ca làm việc (#1–3)', () => {
  it('liệt kê ca theo giờ bắt đầu, ca ngừng dùng ở cuối', async () => {
    await renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Nhân sự' })).toBeInTheDocument();
    const rows = await bodyRows('Danh sách ca làm việc');
    expect(rows.map((r) => within(r).getAllByRole('cell')[0].textContent)).toEqual(['Ca sáng', 'Ca hành chính', 'Ca chiều', 'Ca tối', 'Ca đêm cũ']);
    expect(within(rows[4]).getByText('Ngừng')).toBeInTheDocument();
  });

  it('thêm ca mới: hộp đóng, ca hiện trong bảng', async () => {
    const user = await renderPage();
    await user.click(await screen.findByRole('button', { name: 'Thêm ca' }));
    const dialog = await screen.findByRole('dialog', { name: 'Thêm ca làm việc' });
    await user.type(within(dialog).getByLabelText('Tên ca'), 'Ca khuya');
    fireEvent.change(within(dialog).getByLabelText('Giờ bắt đầu'), { target: { value: '22:00' } });
    fireEvent.change(within(dialog).getByLabelText('Giờ kết thúc'), { target: { value: '23:30' } });
    await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));
    expect(await screen.findByText('Đã tạo Ca khuya.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(await screen.findByRole('row', { name: /Ca khuya/ })).toHaveTextContent('22:00–23:30');
  });

  it('tên ca trùng: lỗi BR-NS-01 hiện ngay trong hộp, hộp không đóng', async () => {
    const user = await renderPage();
    await user.click(await screen.findByRole('button', { name: 'Thêm ca' }));
    const dialog = await screen.findByRole('dialog', { name: 'Thêm ca làm việc' });
    await user.type(within(dialog).getByLabelText('Tên ca'), 'Ca sáng');
    fireEvent.change(within(dialog).getByLabelText('Giờ bắt đầu'), { target: { value: '05:00' } });
    fireEvent.change(within(dialog).getByLabelText('Giờ kết thúc'), { target: { value: '06:00' } });
    await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Tên ca đã tồn tại trong chi nhánh');
  });

  it('ngừng dùng ca còn phân ca chưa làm: lỗi BR-NS-01 trên bảng; dùng lại ca cũ được', async () => {
    const user = await renderPage();
    await user.click(await screen.findByRole('button', { name: 'Ngừng dùng Ca sáng' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Ca còn phân ca từ hôm nay trở đi, không ngừng dùng được');
    await user.click(screen.getByRole('button', { name: 'Dùng lại Ca đêm cũ' }));
    expect(await screen.findByText('Ca đêm cũ dùng lại được.')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('row', { name: /Ca đêm cũ/ })).toHaveTextContent('Đang dùng'));
  });

  it('sửa tên ca: chỉ gửi trường đã đổi', async () => {
    const user = await renderPage();
    await user.click(await screen.findByRole('button', { name: 'Sửa Ca tối' }));
    const dialog = await screen.findByRole('dialog', { name: 'Sửa ca làm việc' });
    const name = within(dialog).getByLabelText('Tên ca');
    await user.clear(name);
    await user.type(name, 'Ca tối muộn');
    await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));
    expect(await screen.findByText('Đã cập nhật Ca tối muộn.')).toBeInTheDocument();
    expect(await screen.findByRole('row', { name: /Ca tối muộn/ })).toHaveTextContent('17:30–21:00');
  });
});

describe('tab Phân ca (#4–7)', () => {
  it('mặc định là tuần này; hôm nay chỉ ca chưa bắt đầu mới hủy được, ngày tương lai không ghi vắng được', async () => {
    const user = await renderPage();
    await openTab(user, 'Phân ca');
    await bodyRows('Lịch phân ca');
    expect(screen.getByLabelText('Từ ngày')).toHaveValue('2026-11-02');
    expect(screen.getByLabelText('Đến ngày')).toHaveValue('2026-11-08');
    expect(screen.getAllByRole('button', { name: /^Hủy phân ca: .*, 2026-11-06$/ })).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Hủy phân ca: BS. Minh Anh, 2026-11-06' })).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /^Ghi vắng: .*, 2026-11-06$/ })).toHaveLength(2);
    expect(screen.queryByRole('button', { name: /^Ghi vắng: .*, 2026-11-07$/ })).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /^Hủy phân ca: .*, 2026-11-07$/ }).length).toBeGreaterThan(0);
  });

  it('hủy phân ca kèm lý do: hàng chuyển sang Đã hủy', async () => {
    const user = await renderPage();
    await openTab(user, 'Phân ca');
    await user.click(await screen.findByRole('button', { name: 'Hủy phân ca: BS. Minh Anh, 2026-11-06' }));
    const dialog = await screen.findByRole('dialog', { name: 'Hủy phân ca' });
    await user.click(within(dialog).getByRole('button', { name: 'Hủy phân ca' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Nhập lý do hủy.');
    await user.type(within(dialog).getByLabelText('Lý do hủy'), 'Chi nhánh sửa điện');
    await user.click(within(dialog).getByRole('button', { name: 'Hủy phân ca' }));
    expect(await screen.findByText('Đã hủy phân ca của BS. Minh Anh.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Hủy phân ca: BS. Minh Anh, 2026-11-06' })).not.toBeInTheDocument());
    expect(screen.getAllByText('Đã hủy').length).toBeGreaterThan(0);
  });

  it('ghi vắng đột xuất cho ca đã bắt đầu hôm nay', async () => {
    const user = await renderPage();
    await openTab(user, 'Phân ca');
    await user.click(await screen.findByRole('button', { name: 'Ghi vắng: Lan Chi, 2026-11-06' }));
    const dialog = await screen.findByRole('dialog', { name: 'Ghi nhận vắng đột xuất' });
    await user.type(within(dialog).getByLabelText('Lý do vắng'), 'Không liên lạc được');
    await user.click(within(dialog).getByRole('button', { name: 'Ghi vắng' }));
    expect(await screen.findByText('Đã ghi vắng cho Lan Chi.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Ghi vắng: Lan Chi, 2026-11-06' })).not.toBeInTheDocument());
  });

  it('ghi vắng ca quá 7 ngày: lỗi BR-NS-07 hiện trong hộp', async () => {
    const user = await renderPage();
    await openTab(user, 'Phân ca');
    await bodyRows('Lịch phân ca');
    fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2026-10-28' } });
    fireEvent.change(screen.getByLabelText('Đến ngày'), { target: { value: '2026-10-28' } });
    await user.click(await screen.findByRole('button', { name: 'Ghi vắng: BS. Quốc Bảo, 2026-10-28' }));
    const dialog = await screen.findByRole('dialog', { name: 'Ghi nhận vắng đột xuất' });
    await user.type(within(dialog).getByLabelText('Lý do vắng'), 'Quên báo');
    await user.click(within(dialog).getByRole('button', { name: 'Ghi vắng' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Chỉ ghi nhận vắng cho ca trong vòng 7 ngày gần đây');
  });

  it('khoảng ngày quá 62 ngày: hiện lỗi BR-NS-03 của máy chủ', async () => {
    const user = await renderPage();
    await openTab(user, 'Phân ca');
    await bodyRows('Lịch phân ca');
    fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2026-08-01' } });
    expect(await screen.findByText('Không tải được lịch phân ca')).toBeInTheDocument();
    expect(screen.getByText(/Khoảng ngày tối đa 62 ngày/)).toBeInTheDocument();
  });

  it('phân ca mới cho nhân viên đang nghỉ hôm nay: thêm một dòng', async () => {
    const user = await renderPage();
    await openTab(user, 'Phân ca');
    const before = (await bodyRows('Lịch phân ca')).length;
    await user.click(screen.getByRole('button', { name: 'Phân ca' }));
    const dialog = await screen.findByRole('dialog', { name: 'Phân ca' });
    await waitFor(() => expect(within(dialog).getByRole('option', { name: /Ca chiều/ })).toBeInTheDocument());
    await user.selectOptions(within(dialog).getByLabelText('Nhân viên'), '6');
    await user.selectOptions(within(dialog).getByLabelText('Ca làm việc'), '2');
    expect(within(dialog).getByLabelText('Ngày làm')).toHaveValue('2026-11-06');
    await user.click(within(dialog).getByRole('button', { name: 'Phân ca' }));
    expect(await screen.findByText('Đã phân Ca chiều ngày 2026-11-06 cho Thu Hà.')).toBeInTheDocument();
    await waitFor(async () => expect(await bodyRows('Lịch phân ca')).toHaveLength(before + 1));
  });

  it('phân ca chồng giờ: lỗi BR-NS-02 hiện trong hộp', async () => {
    const user = await renderPage();
    await openTab(user, 'Phân ca');
    await bodyRows('Lịch phân ca');
    await user.click(screen.getByRole('button', { name: 'Phân ca' }));
    const dialog = await screen.findByRole('dialog', { name: 'Phân ca' });
    await waitFor(() => expect(within(dialog).getByRole('option', { name: /Ca hành chính/ })).toBeInTheDocument());
    await user.selectOptions(within(dialog).getByLabelText('Nhân viên'), '5');
    await user.selectOptions(within(dialog).getByLabelText('Ca làm việc'), '3');
    await user.click(within(dialog).getByRole('button', { name: 'Phân ca' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Nhân viên đã có phân ca trùng hoặc chồng giờ: Ca chiều');
  });

  it('chi nhánh chưa có nhân viên: báo rỗng và khóa nút Phân ca', async () => {
    const user = await renderPage('manager2@store2.vn');
    await openTab(user, 'Phân ca');
    expect(await screen.findByText('Chi nhánh chưa có nhân viên để phân ca.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Phân ca' })).toBeDisabled();
  });
});

describe('tab Yêu cầu nghỉ / đổi ca (#12–14)', () => {
  it('mặc định là chờ duyệt, cũ nhất trước; có nút Duyệt và Từ chối', async () => {
    const user = await renderPage();
    await openTab(user, 'Yêu cầu nghỉ / đổi ca');
    const rows = await bodyRows('Yêu cầu nghỉ hoặc đổi ca');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('BS. Minh Anh');
    expect(rows[0]).toHaveTextContent('Xin nghỉ Ca chiều');
    expect(rows[1]).toHaveTextContent('Thu Hà');
    expect(rows[1]).toHaveTextContent('Đổi Ca sáng sang Ca chiều');
    expect(within(rows[0]).getByRole('button', { name: 'Duyệt: BS. Minh Anh, 2026-11-09' })).toBeInTheDocument();
    expect(within(rows[0]).getByRole('button', { name: 'Từ chối: BS. Minh Anh, 2026-11-09' })).toBeInTheDocument();
  });

  it('duyệt một yêu cầu: dòng rời tab chờ duyệt và sang tab Đã duyệt', async () => {
    const user = await renderPage();
    await openTab(user, 'Yêu cầu nghỉ / đổi ca');
    await user.click(await screen.findByRole('button', { name: 'Duyệt: BS. Minh Anh, 2026-11-09' }));
    expect(await screen.findByText(/^Đã duyệt: BS\. Minh Anh/)).toBeInTheDocument();
    await waitFor(async () => expect(await bodyRows('Yêu cầu nghỉ hoặc đổi ca')).toHaveLength(1));
    await openTab(user, 'Đã duyệt');
    await waitFor(async () => expect(await bodyRows('Yêu cầu nghỉ hoặc đổi ca')).toHaveLength(2));
  });

  it('từ chối: thiếu lý do thì báo ngay, có lý do thì ghi nhận', async () => {
    const user = await renderPage();
    await openTab(user, 'Yêu cầu nghỉ / đổi ca');
    await user.click(await screen.findByRole('button', { name: 'Từ chối: Thu Hà, 2026-11-10' }));
    const dialog = await screen.findByRole('dialog', { name: 'Từ chối yêu cầu' });
    await user.click(within(dialog).getByRole('button', { name: 'Từ chối' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Nhập lý do từ chối.');
    await user.type(within(dialog).getByLabelText('Lý do từ chối'), 'Ca chiều đã đủ người');
    await user.click(within(dialog).getByRole('button', { name: 'Từ chối' }));
    expect(await screen.findByText('Đã từ chối yêu cầu của Thu Hà.')).toBeInTheDocument();
    await openTab(user, 'Từ chối');
    await waitFor(async () => expect((await bodyRows('Yêu cầu nghỉ hoặc đổi ca')).some((r) => r.textContent?.includes('Ca chiều đã đủ người'))).toBe(true));
  });

  it('người khác đã duyệt trước (409): hiện lỗi và danh sách tự tải lại', async () => {
    const user = await renderPage();
    await openTab(user, 'Yêu cầu nghỉ / đổi ca');
    const button = await screen.findByRole('button', { name: 'Duyệt: BS. Minh Anh, 2026-11-09' });
    await workforceApi.approveShiftRequest(1);
    await user.click(button);
    expect(await screen.findByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái');
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Duyệt: BS. Minh Anh, 2026-11-09' })).not.toBeInTheDocument());
  });
});

// ─── Tab Thống kê tuần (#16) ───
// Tuần kế tiếp mặc định là 09/11–15/11/2026. Nhân viên đang hoạt động của chi nhánh 1: Lan Chi (lễ tân), BS. Minh Anh, BS. Ngọc Lan, BS. Quốc Bảo, BS. Thanh Thảo (bác sĩ), Thu Hà (chăm sóc).
// BS. Minh Anh đã có 6 ca do quản lý xếp trong tuần này; BS. Ngọc Lan chưa có ca nào.

const summaryRows = () => bodyRows('Thống kê số ca theo tuần');
const rowOf = async (name: string) => (await summaryRows()).find((r) => within(r).getAllByRole('cell')[0].textContent === name)!;
const cellsOf = (row: HTMLElement) => within(row).getAllByRole('cell').map((c) => c.textContent);
/** Phân ca của quản lý cho một nhân viên: Ca sáng (#1) và Ca chiều (#2) trong `days` ngày liền từ 09/11. */
async function assignShifts(staffId: number, count: number) {
  const slots = ['2026-11-09', '2026-11-10', '2026-11-11', '2026-11-12', '2026-11-13'].flatMap((workDate) => [1, 2].map((shiftDefinitionId) => ({ workDate, shiftDefinitionId })));
  for (const slot of slots.slice(0, count)) await workforceApi.createShiftAssignment({ staffId, ...slot });
}

describe('tab Thống kê tuần (#16)', () => {
  it('mặc định là tuần kế tiếp: mỗi nhân viên một dòng (kể cả chưa có ca), sắp theo tên, kèm vai trò, số ca, định mức và kết quả bằng chữ', async () => {
    const user = await renderPage();
    await openTab(user, 'Thống kê tuần');
    const rows = await summaryRows();
    expect(screen.getByText('Tuần 09/11/2026 – 15/11/2026')).toBeInTheDocument();
    const names = rows.map((r) => within(r).getAllByRole('cell')[0].textContent);
    expect(names).toEqual(expect.arrayContaining(['Lan Chi', 'BS. Minh Anh', 'BS. Ngọc Lan', 'BS. Quốc Bảo', 'BS. Thanh Thảo', 'Thu Hà']));
    expect(names).toEqual([...names].sort((a, b) => (a ?? '').localeCompare(b ?? '')));
    expect(cellsOf(await rowOf('BS. Minh Anh'))).toEqual(['BS. Minh Anh', 'Bác sĩ thú y', '6', '10', 'Chưa đạt']);
    expect(cellsOf(await rowOf('BS. Ngọc Lan'))).toEqual(['BS. Ngọc Lan', 'Bác sĩ thú y', '0', '10', 'Chưa đạt']);
    expect(cellsOf(await rowOf('Lan Chi'))[1]).toBe('Lễ tân');
    expect(cellsOf(await rowOf('Thu Hà'))[1]).toBe('Nhân viên chăm sóc');
    expect(screen.getByText(new RegExp(`^0 / ${rows.length} nhân viên đạt định mức`))).toBeInTheDocument();
  });

  it('đủ định mức thì hiện Đạt, thiếu một ca vẫn Chưa đạt (biên 9 / 10)', async () => {
    const user = await renderPage();
    await assignShifts(22, 10); // BS. Ngọc Lan: 10 ca
    await assignShifts(23, 9); // BS. Thanh Thảo: 9 ca
    await openTab(user, 'Thống kê tuần');
    expect(cellsOf(await rowOf('BS. Ngọc Lan'))).toEqual(['BS. Ngọc Lan', 'Bác sĩ thú y', '10', '10', 'Đạt']);
    expect(cellsOf(await rowOf('BS. Thanh Thảo'))).toEqual(['BS. Thanh Thảo', 'Bác sĩ thú y', '9', '10', 'Chưa đạt']);
    expect(screen.getByText(/^1 \/ \d+ nhân viên đạt định mức/)).toBeInTheDocument();
  });

  it('chọn tuần: Tuần này đếm lại theo lịch của chi nhánh; một ngày bất kỳ quy về thứ Hai', async () => {
    const user = await renderPage();
    await openTab(user, 'Thống kê tuần');
    await summaryRows();
    await user.click(screen.getByRole('button', { name: 'Tuần này' }));
    expect(await screen.findByText('Tuần 02/11/2026 – 08/11/2026')).toBeInTheDocument();
    const expected = (await workforceApi.listShiftAssignments({ from: '2026-11-02', to: '2026-11-08', staffId: 5, status: 'SCHEDULED', size: 100 })).totalElements;
    expect(expected).toBeGreaterThan(0);
    await waitFor(async () => expect(cellsOf(await rowOf('BS. Minh Anh'))[2]).toBe(String(expected)));
    fireEvent.change(screen.getByLabelText('Chọn ngày trong tuần'), { target: { value: '2026-11-26' } });
    expect(await screen.findByText('Tuần 23/11/2026 – 29/11/2026')).toBeInTheDocument();
    expect(screen.getByLabelText('Chọn ngày trong tuần')).toHaveValue('2026-11-23');
    await user.click(screen.getByRole('button', { name: 'Tuần trước' }));
    expect(await screen.findByText('Tuần 16/11/2026 – 22/11/2026')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Tuần sau' }));
    expect(await screen.findByText('Tuần 23/11/2026 – 29/11/2026')).toBeInTheDocument();
  });

  it('tuần ngoài năm 2000–2100: lỗi BR-NS-11 của máy chủ hiện nguyên văn', async () => {
    const user = await renderPage();
    await openTab(user, 'Thống kê tuần');
    await summaryRows();
    fireEvent.change(screen.getByLabelText('Chọn ngày trong tuần'), { target: { value: '1999-12-29' } });
    expect(await screen.findByText('Không tải được thống kê số ca')).toBeInTheDocument();
    expect(screen.getByText(/Chỉ hỗ trợ tuần từ năm 2000 đến năm 2100/)).toBeInTheDocument();
  });

  it('chi nhánh chưa có nhân viên: báo rỗng, không lỗi', async () => {
    const user = await renderPage('manager2@store2.vn');
    await openTab(user, 'Thống kê tuần');
    expect(await screen.findByText('Chưa có nhân viên để thống kê')).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: 'Thống kê số ca theo tuần' })).not.toBeInTheDocument();
  });
});
