import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

/** `beforeRender` chạy sau khi đăng nhập, trước khi dựng trang (dựng sẵn dữ liệu mà trang sẽ tải lần đầu). */
async function renderPage(email = 'doctor@store1.vn', beforeRender?: () => Promise<unknown>) {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
  await beforeRender?.();
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
    expect(screen.getByText(/Khoảng ngày tối đa 62 ngày/)).toBeInTheDocument();
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

  it('đổi ca: ca đích lấy từ các ca đang dùng của chi nhánh (#1), trừ ca hiện tại', async () => {
    const user = await renderPage();
    await user.click(await screen.findByRole('button', { name: 'Xin nghỉ hoặc đổi ca: Ca sáng, 2026-11-11' }));
    const dialog = await screen.findByRole('dialog', { name: 'Xin nghỉ / đổi ca' });
    await user.click(within(dialog).getByLabelText('Đổi ca'));
    const target = within(dialog).getByLabelText('Đổi sang ca');
    expect(within(target).getAllByRole('option').map((o) => o.textContent)).toEqual(['Chọn ca', 'Ca hành chính (08:00–17:00)', 'Ca chiều (13:00–17:30)', 'Ca tối (17:30–21:00)']);
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

// ─── Đăng ký ca theo tuần (#15, #17) ───
// Tuần kế tiếp mặc định là 09/11–15/11/2026. BS. Minh Anh (doctor) đã có 6 ca do quản lý xếp trong tuần này: 09/11 chiều, 11/11 sáng, 12/11 chiều, 13/11 sáng, 14/11 sáng, 15/11 chiều.
// BS. Ngọc Lan (doctor3) chưa có ca nào. Ca đang dùng của chi nhánh 1 (#1): Ca sáng 07:30–12:00, Ca hành chính 08:00–17:00, Ca chiều 13:00–17:30, Ca tối 17:30–21:00.

const registrationTable = () => screen.findByRole('table', { name: 'Đăng ký ca theo tuần' });
/** Ô (ca, ngày) của lưới; aria-label là `Ca sáng, 2026-11-09` (thêm `, quản lý xếp` khi do quản lý xếp). */
const cell = (shift: string, date: string) => screen.findByRole('checkbox', { name: new RegExp(`^${shift}, ${date}(,|$)`) });
const MON_FRI = ['2026-11-09', '2026-11-10', '2026-11-11', '2026-11-12', '2026-11-13'];

async function tick(user: Awaited<ReturnType<typeof renderPage>>, cells: [shift: string, date: string][]) {
  for (const [shift, date] of cells) await user.click(await cell(shift, date));
}
const tenCells = MON_FRI.flatMap((d): [string, string][] => [['Ca sáng', d], ['Ca chiều', d]]);

describe('MySchedulePage: đăng ký ca theo tuần (#17)', () => {
  it('mặc định là tuần kế tiếp: khoảng ngày, hạn đăng ký, lưới 7 ngày × các ca đang dùng, đếm ca', async () => {
    await renderPage('doctor3@store1.vn');
    const table = await registrationTable();
    expect(screen.getByRole('heading', { level: 2, name: 'Đăng ký ca theo tuần' })).toBeInTheDocument();
    expect(screen.getByText('Tuần 09/11/2026 – 15/11/2026')).toBeInTheDocument();
    expect(screen.getByText(/Hạn đăng ký: 00:00 09\/11\/2026/)).toBeInTheDocument();
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Ca', 'Thứ Hai09/11', 'Thứ Ba10/11', 'Thứ Tư11/11', 'Thứ Năm12/11', 'Thứ Sáu13/11', 'Thứ Bảy14/11', 'Chủ nhật15/11',
    ]);
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.map((r) => within(r).getAllByRole('rowheader')[0].textContent)).toEqual([
      'Ca sáng07:30–12:00', 'Ca hành chính08:00–17:00', 'Ca chiều13:00–17:30', 'Ca tối17:30–21:00',
    ]);
    expect(within(table).getAllByRole('checkbox')).toHaveLength(28);
    expect(screen.getByText('0 / 10 ca')).toBeInTheDocument();
    expect(screen.getByText(/Còn thiếu 10 ca/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Lưu đăng ký' })).toBeEnabled();
  });

  it('ô do quản lý xếp: tích sẵn, khóa, kèm nhãn "Quản lý xếp", và được tính vào số ca', async () => {
    await renderPage();
    const boxed = await cell('Ca chiều', '2026-11-09');
    expect(boxed).toBeChecked();
    expect(boxed).toBeDisabled();
    expect(boxed).toHaveAccessibleName('Ca chiều, 2026-11-09, quản lý xếp');
    expect(within(boxed.closest('td')!).getByText('Quản lý xếp')).toBeInTheDocument();
    const free = await cell('Ca tối', '2026-11-09');
    expect(free).not.toBeChecked();
    expect(free).toBeEnabled();
    expect(screen.getByText('6 / 10 ca')).toBeInTheDocument();
    expect(screen.getByText(/6 ca do quản lý xếp/)).toBeInTheDocument();
    expect(screen.getByText(/Còn thiếu 4 ca/)).toBeInTheDocument();
    expect(screen.getAllByText('Quản lý xếp')).toHaveLength(6);
  });

  it('chọn tuần: Tuần sau / Tuần trước, và một ngày bất kỳ quy về thứ Hai của tuần đó', async () => {
    const user = await renderPage('doctor3@store1.vn');
    await registrationTable();
    await user.click(screen.getByRole('button', { name: 'Tuần sau' }));
    expect(await screen.findByText('Tuần 16/11/2026 – 22/11/2026')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Tuần trước' }));
    expect(await screen.findByText('Tuần 09/11/2026 – 15/11/2026')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Chọn ngày trong tuần'), { target: { value: '2026-11-26' } }); // thứ Năm
    expect(await screen.findByText('Tuần 23/11/2026 – 29/11/2026')).toBeInTheDocument();
    expect(screen.getByLabelText('Chọn ngày trong tuần')).toHaveValue('2026-11-23');
    expect(await cell('Ca sáng', '2026-11-23')).toBeEnabled();
    await user.click(screen.getByRole('button', { name: 'Tuần này' }));
    expect(await screen.findByText('Tuần 02/11/2026 – 08/11/2026')).toBeInTheDocument();
  });

  it('tuần đã quá hạn (tuần này, 02/11): xem được ca đã có, lưới khóa, nút Lưu vô hiệu kèm giải thích', async () => {
    const user = await renderPage();
    await registrationTable();
    await user.click(screen.getByRole('button', { name: 'Tuần này' }));
    expect(await screen.findByText(/Đã quá hạn đăng ký \(hạn là 00:00 02\/11\/2026\)/)).toBeInTheDocument();
    expect(screen.getByText(/chỉ còn xin nghỉ hoặc đổi ca/i)).toBeInTheDocument();
    const table = await registrationTable();
    const boxes = within(table).getAllByRole('checkbox');
    expect(boxes.every((b) => (b as HTMLInputElement).disabled)).toBe(true);
    expect(boxes.some((b) => (b as HTMLInputElement).checked)).toBe(true);
    expect(screen.getByRole('button', { name: 'Lưu đăng ký' })).toBeDisabled();
  });
});

describe('MySchedulePage: lưu đăng ký ca theo tuần (#15)', () => {
  it('chọn đủ 10 ca rồi lưu: báo thành công, ô giữ nguyên, máy chủ ghi đúng 10 ca tự đăng ký', async () => {
    const user = await renderPage('doctor3@store1.vn');
    await registrationTable();
    await tick(user, tenCells);
    expect(screen.getByText('10 / 10 ca')).toBeInTheDocument();
    expect(screen.queryByText(/Còn thiếu/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Lưu đăng ký' }));
    expect(await screen.findByText('Đã lưu đăng ký tuần 09/11/2026 – 15/11/2026: 10 / 10 ca.')).toBeInTheDocument();
    const saved = await workforceApi.getMyShiftRegistration('2026-11-09');
    expect(saved.shiftCount).toBe(10);
    expect(saved.assignments.every((a) => a.source === 'SELF')).toBe(true);
    expect(await cell('Ca sáng', '2026-11-09')).toBeChecked();
    expect(screen.getByText('10 / 10 ca')).toBeInTheDocument();
  });

  it('đã đăng ký sẵn: ô hiện tích đúng; bỏ một ô, thêm một ô rồi lưu thì máy chủ đổi theo', async () => {
    const items = tenCells.map(([shift, d]) => ({ workDate: d, shiftDefinitionId: shift === 'Ca sáng' ? 1 : 2 }));
    const user = await renderPage('doctor3@store1.vn', () => workforceApi.putMyShiftRegistration('2026-11-09', { items }));
    await registrationTable();
    expect(await screen.findByText('10 / 10 ca')).toBeInTheDocument();
    expect(await cell('Ca sáng', '2026-11-09')).toBeChecked();
    expect(await cell('Ca tối', '2026-11-09')).not.toBeChecked();
    await user.click(await cell('Ca sáng', '2026-11-09'));
    await user.click(await cell('Ca sáng', '2026-11-14'));
    await user.click(screen.getByRole('button', { name: 'Lưu đăng ký' }));
    expect(await screen.findByText(/^Đã lưu đăng ký tuần/)).toBeInTheDocument();
    const after = await workforceApi.getMyShiftRegistration('2026-11-09');
    expect(after.shiftCount).toBe(10);
    expect(after.assignments.some((a) => a.workDate === '2026-11-09' && a.shiftDefinitionId === 1)).toBe(false);
    expect(after.assignments.some((a) => a.workDate === '2026-11-14' && a.shiftDefinitionId === 1)).toBe(true);
  });

  it('ca do quản lý xếp không bị gửi đi: 6 ca của quản lý + 4 ca tự chọn = đủ 10 và ca của quản lý vẫn nguyên', async () => {
    const user = await renderPage();
    await registrationTable();
    const evenings = ['2026-11-09', '2026-11-10', '2026-11-11', '2026-11-12'];
    await tick(user, evenings.map((d): [string, string] => ['Ca tối', d]));
    expect(screen.getByText('10 / 10 ca')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Lưu đăng ký' }));
    expect(await screen.findByText('Đã lưu đăng ký tuần 09/11/2026 – 15/11/2026: 10 / 10 ca.')).toBeInTheDocument();
    const saved = await workforceApi.getMyShiftRegistration('2026-11-09');
    expect(saved.assignments.filter((a) => a.source === 'MANAGER')).toHaveLength(6);
    expect(saved.assignments.filter((a) => a.source === 'SELF')).toHaveLength(4);
    expect(await cell('Ca chiều', '2026-11-09')).toBeDisabled();
  });

  it('thiếu định mức: cảnh báo bằng chữ trước khi lưu; vẫn gửi được, lỗi BR-NS-12 hiện nguyên văn và các ô đã chọn GIỮ NGUYÊN sau khi tải lại', async () => {
    const user = await renderPage('doctor3@store1.vn');
    await registrationTable();
    await tick(user, [['Ca sáng', '2026-11-09'], ['Ca sáng', '2026-11-10'], ['Ca sáng', '2026-11-11']]);
    expect(screen.getByText('3 / 10 ca')).toBeInTheDocument();
    expect(screen.getByText(/Còn thiếu 7 ca/)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    const reload = vi.spyOn(workforceApi, 'getMyShiftRegistration');
    const calls = reload.mock.calls.length;
    await user.click(screen.getByRole('button', { name: 'Lưu đăng ký' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Tuần này có 3 ca (gồm 0 ca do quản lý xếp), cần tối thiểu 10 ca, còn thiếu 7');
    expect(screen.getByRole('alert')).not.toHaveTextContent('BR-NS-12');
    // Lệnh ghi lỗi vẫn làm mới cả khóa ['workforce']: chờ #17 tải lại xong rồi mới kiểm các ô.
    await waitFor(() => expect(reload.mock.calls.length).toBeGreaterThan(calls));
    await act(async () => {
      await reload.mock.results.at(-1)!.value;
    });
    expect(await cell('Ca sáng', '2026-11-09')).toBeChecked();
    expect(await cell('Ca sáng', '2026-11-11')).toBeChecked();
    expect(screen.getByText('3 / 10 ca')).toBeInTheDocument();
    expect((await workforceApi.getMyShiftRegistration('2026-11-09')).shiftCount).toBe(0);
    reload.mockRestore();
  });

  it('lỗi theo mục của máy chủ (chồng giờ giữa hai ca) hiện nguyên văn, không lưu gì', async () => {
    const user = await renderPage('doctor3@store1.vn');
    await registrationTable();
    await tick(user, [...tenCells, ['Ca hành chính', '2026-11-09']]);
    await user.click(screen.getByRole('button', { name: 'Lưu đăng ký' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/^Đăng ký tuần không hợp lệ: Mục 1 và mục 2 chồng giờ ngày 09\/11\/2026: Ca sáng \(07:30–12:00\) \/ Ca hành chính \(08:00–17:00\)/);
    expect((await workforceApi.getMyShiftRegistration('2026-11-09')).shiftCount).toBe(0);
  });

  it('đăng ký đổi ở nơi khác khi trang đang mở: tải lại thì lưới theo máy chủ', async () => {
    await renderPage('doctor3@store1.vn');
    await registrationTable();
    expect(await screen.findByText('0 / 10 ca')).toBeInTheDocument();
    await workforceApi.putMyShiftRegistration('2026-11-09', { items: tenCells.map(([shift, d]) => ({ workDate: d, shiftDefinitionId: shift === 'Ca sáng' ? 1 : 2 })) });
    window.dispatchEvent(new Event('visibilitychange')); // react-query tải lại khi cửa sổ được chú ý trở lại
    expect(await screen.findByText('10 / 10 ca')).toBeInTheDocument();
    expect(await cell('Ca sáng', '2026-11-09')).toBeChecked();
  });

  it('đổi tuần: các ô đang chọn của tuần cũ không rơi sang tuần mới', async () => {
    const user = await renderPage('doctor3@store1.vn');
    await registrationTable();
    await tick(user, [['Ca sáng', '2026-11-09']]);
    await user.click(screen.getByRole('button', { name: 'Tuần sau' }));
    expect(await screen.findByText('Tuần 16/11/2026 – 22/11/2026')).toBeInTheDocument();
    expect(await screen.findByText('0 / 10 ca')).toBeInTheDocument();
    expect(within(await registrationTable()).getAllByRole('checkbox').some((b) => (b as HTMLInputElement).checked)).toBe(false);
  });
});
