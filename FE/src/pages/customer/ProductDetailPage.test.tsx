import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { catalogApi } from '../../shared/api/catalog.api';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { ProductDetailPage } from './ProductDetailPage';

// UC16, BR-CK-01/03: chi tiết sản phẩm công khai. Seed: id 1 Hạt Royal Canin (còn hàng ở PetCare Cầu Giấy), id 4 Vitamin tổng hợp
// (hết hàng mọi chi nhánh), id 3 Amoxicillin là thuốc kê đơn nên 404 như id lạ. Lỗi giả dùng 403 (hook công khai không thử lại 4xx).

beforeAll(() => installMockApi(apiClient));

function renderPage(id: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/shop/${id}`]}>
        <Routes>
          <Route path="/shop/:id" element={<ProductDetailPage />} />
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
    expect(main).toHaveTextContent(/bán tại quầy/i);
    expect(main).toHaveTextContent(/liên hệ chi nhánh/i);

    const crumbs = screen.getByRole('navigation', { name: 'Đường dẫn' });
    expect(within(crumbs).getByRole('link', { name: 'Danh mục sản phẩm' })).toHaveAttribute('href', '/shop');
  });

  it('không có giỏ hàng, nút mua, đánh giá, ưu đãi vận chuyển (BR-CK-03), không số lượng tồn', async () => {
    renderPage('1');
    await screen.findByRole('heading', { level: 1, name: 'Hạt Royal Canin Mini Adult 2kg' });
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(/giỏ hàng|Mua ngay|đánh giá|vận chuyển|Đổi trả|có sẵn|Số lượng/i);
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
