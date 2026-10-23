import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminPOSPage } from './AdminPOSPage';

// Mock (sales.mock.ts), chi nhánh 1: #1 bán lẻ OPEN · #2 Visit PENDING hôm nay · #3 Visit PENDING 2 ngày trước (quá hạn)
// · #4 bán lẻ PENDING · #5 PAID · #6, #7 CANCELLED. Mã Order có đuôi 0000n theo id.

beforeAll(() => installMockApi(apiClient));

const signIn = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <AdminPOSPage />
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const open = (n: number) => screen.findByRole('button', { name: new RegExp(`Xem Order DH-\\d{4}-0000${n}`) });

describe('AdminPOSPage (T34 — UC66)', () => {
  it('quản lý: không tạo Order; lọc chờ thu quá hạn; hủy Order khách không thanh toán phải có lý do (BR-BH-05)', async () => {
    await signIn('manager@store1.vn');
    const user = renderPage();
    await open(1);
    expect(screen.queryByRole('button', { name: 'Tạo Order bán lẻ' })).not.toBeInTheDocument();

    await user.click(screen.getByLabelText('Chờ thu quá hạn'));
    await waitFor(() => expect(screen.queryByRole('button', { name: /Xem Order DH-\d{4}-00001/ })).not.toBeInTheDocument());
    await user.click(await open(3));

    expect(await screen.findByText('Chưa thanh toán – chưa giao hàng')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Hủy – khách không thanh toán' }));
    expect(screen.getByText(/thất thu/)).toBeInTheDocument();
    const confirm = screen.getByRole('button', { name: 'Xác nhận hủy Order' });
    expect(confirm).toBeDisabled();
    await user.type(screen.getByLabelText('Lý do hủy'), 'Khám xong bỏ về, gọi không được');
    await user.click(confirm);

    expect(await screen.findByText(/Đã hủy Order DH-\d{4}-00003 — ghi nhận thất thu/)).toBeInTheDocument();
    expect((await apiClient.get('/api/orders/3')).data.data).toMatchObject({
      status: 'CANCELLED',
      cancelType: 'UNPAID',
      cancelReason: 'Khám xong bỏ về, gọi không được',
    });
  });

  it('quản lý xem Order bán lẻ đang mở: chỉ đọc, không có nút bán / hủy', async () => {
    await signIn('manager@store1.vn');
    const user = renderPage();
    await user.click(await open(1));
    await screen.findByRole('heading', { name: /Order DH-\d{4}-00001/ });
    expect(screen.queryByRole('button', { name: 'Tiếp tục bán' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Hủy Order' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Hủy – khách không thanh toán' })).not.toBeInTheDocument();
  });

  it('lễ tân: tạo Order bán lẻ, làm tiếp Order đang mở; không hủy được Order chờ thu', async () => {
    await signIn('reception@store1.vn');
    const user = renderPage();
    await user.click(await open(4));
    await screen.findByRole('heading', { name: /Order DH-\d{4}-00004/ });
    expect(screen.queryByRole('button', { name: 'Hủy – khách không thanh toán' })).not.toBeInTheDocument();

    await user.click(await open(1));
    await user.click(await screen.findByRole('button', { name: 'Tiếp tục bán' }));
    const panel = screen.getByRole('region', { name: 'Bán lẻ tại quầy' });
    expect(await within(panel).findByPlaceholderText('Tìm sản phẩm, Enter để thêm')).toBeInTheDocument();
    expect(within(within(panel).getByRole('list', { name: 'Dòng Order' })).getByText('Hạt Royal Canin Mini Adult 2kg')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Tạo Order bán lẻ' }));
    expect(await screen.findByPlaceholderText('SĐT, họ tên hoặc email của khách')).toBeInTheDocument();
  });
});
