import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

  // Cờ purchasableOnline (BR-BH-07: giỏ online chỉ nhận hàng thường). Chỉ `=== false` mới bỏ ô số lượng và nút. Dùng khớp chữ CHÍNH XÁC:
  // câu mô tả của hàng thường cũng chứa "mua trực tiếp tại quầy" nên khớp mờ sẽ không phân biệt được.
  const COUNTER_NOTE = 'Mua trực tiếp tại quầy';
  const COUNTER_ONLY_TEXT = 'Sản phẩm này chỉ bán trực tiếp tại quầy các chi nhánh.';
  const ONLINE_TEXT = 'Đặt online giao tận nơi (thanh toán trước qua cổng), hoặc mua trực tiếp tại quầy các chi nhánh.';

  it('vaccine (purchasableOnline = false) còn hàng: không ô số lượng, không "Thêm vào giỏ"; có "Mua trực tiếp tại quầy"; giá, chi nhánh còn hàng, câu mô tả chỉ bán tại quầy', async () => {
    await loginAs();
    renderPage('5');
    await screen.findByRole('heading', { level: 1, name: 'Vaccine Dại Rabisin' });
    expect(screen.queryByLabelText('Số lượng đặt')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Thêm vào giỏ' })).not.toBeInTheDocument();
    expect(screen.getByText(COUNTER_NOTE)).toBeInTheDocument();
    expect(screen.getByText(COUNTER_ONLY_TEXT)).toBeInTheDocument();
    expect(screen.queryByText(ONLINE_TEXT)).not.toBeInTheDocument();
    const main = screen.getByRole('article');
    expect(main).toHaveTextContent('150.000 đ');
    expect(within(screen.getByRole('list', { name: 'Chi nhánh còn hàng' })).getByRole('link', { name: 'PetCare Cầu Giấy' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Xem chi nhánh/ })).toHaveAttribute('href', '/branches');
  });

  it('hàng thường (true): có ô số lượng và nút, câu mô tả đặt online, không có dòng "Mua trực tiếp tại quầy"', async () => {
    renderPage('1');
    await screen.findByRole('heading', { level: 1, name: 'Hạt Royal Canin Mini Adult 2kg' });
    expect(screen.getByLabelText('Số lượng đặt')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Thêm vào giỏ' })).toBeInTheDocument();
    expect(screen.getByText(ONLINE_TEXT)).toBeInTheDocument();
    expect(screen.queryByText(COUNTER_NOTE)).not.toBeInTheDocument();
    expect(screen.queryByText(COUNTER_ONLY_TEXT)).not.toBeInTheDocument();
  });

  it('nhân viên (lễ tân) xem vaccine: vẫn thấy "Mua trực tiếp tại quầy" (thông tin, không phụ thuộc vai trò), không ô số lượng, không nút', async () => {
    await loginAs('reception@store1.vn');
    renderPage('5');
    await screen.findByRole('heading', { level: 1, name: 'Vaccine Dại Rabisin' });
    expect(screen.getByText(COUNTER_NOTE)).toBeInTheDocument();
    expect(screen.queryByLabelText('Số lượng đặt')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Thêm vào giỏ' })).not.toBeInTheDocument();
  });

  it('thuốc (false) hết hàng ở mọi chi nhánh: chỉ "Tạm hết hàng", không dòng mua tại quầy; câu mô tả vẫn đổi thành chỉ bán tại quầy', async () => {
    renderPage('4');
    await screen.findByRole('heading', { level: 1, name: 'Vitamin tổng hợp PetCare' });
    expect(screen.getByText('Tạm hết hàng tại các chi nhánh')).toBeInTheDocument();
    expect(screen.queryByText(COUNTER_NOTE)).not.toBeInTheDocument();
    expect(screen.getByText(COUNTER_ONLY_TEXT)).toBeInTheDocument();
  });

  it('BE cũ chưa gửi trường (vắng = true): vaccine vẫn có nút; bấm thì BE từ chối 400 BR-BH-07, trang không hỏng và giỏ không có vaccine', async () => {
    await loginAs();
    const spy = vi.spyOn(shopApi, 'setCartItem');
    vi.spyOn(catalogApi, 'getPublicProduct').mockResolvedValue({
      productId: 5,
      name: 'Vaccine Dại Rabisin',
      categoryId: 3,
      categoryName: 'Vaccine',
      price: 150_000,
      unit: 'liều',
      inStockBranchIds: [1],
    });
    const user = renderPage('5');
    await screen.findByRole('heading', { level: 1, name: 'Vaccine Dại Rabisin' });
    expect(screen.queryByText(COUNTER_NOTE)).not.toBeInTheDocument();
    expect(screen.getByText(ONLINE_TEXT)).toBeInTheDocument();
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

  // Ảnh bìa + ảnh phụ (galleryUrls). Seed mock: id 1 có bìa và 2 ảnh phụ, id 2 có bìa và 1 ảnh phụ.
  it('có ảnh phụ: dải ảnh nhỏ gồm ảnh bìa rồi các ảnh phụ theo thứ tự; bấm ảnh nhỏ đổi ảnh lớn', async () => {
    const user = renderPage('1');
    await screen.findByRole('heading', { level: 1, name: 'Hạt Royal Canin Mini Adult 2kg' });
    const name = 'Hạt Royal Canin Mini Adult 2kg';
    const buttons = screen.getAllByRole('button', { name: /^Xem ảnh \d+ trên 3$/ });
    expect(buttons).toHaveLength(3);
    expect(screen.getByRole('img', { name })).toHaveAttribute('src', '/imgs/prod1.jpg');
    expect(buttons[2]).not.toHaveAttribute('aria-current');
    await user.click(buttons[2]);
    expect(screen.getByRole('img', { name })).toHaveAttribute('src', '/imgs/prod3.jpg');
    expect(buttons[2]).toHaveAttribute('aria-current', 'true');
    expect(buttons[0]).not.toHaveAttribute('aria-current');
  });

  it('chỉ có ảnh bìa (không ảnh phụ): ảnh lớn, không có dải ảnh nhỏ, không có nút Trước / Sau', async () => {
    vi.spyOn(catalogApi, 'getPublicProduct').mockResolvedValue({
      productId: 1,
      name: 'Hạt Royal Canin Mini Adult 2kg',
      categoryId: 1,
      categoryName: 'Thức ăn',
      price: 450_000,
      unit: 'túi',
      imageUrl: '/imgs/prod1.jpg',
      galleryUrls: [],
      inStockBranchIds: [1],
    });
    renderPage('1');
    await screen.findByRole('heading', { level: 1, name: 'Hạt Royal Canin Mini Adult 2kg' });
    expect(screen.getByRole('img', { name: 'Hạt Royal Canin Mini Adult 2kg' })).toHaveAttribute('src', '/imgs/prod1.jpg');
    expect(screen.queryByRole('button', { name: /^Xem ảnh/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Ảnh sau' })).not.toBeInTheDocument();
  });

  it('chưa có ảnh bìa nhưng có ảnh phụ: ảnh phụ vẫn hiện; ảnh phụ trùng ảnh bìa không hiện hai lần', async () => {
    vi.spyOn(catalogApi, 'getPublicProduct').mockResolvedValue({
      productId: 4,
      name: 'Vitamin tổng hợp PetCare',
      categoryId: 2,
      categoryName: 'Thuốc',
      price: 120_000,
      unit: 'lọ',
      imageUrl: null,
      galleryUrls: ['/imgs/prod4.jpg', '/imgs/prod5.jpg', '/imgs/prod4.jpg'],
      inStockBranchIds: [],
    });
    renderPage('4');
    await screen.findByRole('heading', { level: 1, name: 'Vitamin tổng hợp PetCare' });
    expect(screen.getAllByRole('button', { name: /^Xem ảnh \d+ trên 2$/ })).toHaveLength(2);
    expect(screen.getByRole('img', { name: 'Vitamin tổng hợp PetCare' })).toHaveAttribute('src', '/imgs/prod4.jpg');
  });

  it('ảnh lớn của ảnh bìa tải lỗi: chuyển sang ảnh phụ, không để khung vỡ', async () => {
    renderPage('2');
    await screen.findByRole('heading', { level: 1, name: 'Pate Whiskas 85g' });
    fireEvent.error(screen.getByRole('img', { name: 'Pate Whiskas 85g' }));
    expect(screen.getByRole('img', { name: 'Pate Whiskas 85g' })).toHaveAttribute('src', '/imgs/prod7.jpg');
    expect(screen.queryByRole('button', { name: /^Xem ảnh/ })).not.toBeInTheDocument(); // còn một ảnh tốt nên không còn dải
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
