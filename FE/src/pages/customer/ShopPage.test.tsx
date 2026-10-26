import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link, MemoryRouter, useLocation } from 'react-router-dom';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { catalogApi } from '../../shared/api/catalog.api';
import { installMockApi } from '../../shared/api/mock/mock-api';
import type { PublicProduct } from '../../shared/types/public-site';
import { ShopPage } from './ShopPage';

// UC16, BR-CK-01/03: cửa hàng công khai đọc /api/public/products. Seed có 5 sản phẩm (không kê đơn, đang bán), xếp theo tên:
// Hạt Royal Canin (Thức ăn), Pate Whiskas (Thức ăn), Vaccine 5 bệnh Vanguard + Vaccine Dại Rabisin (Vaccine), Vitamin tổng hợp
// (Thuốc, hết hàng ở mọi chi nhánh). Chi nhánh ACTIVE duy nhất là PetCare Cầu Giấy (id 1). Danh mục Phụ kiện không có sản phẩm.
// Lỗi giả dùng 403 vì hook công khai không thử lại 4xx.

beforeAll(() => installMockApi(apiClient));

const URL_PROBE = 'url-probe';

function UrlProbe() {
  const { pathname, search } = useLocation();
  return <output data-testid={URL_PROBE}>{pathname + search}</output>;
}

function renderPage(url = '/shop') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[url]}>
        <ShopPage />
        <UrlProbe />
        {/* Ô tìm ở header điều hướng sang /shop?q=… trong khi ShopPage vẫn đang mở. */}
        <Link to="/shop?q=vitamin">Tìm từ header</Link>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const currentUrl = () => screen.getByTestId(URL_PROBE).textContent;
const card = async (name: string) => (await screen.findByRole('heading', { level: 3, name })).closest('article')!;
const cardNames = () => screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent);
const gone = () => new ApiError('ACCESS_DENIED', 'Từ chối', 403);

const fake = (n: number): PublicProduct => ({
  productId: 100 + n,
  name: `Sản phẩm trang ${n}`,
  categoryId: 1,
  categoryName: 'Thức ăn',
  price: 99_000,
  unit: 'gói',
  inStockBranchIds: [1],
});

/** 3 trang giả, mỗi trang một sản phẩm. */
const threePages = () =>
  vi.spyOn(catalogApi, 'listPublicProducts').mockImplementation(async (query) => ({
    content: [fake((query?.page ?? 0) + 1)],
    page: query?.page ?? 0,
    size: 24,
    totalElements: 50,
    totalPages: 3,
  }));

describe('ShopPage (UC16)', () => {
  it('hiện sản phẩm seed: tên, danh mục, giá + đơn vị, tên chi nhánh còn hàng và không có số lượng', async () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Cửa hàng của boss' })).toBeInTheDocument();

    const royal = await card('Hạt Royal Canin Mini Adult 2kg');
    expect(within(royal).getByText('Thức ăn')).toBeInTheDocument();
    expect(royal).toHaveTextContent('450.000 đ');
    expect(royal).toHaveTextContent('túi');
    expect(royal).toHaveTextContent(/Còn hàng tại:\s*PetCare Cầu Giấy/);
    expect(within(royal).getByRole('link', { name: 'Hạt Royal Canin Mini Adult 2kg' })).toHaveAttribute('href', '/shop/1');

    expect(cardNames()).toEqual([
      'Hạt Royal Canin Mini Adult 2kg',
      'Pate Whiskas 85g',
      'Vaccine 5 bệnh Vanguard',
      'Vaccine Dại Rabisin',
      'Vitamin tổng hợp PetCare',
    ]);
    const vitamin = await card('Vitamin tổng hợp PetCare');
    expect(vitamin).toHaveTextContent('Tạm hết hàng tại các chi nhánh');
    expect(vitamin).not.toHaveTextContent('Còn hàng tại');
    expect(screen.getByRole('navigation', { name: 'Phân trang' })).toHaveTextContent('1–5 trên 5 sản phẩm');
  });

  it('không còn giỏ hàng, nút mua, yêu thích, đánh giá, đã bán (BR-CK-03: không đặt mua online)', async () => {
    renderPage();
    await card('Pate Whiskas 85g');
    expect(screen.queryByRole('button', { name: /giỏ|mua|yêu thích/i })).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(/Thêm vào giỏ|đã bán|Giao 2 giờ|4\.9\/5/);
    // Chỉ tên chi nhánh, không có "còn 12", "12 sản phẩm có sẵn"…
    expect(document.body).not.toHaveTextContent(/\bcòn \d|có sẵn/i);
  });

  it('ô tìm giới hạn 100 ký tự; gõ không gửi mỗi phím một request mà đợi ~300ms rồi ghi q lên URL', async () => {
    const spy = vi.spyOn(catalogApi, 'listPublicProducts');
    const user = renderPage();
    const box = screen.getByRole('searchbox', { name: 'Tìm sản phẩm' });
    expect(box).toHaveAttribute('maxlength', '100');
    await card('Pate Whiskas 85g');
    spy.mockClear();

    await user.type(box, 'vitamin');
    expect(spy).not.toHaveBeenCalled();
    await waitFor(() => expect(currentUrl()).toBe('/shop?q=vitamin'));
    expect(await screen.findByRole('heading', { level: 3, name: 'Vitamin tổng hợp PetCare' })).toBeInTheDocument();
    await waitFor(() => expect(cardNames()).toEqual(['Vitamin tổng hợp PetCare']));
    expect(spy).toHaveBeenCalledTimes(1);
    expect(spy).toHaveBeenCalledWith(expect.objectContaining({ q: 'vitamin', page: 0 }));
  });

  it('nhấn Enter tìm ngay, không đợi debounce; q có khoảng trắng đầu cuối được cắt', async () => {
    const user = renderPage();
    await card('Pate Whiskas 85g');
    await user.type(screen.getByRole('searchbox', { name: 'Tìm sản phẩm' }), '  pate {Enter}');
    expect(currentUrl()).toBe('/shop?q=pate');
    await waitFor(() => expect(cardNames()).toEqual(['Pate Whiskas 85g']));
  });

  it('?q= trên URL điền sẵn ô tìm và lọc; ô tìm của header đổi URL thì ô nhập theo', async () => {
    const user = renderPage('/shop?q=vaccine');
    await waitFor(() => expect(cardNames()).toEqual(['Vaccine 5 bệnh Vanguard', 'Vaccine Dại Rabisin']));
    const box = screen.getByRole('searchbox', { name: 'Tìm sản phẩm' });
    expect(box).toHaveValue('vaccine');

    await user.click(screen.getByRole('link', { name: 'Tìm từ header' }));
    await waitFor(() => expect(box).toHaveValue('vitamin'));
    await waitFor(() => expect(cardNames()).toEqual(['Vitamin tổng hợp PetCare']));
  });

  it('chip danh mục lọc theo ?category=<id>, hiện chip "đang lọc" và gỡ được', async () => {
    const user = renderPage();
    await card('Pate Whiskas 85g');
    const strip = screen.getByRole('group', { name: 'Danh mục' });
    // Danh mục thật từ API (có cả danh mục chưa có sản phẩm), không còn Chó/Mèo hay thức ăn/đồ chơi cứng.
    expect(within(strip).getAllByRole('button').map((b) => b.textContent)).toEqual(['Tất cả', 'Phụ kiện', 'Thuốc', 'Thức ăn', 'Vaccine']);
    expect(within(strip).getByRole('button', { name: 'Tất cả' })).toHaveAttribute('aria-pressed', 'true');

    await user.click(within(strip).getByRole('button', { name: 'Vaccine' }));
    expect(currentUrl()).toBe('/shop?category=3');
    await waitFor(() => expect(cardNames()).toEqual(['Vaccine 5 bệnh Vanguard', 'Vaccine Dại Rabisin']));
    expect(within(strip).getByRole('button', { name: 'Vaccine' })).toHaveAttribute('aria-pressed', 'true');

    await user.click(screen.getByRole('button', { name: 'Bỏ lọc Vaccine' }));
    expect(currentUrl()).toBe('/shop');
    await waitFor(() => expect(cardNames()).toHaveLength(5));
  });

  it('"Còn hàng tại" lọc theo ?branch=<id> và ghi tên chi nhánh ở chip đang lọc', async () => {
    const user = renderPage();
    await card('Pate Whiskas 85g');
    const select = screen.getByRole('combobox', { name: 'Còn hàng tại' });
    expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(['Tất cả chi nhánh', 'PetCare Cầu Giấy']);

    await user.selectOptions(select, 'PetCare Cầu Giấy');
    expect(currentUrl()).toBe('/shop?branch=1');
    // Vitamin hết hàng ở chi nhánh 1 nên rơi khỏi danh sách.
    await waitFor(() => expect(cardNames()).toHaveLength(4));
    expect(cardNames()).not.toContain('Vitamin tổng hợp PetCare');
    expect(screen.getByRole('button', { name: 'Bỏ lọc Còn hàng tại PetCare Cầu Giấy' })).toBeInTheDocument();
  });

  it('bỏ qua tham số lạ như ?petType=dog (link cũ của mega-menu) và category không phải số', async () => {
    const spy = vi.spyOn(catalogApi, 'listPublicProducts');
    renderPage('/shop?petType=dog&category=food&branch=abc&page=-3');
    await card('Pate Whiskas 85g');
    expect(cardNames()).toHaveLength(5);
    expect(spy).toHaveBeenCalledWith({ page: 0, size: 24 });
    expect(screen.queryByText('Không tải được danh sách sản phẩm')).not.toBeInTheDocument();
  });

  it('?page=2 gửi API page 1 (BE tính từ 0); chuyển trang đổi URL, trang 1 bỏ tham số page', async () => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    const spy = threePages();
    const user = renderPage('/shop?page=2');
    expect(await screen.findByRole('heading', { level: 3, name: 'Sản phẩm trang 2' })).toBeInTheDocument();
    expect(spy).toHaveBeenCalledWith(expect.objectContaining({ page: 1, size: 24 }));
    const pager = screen.getByRole('navigation', { name: 'Phân trang' });
    expect(within(pager).getByRole('button', { name: '2' })).toHaveAttribute('aria-current', 'page');
    expect(pager).toHaveTextContent('25–25 trên 50 sản phẩm');

    await user.click(within(pager).getByRole('button', { name: 'Trang sau' }));
    expect(currentUrl()).toBe('/shop?page=3');
    expect(await screen.findByRole('heading', { level: 3, name: 'Sản phẩm trang 3' })).toBeInTheDocument();
    expect(spy).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 }));
    expect(within(pager).getByRole('button', { name: 'Trang sau' })).toBeDisabled();

    await user.click(within(pager).getByRole('button', { name: '1' }));
    expect(currentUrl()).toBe('/shop');
    expect(await screen.findByRole('heading', { level: 3, name: 'Sản phẩm trang 1' })).toBeInTheDocument();
  });

  it('đổi bộ lọc đưa về trang đầu (bỏ ?page=)', async () => {
    threePages();
    const user = renderPage('/shop?page=3');
    await screen.findByRole('heading', { level: 3, name: 'Sản phẩm trang 3' });
    await user.click(await within(screen.getByRole('group', { name: 'Danh mục' })).findByRole('button', { name: 'Thức ăn' }));
    expect(currentUrl()).toBe('/shop?category=1');
  });

  it('lỗi tải: báo lỗi có "Thử lại", bấm thì tải lại được', async () => {
    vi.spyOn(catalogApi, 'listPublicProducts').mockRejectedValueOnce(gone());
    const user = renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('Không tải được danh sách sản phẩm');
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByRole('heading', { level: 3, name: 'Hạt Royal Canin Mini Adult 2kg' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('đang tải: skeleton có trạng thái đọc được, chưa có thẻ sản phẩm', async () => {
    renderPage();
    expect(screen.getByRole('status', { name: 'Đang tải sản phẩm' })).toBeInTheDocument();
    expect(screen.queryAllByRole('heading', { level: 3 })).toHaveLength(0);
    await card('Pate Whiskas 85g');
    expect(screen.queryByRole('status', { name: 'Đang tải sản phẩm' })).not.toBeInTheDocument();
  });

  it('không có sản phẩm khớp: khung rỗng + nút xóa bộ lọc đưa về danh sách đầy đủ', async () => {
    const user = renderPage('/shop?q=khongcosanphamnay&category=1');
    expect(await screen.findByText('Không tìm thấy sản phẩm phù hợp')).toBeInTheDocument();
    expect(screen.queryAllByRole('heading', { level: 3 })).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Xóa bộ lọc' }));
    expect(currentUrl()).toBe('/shop');
    expect(screen.getByRole('searchbox', { name: 'Tìm sản phẩm' })).toHaveValue('');
    await waitFor(() => expect(cardNames()).toHaveLength(5));
  });

  it('ngăn lọc trên điện thoại: chọn danh mục và chi nhánh rồi xem kết quả', async () => {
    const user = renderPage();
    await card('Pate Whiskas 85g');
    await user.click(screen.getByRole('button', { name: /^Bộ lọc/ }));
    const drawer = screen.getByRole('dialog', { name: 'Bộ lọc sản phẩm' });
    await user.click(within(drawer).getByRole('button', { name: 'Thuốc' }));
    await user.click(within(drawer).getByRole('button', { name: 'PetCare Cầu Giấy' }));
    // Thuốc duy nhất là Vitamin, hết hàng ở chi nhánh 1 → rỗng.
    await waitFor(() => expect(currentUrl()).toBe('/shop?category=2&branch=1'));
    await user.click(within(drawer).getByRole('button', { name: 'Xóa hết' }));
    expect(currentUrl()).toBe('/shop');
    await user.click(within(drawer).getByRole('button', { name: /^Xem \d+ sản phẩm$/ }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
