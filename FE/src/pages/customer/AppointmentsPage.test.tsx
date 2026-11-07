import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { appointmentApi } from '../../shared/api/appointment.api';
import { apiClient } from '../../shared/api/axios';
import { homevisitApi } from '../../shared/api/homevisit.api';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AppointmentsPage } from './AppointmentsPage';

// Giờ cố định thứ Sáu 23/10/2026 09:00 (giờ VN). Lịch BOOKED của khach@petcare.vn theo giờ hẹn:
// Mochi hôm nay 11:00 (còn 2 giờ: hủy được nhưng là hủy muộn, không đổi giờ online), Bim mai 10:00 (đã đổi 3 lần),
// Mochi thứ Hai 26/10 09:00 (đổi / hủy được). Đã qua: hoàn tất, Bim hủy muộn, lịch phòng khám hủy do ngày nghỉ.

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-10-23T09:00:00+07:00'));
  useSession.getState().clear();
  await useSession.getState().login({ email: 'khach@petcare.vn', password: MOCK_PASSWORD });
});
afterEach(() => vi.useRealTimers());

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/appointments']}>
        <AppointmentsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const cards = async () => screen.findAllByRole('article');

describe('AppointmentsPage (UC40)', () => {
  it('nút đổi giờ / hủy theo cờ canReschedule / canCancel của BE (BR-LH-06, 07)', async () => {
    renderPage();
    const [today, tomorrow, monday] = await cards();
    expect(within(today).getByText('Mochi')).toBeInTheDocument();
    expect(within(today).queryByRole('button', { name: /Đổi giờ/ })).not.toBeInTheDocument();
    expect(within(today).getByRole('button', { name: /Hủy lịch/ })).toBeInTheDocument();
    expect(within(today).getByText(/còn dưới 12 giờ trước giờ hẹn/)).toBeInTheDocument();

    expect(within(tomorrow).getByText('Bim')).toBeInTheDocument();
    expect(within(tomorrow).queryByRole('button', { name: /Đổi giờ/ })).not.toBeInTheDocument();
    expect(within(tomorrow).getByText(/đã đổi đủ 3 lần/)).toBeInTheDocument();

    expect(within(monday).getByRole('button', { name: /Đổi giờ/ })).toBeInTheDocument();
    expect(within(monday).getByRole('button', { name: /Hủy lịch/ })).toBeInTheDocument();
  });

  it('hủy khi còn dưới 12 giờ: cảnh báo hủy muộn, lịch chuyển sang "Đã qua / đã hủy" kèm nguồn hủy', async () => {
    const user = renderPage();
    const [today] = await cards();
    await user.click(within(today).getByRole('button', { name: /Hủy lịch/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Hủy lịch hẹn' });
    expect(within(dialog).getByRole('alert')).toHaveTextContent('hủy lúc này được tính là hủy muộn');
    await user.type(within(dialog).getByLabelText('Lý do hủy (không bắt buộc)'), 'Kẹt xe');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }));

    expect(await cards()).toHaveLength(2);
    await user.click(screen.getByRole('tab', { name: /Đã qua/ }));
    const cancelled = (await cards()).find((c) => within(c).queryByText(/Kẹt xe/))!;
    expect(cancelled).toHaveTextContent('Khách hủy');
    expect(cancelled).toHaveTextContent('Hủy muộn');
    expect(screen.getAllByText('Phòng khám hủy').length).toBeGreaterThan(0);
  });

  it('đổi giờ trong cùng chi nhánh, dịch vụ: giữ mã, tăng số lần đổi', async () => {
    const user = renderPage();
    const monday = (await cards())[2];
    const code = within(monday).getByText(/^LH-/).textContent;
    await user.click(within(monday).getByRole('button', { name: /Đổi giờ/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Đổi giờ hẹn' });
    expect(dialog).toHaveTextContent('hãy hủy lịch này rồi đặt lại');
    const slots = await within(dialog).findByRole('group', { name: 'Khung giờ' });
    expect(within(slots).getByRole('button', { name: /^09:00/ })).toBeDisabled();
    await user.click(within(slots).getByRole('button', { name: /^09:30/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận đổi giờ' }));

    const moved = (await screen.findByText(/đã đổi giờ 1\/3 lần/)).closest('article')!;
    expect(moved).toHaveTextContent('09:30–10:00');
    expect(within(moved).getByText(code!)).toBeInTheDocument();
  });
});

// Lịch khám tại nhà của khach@petcare.vn (homevisit.mock nạp lười lúc khối này gọi lần đầu, tính theo hôm nay 23/10): Bim 25/10 09:30 đã đặt (đổi / hủy được),
// Tôm hôm nay 16:30 đã đặt (còn 7,5 giờ: hủy được, không đổi giờ online), 14/10 đã khám, 17/10 đã hủy. Không lẫn vào danh sách lịch hẹn thường (các thẻ <article> ở trên).
describe('AppointmentsPage — lịch khám tại nhà (UC43)', () => {
  const homeCards = async () => (await screen.findAllByRole('group', { name: /^Lịch khám tại nhà LH-/ }));
  const homeCard = async (pet: string) => (await homeCards()).find((c) => within(c).queryByRole('heading') && c.textContent!.includes(pet))!;

  it('khối riêng liệt kê lịch tại nhà mới nhất trước: trạng thái, địa chỉ, phí; danh sách lịch hẹn thường không đổi', async () => {
    renderPage();
    const groups = await homeCards();
    expect(groups).toHaveLength(4);
    expect(groups.map((g) => g.textContent!.match(/Đã đặt|Đã khám|Đã hủy/)![0])).toEqual(['Đã đặt', 'Đã đặt', 'Đã hủy', 'Đã khám']);
    const bim = await homeCard('Bim');
    expect(bim).toHaveTextContent('45 Láng Hạ');
    expect(bim).toHaveTextContent('Phí khám tại nhà 100.000');
    expect(screen.getByRole('heading', { level: 2, name: 'Lịch khám tại nhà' })).toBeInTheDocument();
    expect(await cards()).toHaveLength(3); // 3 lịch BOOKED thường
  });

  it('nút theo cờ của lịch hẹn: Bim đổi giờ và hủy được, Tôm (còn dưới 24 giờ) chỉ hủy được và được hướng dẫn liên hệ chi nhánh', async () => {
    renderPage();
    const bim = await homeCard('Bim');
    expect(await within(bim).findByRole('button', { name: /Đổi giờ/ })).toBeInTheDocument();
    expect(within(bim).getByRole('button', { name: /Hủy lịch/ })).toBeInTheDocument();
    const tom = await homeCard('Tôm');
    expect(await within(tom).findByRole('button', { name: /Hủy lịch/ })).toBeInTheDocument();
    expect(within(tom).queryByRole('button', { name: /Đổi giờ/ })).not.toBeInTheDocument();
    expect(within(tom).getByText(/Không đổi giờ online được nữa/)).toBeInTheDocument();
    // Lịch đã khám / đã hủy không có nút.
    const done = (await homeCards()).find((g) => g.textContent!.includes('Đã khám'))!;
    expect(within(done).queryByRole('button')).not.toBeInTheDocument();
  });

  it('đổi giờ lịch tại nhà dùng appointment-v1: chọn khung mới, thẻ hiện giờ mới sau khi hộp thoại đóng', async () => {
    const user = renderPage();
    const bim = await homeCard('Bim');
    await user.click(await within(bim).findByRole('button', { name: /Đổi giờ/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Đổi giờ hẹn' });
    await user.click(within(dialog).getByRole('button', { name: 'Ngày hẹn' }));
    await user.click(within(dialog).getByRole('button', { name: '26/10/2026' }));
    const slots = await within(dialog).findByRole('group', { name: 'Khung giờ' });
    await user.click(within(slots).getByRole('button', { name: /^10:00/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận đổi giờ' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(async () => expect(await homeCard('Bim')).toHaveTextContent('10:00–10:30'));
    expect(await homeCard('Bim')).toHaveTextContent('Thứ Hai, 26/10/2026');
  });

  it('hủy lịch tại nhà: Tôm hôm nay hủy muộn, thẻ chuyển Đã hủy; lịch đã bị hủy ở nơi khác thì BE trả 409 và hộp thoại báo tải lại', async () => {
    const user = renderPage();
    const tom = await homeCard('Tôm');
    await user.click(await within(tom).findByRole('button', { name: /Hủy lịch/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Hủy lịch hẹn' });
    const id = (await homevisitApi.listMine()).content.find((v) => v.petName === 'Tôm' && v.status === 'BOOKED')!.appointmentId;
    await appointmentApi.cancel(id, {}); // máy khác hủy trước
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }));
    expect(await within(dialog).findByText('Lịch hẹn đã được tiếp nhận hoặc thay đổi, vui lòng tải lại.')).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Giữ lịch' }));
    await waitFor(async () => expect(await homeCard('Tôm')).toHaveTextContent('Đã hủy'));
    expect(within(await homeCard('Tôm')).queryByRole('button', { name: /Hủy lịch/ })).not.toBeInTheDocument();
  });
});
