import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import axios from 'axios';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { catalogApi } from '../../shared/api/catalog.api';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { pinClock } from '../../test/boarding-harness';
import { apiFailure } from '../customer/boarding/testing';
import { HotelPage } from './HotelPage';

// Trang công khai: khách chưa đăng nhập. Giờ ghim: thứ Sáu 23/10/2026 09:00 (giờ VN). Dữ liệu mẫu: chi nhánh 1 (PetCare Cầu Giấy) là chi nhánh ACTIVE duy nhất,
// loại chuồng #5 "Chuồng chó nhỏ" 200.000 đ/đêm (chó ≤ 10 kg) bật ở đó; loại #6 "Chuồng mèo" 180.000 đ/đêm (mèo ≤ 8 kg) chưa bật ở chi nhánh nào.

const raw = axios.create();
beforeAll(() => {
  installMockApi(apiClient);
  installMockApi(raw);
});
beforeEach(() => {
  pinClock();
  useSession.getState().clear();
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

const staff = async (email: string) => ({
  headers: { Authorization: `Bearer ${(await raw.post('/api/auth/login', { email, password: MOCK_PASSWORD })).data.data.accessToken}` },
});

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/hotel']}>
        <HotelPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}
type User = ReturnType<typeof userEvent.setup>;

const kennelCard = (name: string) => screen.findByRole('article', { name });
const calculator = () => screen.getByRole('region', { name: 'Tính tiền lưu trú' });

async function pickDate(user: User, field: 'Ngày nhận' | 'Ngày trả', day: string, months = 0) {
  await user.click(within(calculator()).getByRole('button', { name: field }));
  for (let i = 0; i < months; i++) await user.click(screen.getByRole('button', { name: 'Tháng sau' }));
  await user.click(screen.getByRole('button', { name: day }));
}

describe('HotelPage (trang công khai /hotel)', () => {
  it('một <h1>, nút đặt chỗ ở đầu trang dẫn tới /boarding-bookings/new', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { level: 1, name: /Nơi nghỉ ngơi tuyệt vời/ })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    const links = screen.getAllByRole('link', { name: 'Đặt chỗ lưu trú' });
    expect(links.length).toBeGreaterThanOrEqual(2);
    for (const link of links) expect(link).toHaveAttribute('href', '/boarding-bookings/new');
  });

  it('loại chuồng, giá và chi nhánh lấy từ dữ liệu công khai: chỉ loại đang bật, kèm loài, cân nặng tối đa, giá đêm và nơi có', async () => {
    renderPage();
    const small = await kennelCard('Chuồng chó nhỏ');
    expect(within(small).getByText('Chó, tối đa 10 kg')).toBeInTheDocument();
    expect(within(small).getByText(/200\.000/)).toHaveTextContent('/đêm');
    expect(within(small).getByText('Có tại PetCare Cầu Giấy')).toBeInTheDocument();
    expect(within(small).getByRole('img', { name: 'Chuồng chó nhỏ' })).toBeInTheDocument();
    // Loại chuồng mèo chưa bật ở chi nhánh nào: không được quảng cáo.
    expect(screen.queryByText('Chuồng mèo')).not.toBeInTheDocument();
    expect(screen.getAllByRole('article')).toHaveLength(1);
  });

  it('bật loại chuồng mèo ở chi nhánh 1 thì trang hiện thêm "Chuồng mèo" với giá của nó', async () => {
    await raw.put('/api/branches/1/services/6', { enabled: true }, await staff('manager@store1.vn'));
    renderPage();
    const cat = await kennelCard('Chuồng mèo');
    expect(within(cat).getByText('Mèo, tối đa 8 kg')).toBeInTheDocument();
    expect(within(cat).getByText(/180\.000/)).toBeInTheDocument();
  });

  it('đổi giá loại chuồng ở danh mục thì trang hiện giá mới, không còn giá cũ', async () => {
    await raw.patch('/api/services/5', { price: 260_000 }, await staff('chuoi@petcare.vn'));
    renderPage();
    const small = await kennelCard('Chuồng chó nhỏ');
    expect(within(small).getByText(/260\.000/)).toBeInTheDocument();
    expect(screen.queryByText(/200\.000/)).not.toBeInTheDocument();
  });

  it('không còn số liệu cứng hay lời hứa mà hệ thống không có', async () => {
    renderPage();
    await kennelCard('Chuồng chó nhỏ');
    const text = document.body.textContent ?? '';
    for (const gone of ['150.000', '350.000', '490.000', '3.200.000', '5.900.000', '9.500.000', 'Phòng Standard', 'Deluxe', 'Camera 24/7', 'Wifi', 'Spa miễn phí', 'Bãi đỗ xe', 'Dắt đi dạo']) {
      expect(text).not.toContain(gone);
    }
    expect(screen.queryByRole('button', { name: /Tìm phòng/ })).not.toBeInTheDocument();
  });

  it('trang công khai chỉ gọi API công khai: không gọi boarding-availability (cần đăng nhập) và không gặp 401', async () => {
    const get = vi.spyOn(apiClient, 'get');
    const services = vi.spyOn(catalogApi, 'listPublicServices');
    const user = renderPage();
    await kennelCard('Chuồng chó nhỏ');
    await pickDate(user, 'Ngày nhận', '24/10/2026');
    await pickDate(user, 'Ngày trả', '26/10/2026');
    await within(calculator()).findByText(/2 đêm/);
    const urls = get.mock.calls.map(([url]) => String(url));
    expect(urls.length).toBeGreaterThan(0);
    expect(urls.some((url) => url.includes('boarding-availability'))).toBe(false);
    expect(urls.every((url) => url.startsWith('/api/public/'))).toBe(true);
    // Chỉ xin nhóm lưu trú, không kéo cả danh mục dịch vụ về.
    expect(services).toHaveBeenCalledWith({ group: 'BOARDING' });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('chi nhánh nhận lưu trú: tên, địa chỉ, số điện thoại lấy từ chi nhánh công khai', async () => {
    renderPage();
    const section = await screen.findByRole('region', { name: 'Chi nhánh nhận lưu trú' });
    expect(await within(section).findByText('PetCare Cầu Giấy')).toBeInTheDocument();
    expect(within(section).getByText(/Cầu Giấy, Hà Nội/)).toBeInTheDocument();
    expect(within(section).getByRole('link', { name: 'Xem tất cả chi nhánh' })).toHaveAttribute('href', '/branches');
  });

  it('đang tải: khung chờ; lỗi: nêu câu của BE và Thử lại; không có dịch vụ nào: nói rõ', async () => {
    const spy = vi.spyOn(catalogApi, 'listPublicServices').mockRejectedValueOnce(apiFailure(403, 'ACCESS_DENIED', 'Không có quyền thực hiện thao tác này'));
    const user = renderPage();
    expect(screen.getByRole('status', { name: 'Đang tải loại chuồng' })).toBeInTheDocument();
    expect(await screen.findByRole('alert')).toHaveTextContent('Không có quyền thực hiện thao tác này');
    spy.mockResolvedValueOnce([]);
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByText('Hiện chưa có chi nhánh nào mở dịch vụ lưu trú.')).toBeInTheDocument();
    expect(screen.queryAllByRole('article')).toHaveLength(0);
  });
});

describe('HotelPage: máy tính tiền lưu trú (giờ Việt Nam)', () => {
  it('chưa chọn ngày: hướng dẫn chọn; loại chuồng duy nhất được chọn sẵn', async () => {
    renderPage();
    await kennelCard('Chuồng chó nhỏ');
    const box = within(calculator());
    expect(box.getByRole('combobox', { name: 'Loại chuồng' })).toHaveValue('5');
    expect(box.getByText('Chọn loại chuồng và ngày để xem tiền tạm tính.')).toBeInTheDocument();
  });

  it('N đêm × giá đêm = tạm tính, kèm ghi chú chốt khi trả thú', async () => {
    const user = renderPage();
    await kennelCard('Chuồng chó nhỏ');
    await pickDate(user, 'Ngày nhận', '24/10/2026');
    await pickDate(user, 'Ngày trả', '27/10/2026');
    const result = within(calculator()).getByRole('status');
    expect(result).toHaveTextContent(/3 đêm × 200\.000/);
    expect(within(result).getByText(/^600\.000/)).toBeInTheDocument();
    expect(result).toHaveTextContent('Tiền chốt khi trả thú');
  });

  it('chọn thẻ loại chuồng thì máy tính đổi sang loại đó và tính lại', async () => {
    await raw.put('/api/branches/1/services/6', { enabled: true }, await staff('manager@store1.vn'));
    const user = renderPage();
    await kennelCard('Chuồng mèo');
    const box = within(calculator());
    await pickDate(user, 'Ngày nhận', '24/10/2026');
    await pickDate(user, 'Ngày trả', '26/10/2026');
    // Hai loại cùng được bật nên chưa chọn sẵn loại nào.
    expect(box.getByRole('combobox', { name: 'Loại chuồng' })).toHaveValue('');
    expect(box.getByRole('status')).toHaveTextContent('Chọn loại chuồng và ngày để xem tiền tạm tính.');
    const catCard = await kennelCard('Chuồng mèo');
    await user.click(within(catCard).getByRole('button', { name: /Chọn loại này/ }));
    expect(within(catCard).getByRole('button', { name: /Đang chọn/ })).toHaveAttribute('aria-pressed', 'true');
    expect(box.getByRole('combobox', { name: 'Loại chuồng' })).toHaveValue('6');
    expect(box.getByRole('status')).toHaveTextContent(/2 đêm × 180\.000/);
    expect(within(box.getByRole('status')).getByText(/^360\.000/)).toBeInTheDocument();
  });

  it('ngày nhận: hôm nay không chọn được, ngày mai được; ngày trả tối đa 30 đêm', async () => {
    const user = renderPage();
    await kennelCard('Chuồng chó nhỏ');
    await user.click(within(calculator()).getByRole('button', { name: 'Ngày nhận' }));
    expect(screen.getByRole('button', { name: '23/10/2026' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '24/10/2026' }));
    await user.click(within(calculator()).getByRole('button', { name: 'Ngày trả' }));
    await user.click(screen.getByRole('button', { name: 'Tháng sau' }));
    expect(screen.getByRole('button', { name: '23/11/2026' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '24/11/2026' })).toBeDisabled();
  });

  it('nút "Hôm nay" của bộ chọn bỏ qua giới hạn: máy tính báo lỗi và không đưa ra số tiền', async () => {
    const user = renderPage();
    await kennelCard('Chuồng chó nhỏ');
    await user.click(within(calculator()).getByRole('button', { name: 'Ngày nhận' }));
    await user.click(screen.getByRole('button', { name: 'Hôm nay' }));
    await pickDate(user, 'Ngày trả', '26/10/2026');
    expect(await within(calculator()).findByText(/Ngày nhận phải từ ngày mai/)).toBeInTheDocument();
    expect(within(calculator()).queryByText(/× 200\.000/)).not.toBeInTheDocument();
  });

  it('ranh giới nửa đêm giờ Việt Nam: 23:59 ngày 23/10 thì sớm nhất 24/10, 00:01 ngày 24/10 thì sớm nhất 25/10', async () => {
    pinClock('2026-10-23T23:59:00+07:00');
    const first = renderPage();
    await kennelCard('Chuồng chó nhỏ');
    await first.click(within(calculator()).getByRole('button', { name: 'Ngày nhận' }));
    expect(screen.getByRole('button', { name: '24/10/2026' })).toBeEnabled();
    cleanup();
    vi.setSystemTime(new Date('2026-10-24T00:01:00+07:00'));
    const second = renderPage();
    await kennelCard('Chuồng chó nhỏ');
    await second.click(within(calculator()).getByRole('button', { name: 'Ngày nhận' }));
    expect(screen.getByRole('button', { name: '24/10/2026' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '25/10/2026' })).toBeEnabled();
  });

  it('không có loại chuồng nào: máy tính nói rõ thay vì để trống', async () => {
    vi.spyOn(catalogApi, 'listPublicServices').mockResolvedValue([]);
    renderPage();
    await screen.findByText('Hiện chưa có chi nhánh nào mở dịch vụ lưu trú.');
    await waitFor(() => expect(within(calculator()).getByRole('combobox', { name: 'Loại chuồng' })).toBeDisabled());
  });
});
