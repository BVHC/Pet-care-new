import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { loginAs } from '../../components/customer/shop/testing';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { catalogApi } from '../../shared/api/catalog.api';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { shopApi } from '../../shared/api/shop.api';
import { useSession } from '../../shared/stores/session.store';
import { ProductDetailPage } from './ProductDetailPage';

// UC16, BR-CK-01/03 + UC61: chi tiết sản phẩm công khai; khách đặt số lượng rồi thêm vào giỏ (khách vãng lai bị chuyển sang đăng nhập, nhân viên không thấy).
// Seed: id 1 Hạt Royal Canin (còn hàng ở PetCare Cầu Giấy), id 4 Vitamin tổng hợp (hết hàng mọi chi nhánh), id 5 Vaccine Dại (không bán online),
// id 3 Amoxicillin là thuốc kê đơn nên 404 như id lạ. Lỗi giả dùng 403 (hook công khai không thử lại 4xx).

beforeAll(() => installMockApi(apiClient));
beforeEach(() => useSession.getState().clear());

function renderPage(id: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/shop/${id}`]}>
        <Routes>
          <Route path="/shop/:id" element={<ProductDetailPage />} />
          <Route path="/auth/login" element={<p>Trang đăng nhập</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const notFound = () => screen.findByRole('heading', { level: 1, name: 'Không tìm thấy sản phẩm' });

describe('ProductDetailPage (UC16)', () => {
  it('hiện sản phẩm thật: tên, danh mục, giá, đơn vị, chi nhánh còn hàng (link /branches) và ghi chú mua tại quầy', async () => {
    renderPage('1');
    expect(await screen.findByRole('heading', { level: 1, name: 'Hạt Royal Canin Mini Adult 2kg' })).toBeInTheDocument();
    const main = screen.getByRole('article');
    expect(within(main).getByText('Thức ăn')).toBeInTheDocument();
    expect(main).toHaveTextContent('450.000 đ');
    expect(main).toHaveTextContent('túi');

    const stock = screen.getByRole('list', { name: 'Chi nhánh còn hàng' });
    expect(within(stock).getByRole('link', { name: 'PetCare Cầu Giấy' })).toHaveAttribute('href', '/branches');
    expect(main).toHaveTextContent(/mua trực tiếp tại quầy/i);

    const crumbs = screen.getByRole('navigation', { name: 'Đường dẫn' });
    expect(within(crumbs).getByRole('link', { name: 'Danh mục sản phẩm' })).toHaveAttribute('href', '/shop');
  });

  it('không có đánh giá, đổi trả, "mua ngay", không lộ số lượng tồn (BR-CK-03)', async () => {
    renderPage('1');
    await screen.findByRole('heading', { level: 1, name: 'Hạt Royal Canin Mini Adult 2kg' });
    expect(document.body).not.toHaveTextContent(/Mua ngay|đánh giá|Đổi trả|có sẵn|còn \d/i);
  });

  it('khách vãng lai: có ô số lượng và "Thêm vào giỏ"; bấm thì chuyển sang đăng nhập, giỏ không bị ghi', async () => {
    const setItem = vi.spyOn(shopApi, 'setCartItem');
    const user = renderPage('1');
    await screen.findByRole('heading', { level: 1, name: 'Hạt Royal Canin Mini Adult 2kg' });
    expect(screen.getByLabelText('Số lượng đặt')).toHaveValue(1);
    await user.click(screen.getByRole('button', { name: 'Thêm vào giỏ' }));
    expect(await screen.findByText('Trang đăng nhập')).toBeInTheDocument();
    expect(setItem).not.toHaveBeenCalled();
  });

  it('khách hàng: đặt số lượng 3 rồi thêm vào giỏ, giỏ trên BE cộng dồn vào dòng có sẵn (1 + 3)', async () => {
    await loginAs();
    const user = renderPage('1');
    await screen.findByRole('heading', { level: 1, name: 'Hạt Royal Canin Mini Adult 2kg' });
    const box = screen.getByLabelText('Số lượng đặt');
    await user.clear(box);
    await user.type(box, '3');
    await user.click(screen.getByRole('button', { name: 'Thêm vào giỏ' }));
    await waitFor(async () => expect((await shopApi.getCart()).items.find((i) => i.productId === 1)?.quantity).toBe(4));
  });

  it('số lượng đặt bị kẹp trong 1–99', async () => {
    const user = renderPage('1');
    await screen.findByRole('heading', { level: 1, name: 'Hạt Royal Canin Mini Adult 2kg' });
    const box = screen.getByLabelText('Số lượng đặt');
    await user.clear(box);
    await user.type(box, '500');
    expect(box).toHaveValue(99);
  });

  it('vaccine không bán online (400 BR-BH-07 của BE): trang không hỏng và giỏ không có vaccine', async () => {
    await loginAs();
    const spy = vi.spyOn(shopApi, 'setCartItem');
    const user = renderPage('5');
    await screen.findByRole('heading', { level: 1, name: 'Vaccine Dại Rabisin' });
    await user.click(screen.getByRole('button', { name: 'Thêm vào giỏ' }));
    await waitFor(() => expect(spy).toHaveBeenCalledWith(5, { quantity: 1 }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Thêm vào giỏ' })).toBeEnabled());
    expect((await shopApi.getCart()).items.map((i) => i.productId)).toEqual([1, 2]);
  });

  it('nhân viên (lễ tân) không thấy ô số lượng lẫn nút thêm vào giỏ', async () => {
    await loginAs('reception@store1.vn');
    renderPage('1');
    await screen.findByRole('heading', { level: 1, name: 'Hạt Royal Canin Mini Adult 2kg' });
    expect(screen.queryByRole('button', { name: 'Thêm vào giỏ' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Số lượng đặt')).not.toBeInTheDocument();
  });

  it('mô tả hiện khi có, ảnh thật hiện khi có imageUrl', async () => {
    vi.spyOn(catalogApi, 'getPublicProduct').mockResolvedValue({
      productId: 1,
      name: 'Hạt Royal Canin Mini Adult 2kg',
      categoryId: 1,
      categoryName: 'Thức ăn',
      price: 450_000,
      unit: 'túi',
      description: 'Hạt khô cho chó nhỏ trưởng thành.',
      imageUrl: 'https://cdn.example.com/royal.jpg',
      inStockBranchIds: [1],
    });
    renderPage('1');
    expect(await screen.findByText('Hạt khô cho chó nhỏ trưởng thành.')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Hạt Royal Canin Mini Adult 2kg' })).toHaveAttribute('src', 'https://cdn.example.com/royal.jpg');
  });

  it('chưa có ảnh: khối thay thế, không có thẻ ảnh vỡ', async () => {
    renderPage('4');
    await screen.findByRole('heading', { level: 1, name: 'Vitamin tổng hợp PetCare' });
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('hết hàng ở mọi chi nhánh: ghi rõ, không liệt kê chi nhánh', async () => {
    renderPage('4');
    expect(await screen.findByRole('heading', { level: 1, name: 'Vitamin tổng hợp PetCare' })).toBeInTheDocument();
    expect(screen.getByText('Tạm hết hàng tại các chi nhánh')).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Chi nhánh còn hàng' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Thêm vào giỏ' })).not.toBeInTheDocument();
  });

  it.each([
    ['999', 'id không có'],
    ['3', 'thuốc kê đơn (BR-CK-01)'],
  ])('/shop/%s (%s): khung không tìm thấy + link về cửa hàng', async (id) => {
    renderPage(id);
    expect(await notFound()).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Quay lại cửa hàng' })).toHaveAttribute('href', '/shop');
    expect(screen.queryByRole('button', { name: 'Thử lại' })).not.toBeInTheDocument();
  });

  it.each(['abc', '0', '-1', '1.5', '1e3', '99999999999'])('/shop/%s: id không hợp lệ coi như không tìm thấy, không gọi API', async (id) => {
    const spy = vi.spyOn(catalogApi, 'getPublicProduct');
    renderPage(id);
    expect(await notFound()).toBeInTheDocument();
    expect(spy).not.toHaveBeenCalled();
  });

  it('lỗi khác 404: báo lỗi có "Thử lại", bấm thì tải lại được', async () => {
    vi.spyOn(catalogApi, 'getPublicProduct').mockRejectedValueOnce(new ApiError('ACCESS_DENIED', 'Từ chối', 403));
    const user = renderPage('1');
    expect(await screen.findByRole('alert')).toHaveTextContent('Không tải được sản phẩm');
    expect(screen.queryByRole('heading', { name: 'Không tìm thấy sản phẩm' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Hạt Royal Canin Mini Adult 2kg' })).toBeInTheDocument();
  });

  it('đang tải: trạng thái đọc được', () => {
    renderPage('1');
    expect(screen.getByRole('status', { name: 'Đang tải sản phẩm' })).toBeInTheDocument();
  });
});
