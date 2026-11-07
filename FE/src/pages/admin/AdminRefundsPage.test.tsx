import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { returnsApi } from '../../shared/api/returns.api';
import { salesApi } from '../../shared/api/sales.api';
import { pinClock, signInAs } from '../../test/cashier-harness';
import { AdminRefundsPage } from './AdminRefundsPage';

// Dữ liệu mẫu (commerce.store.ts + mock bán hàng), chi nhánh 1, thứ Ba 27/10/2026 10:00 (chi nhánh mở cửa).
// Yêu cầu trả hàng (cũ nhất trước): #1 PENDING (Order quầy #5, pate ×2, do lễ tân #4 tạo) · #5 PENDING (đơn online #2, do tài khoản #8 tạo) · #2 APPROVED · #3 REJECTED · #4 CANCELLED · #6 APPROVED · #7 REFUNDED.
// Hoàn tiền "cần xử lý", cũ nhất trước: #3 GATEWAY FAILED (3 lần thử, của yêu cầu #6), rồi #2 CASH REQUESTED 18.000 đ (của yêu cầu #2). Dòng #51 của Order #5 còn được trả 3.
vi.mock('../../shared/constants/realtime', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../shared/constants/realtime')>()),
  retryDelay: () => 0,
}));

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  pinClock('2026-10-27T10:00:00+07:00');
  await signInAs('reception@store1.vn');
});
afterEach(() => vi.useRealTimers());

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AdminRefundsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

type User = ReturnType<typeof renderPage>;
const tableRows = async (name: string) => within(await screen.findByRole('table', { name })).getAllByRole('row').slice(1);
const requestRows = () => tableRows('Danh sách yêu cầu trả hàng');
const refundRows = () => tableRows('Danh sách hoàn tiền');
const dialog = () => screen.getByRole('dialog');
const buttonNames = (row: HTMLElement) => within(row).queryAllByRole('button').map((b) => b.getAttribute('aria-label') ?? b.textContent);
const openRefunds = async (user: User) => {
  await user.click(screen.getByRole('tab', { name: 'Hoàn tiền' }));
  return refundRows();
};

describe('yêu cầu trả hàng (UC68)', () => {
  it('mặc định là yêu cầu chờ duyệt, cũ nhất trước; đổi tab trạng thái xem được yêu cầu bị từ chối', async () => {
    const user = renderPage();
    const rows = await requestRows();
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('#5');
    expect(rows[0]).toHaveTextContent('Hàng lỗi');
    expect(rows[1]).toHaveTextContent('Lý do khác');
    expect(screen.getByRole('tab', { name: 'Chờ duyệt' })).toHaveAttribute('aria-selected', 'true');

    await user.click(screen.getByRole('tab', { name: 'Bị từ chối' }));
    await waitFor(async () => expect(await requestRows()).toHaveLength(1));
    await user.click(within((await requestRows())[0]).getByRole('button', { name: /^Xem / }));
    expect(await within(dialog()).findByText('Hàng đã mở bao, không nhận trả')).toBeInTheDocument();
    expect(within(dialog()).getByRole('table', { name: 'Dòng hàng trả' })).toHaveTextContent('Pate Whiskas 85g');
  });

  it('lễ tân tạo yêu cầu cho Order quầy: tra cứu mã Order, nhập số lượng, chọn lý do', async () => {
    const user = renderPage();
    await requestRows();
    await user.click(screen.getByRole('button', { name: /Tạo yêu cầu trả hàng/ }));
    await user.click(within(dialog()).getByRole('button', { name: 'Tạo yêu cầu' }));
    expect(within(dialog()).getByRole('alert')).toHaveTextContent('Tra cứu Order cần trả hàng trước.');

    await user.type(within(dialog()).getByLabelText('Mã Order'), '5');
    await user.click(within(dialog()).getByRole('button', { name: 'Tra cứu' }));
    const qty = await within(dialog()).findByLabelText(/^Số lượng trả Pate/);
    await user.click(within(dialog()).getByRole('button', { name: 'Tạo yêu cầu' }));
    expect(within(dialog()).getByRole('alert')).toHaveTextContent('Nhập số lượng trả cho ít nhất một dòng.');
    await user.type(qty, '1');
    await user.click(within(dialog()).getByRole('button', { name: 'Tạo yêu cầu' }));
    expect(within(dialog()).getByRole('alert')).toHaveTextContent('Chọn lý do trả hàng.');
    await user.selectOptions(within(dialog()).getByLabelText('Lý do trả hàng'), 'Khách đổi ý');
    await user.click(within(dialog()).getByRole('button', { name: 'Tạo yêu cầu' }));

    expect(await screen.findByText(/Đã tạo yêu cầu trả hàng TH-.*chờ quản lý chi nhánh duyệt/)).toBeInTheDocument();
    await waitFor(async () => expect(await requestRows()).toHaveLength(3));
  });

  it('vượt số lượng được trả (400 BR-BH-13) hiện ngay trong hộp thoại; Order không tồn tại hiện lỗi tra cứu', async () => {
    const user = renderPage();
    await requestRows();
    await user.click(screen.getByRole('button', { name: /Tạo yêu cầu trả hàng/ }));
    await user.type(within(dialog()).getByLabelText('Mã Order'), '9999');
    await user.click(within(dialog()).getByRole('button', { name: 'Tra cứu' }));
    expect(await within(dialog()).findByRole('alert')).toBeInTheDocument();

    await user.clear(within(dialog()).getByLabelText('Mã Order'));
    await user.type(within(dialog()).getByLabelText('Mã Order'), '5');
    await user.click(within(dialog()).getByRole('button', { name: 'Tra cứu' }));
    await user.type(await within(dialog()).findByLabelText(/^Số lượng trả Pate/), '4');
    await user.selectOptions(within(dialog()).getByLabelText('Lý do trả hàng'), 'Hàng lỗi');
    await user.click(within(dialog()).getByRole('button', { name: 'Tạo yêu cầu' }));
    expect(await within(dialog()).findByText(/vượt số lượng được trả \(còn 3\)/)).toBeInTheDocument();
  });

  it('lễ tân rút yêu cầu của chính mình; yêu cầu do người khác tạo bị từ chối (403) và báo ngay trên trang', async () => {
    const user = renderPage();
    const [mine, other] = await requestRows(); // #1 (lễ tân #4), #5 (tài khoản #8)
    await user.click(within(other).getByRole('button', { name: /^Rút / }));
    await user.click(within(dialog()).getByRole('button', { name: 'Rút yêu cầu' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Không có quyền truy cập dữ liệu này');
    expect(await requestRows()).toHaveLength(2);

    await user.click(within(mine).getByRole('button', { name: /^Rút / }));
    await user.click(within(dialog()).getByRole('button', { name: 'Rút yêu cầu' }));
    expect(await screen.findByText(/Đã rút yêu cầu trả hàng/)).toBeInTheDocument();
    await waitFor(async () => expect(await requestRows()).toHaveLength(1));
  });

  it('lễ tân không thấy nút Duyệt / Từ chối', async () => {
    renderPage();
    const rows = await requestRows();
    for (const row of rows) expect(buttonNames(row).join()).not.toMatch(/Duyệt|Từ chối/);
  });
});

describe('quản lý chi nhánh duyệt / từ chối (UC69)', () => {
  beforeEach(() => signInAs('manager@store1.vn'));

  it('không thấy nút Tạo / Rút; thấy Duyệt và Từ chối', async () => {
    renderPage();
    const rows = await requestRows();
    expect(screen.queryByRole('button', { name: /Tạo yêu cầu trả hàng/ })).not.toBeInTheDocument();
    for (const row of rows) {
      expect(buttonNames(row).join()).not.toMatch(/Rút/);
      expect(buttonNames(row).join()).toMatch(/Duyệt .*Từ chối/);
    }
  });

  it('duyệt bắt buộc chọn nhập lại kho hay loại bỏ; duyệt xong yêu cầu rời danh sách chờ duyệt', async () => {
    const user = renderPage();
    const [, online] = await requestRows(); // #1 Order quầy, #5 đơn online
    await user.click(within(online).getByRole('button', { name: /^Duyệt / }));
    await user.click(within(dialog()).getByRole('button', { name: 'Duyệt' }));
    expect(within(dialog()).getByRole('alert')).toHaveTextContent('Chọn nhập lại kho hoặc loại bỏ hàng trả.');
    await user.click(within(dialog()).getByLabelText('Loại bỏ (hàng hỏng, không bán lại)'));
    await user.click(within(dialog()).getByRole('button', { name: 'Duyệt' }));
    expect(await screen.findByText(/Đã duyệt TH-.*450\.000/)).toBeInTheDocument();
    await waitFor(async () => expect(await requestRows()).toHaveLength(1));
  });

  it('từ chối bắt buộc lý do; lý do hiện ở chi tiết yêu cầu bị từ chối', async () => {
    const user = renderPage();
    const [first] = await requestRows();
    await user.click(within(first).getByRole('button', { name: /^Từ chối / }));
    await user.click(within(dialog()).getByRole('button', { name: 'Từ chối' }));
    expect(within(dialog()).getByRole('alert')).toHaveTextContent('Nhập lý do từ chối.');
    await user.type(within(dialog()).getByLabelText('Lý do từ chối'), 'Túi hạt còn nguyên, không lỗi');
    await user.click(within(dialog()).getByRole('button', { name: 'Từ chối' }));
    expect(await screen.findByText(/Đã từ chối yêu cầu trả hàng/)).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Bị từ chối' }));
    await waitFor(async () => expect(await requestRows()).toHaveLength(2));
  });

  it('người khác đã duyệt trước (409) hiện câu báo trạng thái đã đổi trong hộp thoại', async () => {
    const user = renderPage();
    const [first] = await requestRows();
    await user.click(within(first).getByRole('button', { name: /^Từ chối / }));
    await returnsApi.approve(1, { restock: true });
    await user.type(within(dialog()).getByLabelText('Lý do từ chối'), 'Không đủ điều kiện');
    await user.click(within(dialog()).getByRole('button', { name: 'Từ chối' }));
    expect(await within(dialog()).findByRole('alert')).toHaveTextContent('Yêu cầu vừa được xử lý ở nơi khác');
  });
});

describe('hoàn tiền (UC69)', () => {
  it('mặc định là khoản cần xử lý: tiền mặt chờ hoàn và khoản qua cổng thất bại kèm lý do', async () => {
    const user = renderPage();
    const rows = await openRefunds(user);
    expect(rows).toHaveLength(2);
    expect(rows[1]).toHaveTextContent('Tiền mặt');
    expect(rows[1]).toHaveTextContent('Chờ hoàn');
    expect(rows[0]).toHaveTextContent('Hoàn thất bại');
    expect(rows[0]).toHaveTextContent('Cổng từ chối hoàn tiền: tài khoản nguồn tạm khóa (3 lần thử)');
  });

  it('lễ tân chỉ thấy nút xác nhận ở khoản tiền mặt; chưa mở ca thì BR-TG-01 kèm đường tới bàn Lễ tân, mở ca xong xác nhận được', async () => {
    const user = renderPage();
    const [gateway, cash] = await openRefunds(user);
    expect(buttonNames(gateway)).toEqual([]);
    await user.click(within(cash).getByRole('button', { name: /^Xác nhận hoàn / }));
    await user.click(within(dialog()).getByRole('button', { name: 'Đã hoàn tiền' }));
    expect(await within(dialog()).findByRole('alert')).toHaveTextContent('Bạn chưa mở ca thu ngân');
    expect(within(dialog()).getByRole('link', { name: 'bàn Lễ tân' })).toHaveAttribute('href', '/staff/reception');

    await salesApi.openShift({ afterHours: false });
    await user.click(within(dialog()).getByRole('button', { name: 'Đã hoàn tiền' }));
    expect(await screen.findByText(/Đã xác nhận hoàn tiền HT-.*18\.000.*tại quầy/)).toBeInTheDocument();
    await waitFor(async () => expect(await refundRows()).toHaveLength(1));
  });

  it('quản lý thử lại khoản qua cổng thất bại; không thấy nút xác nhận tại quầy', async () => {
    await signInAs('manager@store1.vn');
    const user = renderPage();
    const [gateway, cash] = await openRefunds(user);
    expect(buttonNames(cash)).toEqual([]);
    await user.click(within(gateway).getByRole('button', { name: /^Thử lại / }));
    expect(await screen.findByText(/Đã yêu cầu hoàn tiền lại qua cổng/)).toBeInTheDocument();
    await waitFor(async () => expect((await refundRows())[0]).toHaveTextContent('Chờ hoàn'));
    expect(buttonNames((await refundRows())[0])).toEqual([]);
  });

  it('lễ tân không thấy nút Thử lại; lọc "Đã hoàn" xem được các khoản đã xong', async () => {
    const user = renderPage();
    const [gateway] = await openRefunds(user);
    expect(gateway).not.toHaveTextContent('Thử lại');
    await user.selectOptions(screen.getByLabelText('Lọc trạng thái hoàn tiền'), 'Đã hoàn');
    await waitFor(async () => expect(await refundRows()).toHaveLength(2));
    for (const row of await refundRows()) expect(row).toHaveTextContent('Đã hoàn');
  });

  it('khoản đã đổi trạng thái ở nơi khác (409) hiện thành thông báo', async () => {
    await signInAs('manager@store1.vn');
    const user = renderPage();
    const [gateway] = await openRefunds(user);
    await returnsApi.retryRefund(3); // người khác thử lại trước
    await user.click(within(gateway).getByRole('button', { name: /^Thử lại / }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Khoản hoàn tiền vừa được xử lý ở nơi khác');
  });
});
