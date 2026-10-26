import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { contentApi } from '../../shared/api/content.api';
import { installMockApi } from '../../shared/api/mock/mock-api';
import type { PublicArticleCard } from '../../shared/types/public-site';
import { NewsPage } from './NewsPage';

// UC17, BR-BV-03/04: tin tức công khai. Seed: 8 bài PUBLISHED (mới nhất: Royal Canin vs Pedigree, 28/07/2026), 3 chuyên mục hiện
// (Kiến thức nuôi thú cưng, Sức khỏe thú y, Chăm sóc hằng ngày) và 1 chuyên mục ẩn (Lưu trú & dịch vụ, có 1 bài PUBLISHED).
// Lỗi giả dùng 403 vì hook công khai không thử lại 4xx.

beforeAll(() => installMockApi(apiClient));

function UrlProbe() {
  const { pathname, search } = useLocation();
  return <output data-testid="url">{pathname + search}</output>;
}

function renderPage(url = '/news') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[url]}>
        <NewsPage />
        <UrlProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const currentUrl = () => screen.getByTestId('url').textContent;
const titles = () => screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
const gone = () => new ApiError('ACCESS_DENIED', 'Từ chối', 403);
const chips = () => within(screen.getByRole('group', { name: 'Chuyên mục' }));

const fake = (n: number): PublicArticleCard => ({
  articleId: 200 + n,
  title: `Bài viết trang ${n}`,
  slug: `bai-viet-trang-${n}`,
  categoryName: 'Kiến thức nuôi thú cưng',
  categorySlug: 'kien-thuc-nuoi-thu-cung',
  authorDisplayName: 'Tác giả',
  publishedAt: '2026-07-01T09:00:00+07:00',
});

describe('NewsPage (UC17)', () => {
  it('hiện bài seed: bài mới nhất nổi bật, các bài còn lại là thẻ có chuyên mục, tác giả, ngày, link /news/:slug', async () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Kiến thức chăm sóc thú cưng' })).toBeInTheDocument();

    const featured = (await screen.findByRole('heading', { level: 2, name: 'Royal Canin vs Pedigree: nên chọn hãng nào cho chó cưng?' })).closest('article')!;
    expect(featured).toHaveTextContent('Kiến thức nuôi thú cưng');
    expect(featured).toHaveTextContent('Dr. Minh Thu');
    expect(featured).toHaveTextContent('28/07/2026');
    expect(featured).toHaveTextContent('Cả hai đều là thương hiệu lớn');
    expect(featured).toHaveTextContent('Mới nhất');
    expect(within(featured).getByRole('link', { name: /Royal Canin vs Pedigree/ })).toHaveAttribute(
      'href',
      '/news/royal-canin-vs-pedigree-chon-hang-nao-cho-cho-cung',
    );

    const cards = screen.getAllByRole('heading', { level: 3 });
    expect(cards).toHaveLength(7);
    const second = cards[0].closest('article')!;
    expect(within(second).getByRole('link', { name: 'Mèo bỏ ăn 3 ngày liên tiếp — khi nào cần đưa đi cấp cứu?' })).toHaveAttribute(
      'href',
      '/news/meo-bo-an-3-ngay-khi-nao-can-cap-cuu',
    );
    expect(second).toHaveTextContent('Sức khỏe thú y');
    expect(second).toHaveTextContent('Dr. Lan Hương');
    expect(second).toHaveTextContent('25/07/2026');
    // Bài DRAFT / HIDDEN không bao giờ có mặt.
    expect(document.body).not.toHaveTextContent('Mèo cào sofa');
    expect(document.body).not.toHaveTextContent('Chó sợ pháo hoa');
    expect(screen.queryByRole('navigation', { name: 'Phân trang' })).not.toBeInTheDocument();
  });

  it('chip chuyên mục: Tất cả + chuyên mục không ẩn (chuyên mục ẩn không có chip)', async () => {
    renderPage();
    await screen.findByRole('heading', { level: 2 });
    expect(chips().getAllByRole('button').map((b) => b.textContent)).toEqual([
      'Tất cả',
      'Chăm sóc hằng ngày',
      'Kiến thức nuôi thú cưng',
      'Sức khỏe thú y',
    ]);
    expect(chips().getByRole('button', { name: 'Tất cả' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('bấm chip lọc theo ?category=<slug>, bấm "Tất cả" bỏ lọc', async () => {
    const spy = vi.spyOn(contentApi, 'listPublicArticles');
    const user = renderPage();
    await screen.findByRole('heading', { level: 2 });
    await user.click(chips().getByRole('button', { name: 'Sức khỏe thú y' }));
    expect(currentUrl()).toBe('/news?category=suc-khoe-thu-y');
    // 3 bài Sức khỏe thú y: bài mới nhất nổi bật, 2 bài còn lại là thẻ.
    await waitFor(() => expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(2));
    expect(titles()).toEqual(['Mèo bỏ ăn 3 ngày liên tiếp — khi nào cần đưa đi cấp cứu?']);
    expect(spy).toHaveBeenLastCalledWith(expect.objectContaining({ categorySlug: 'suc-khoe-thu-y', page: 0 }));
    expect(chips().getByRole('button', { name: 'Sức khỏe thú y' })).toHaveAttribute('aria-pressed', 'true');

    await user.click(chips().getByRole('button', { name: 'Tất cả' }));
    expect(currentUrl()).toBe('/news');
    await waitFor(() => expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(7));
  });

  it('link trực tiếp tới chuyên mục ẩn vẫn lọc ra bài (BR-BV-04) dù không có chip', async () => {
    renderPage('/news?category=luu-tru-dich-vu');
    expect(await screen.findByRole('heading', { level: 2, name: /Cách chọn khách sạn thú cưng uy tín/ })).toBeInTheDocument();
    expect(chips().queryByRole('button', { name: 'Lưu trú & dịch vụ' })).not.toBeInTheDocument();
    expect(chips().getByRole('button', { name: 'Tất cả' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('bài không có ảnh bìa hiện khối thay thế, bài có ảnh bìa hiện ảnh', async () => {
    renderPage('/news?category=cham-soc-hang-ngay');
    // Chăm sóc hằng ngày: "Đệm memory foam" (có bìa) nổi bật, "Dinh dưỡng cho chó mắc bệnh tiểu đường" (không bìa) là thẻ.
    const featured = (await screen.findByRole('heading', { level: 2, name: /Đệm memory foam/ })).closest('article')!;
    expect(featured.querySelector('img')).toHaveAttribute('src', '/imgs/blog_post01-Ic8URbXk.jpg');
    const plain = screen.getByRole('heading', { level: 3, name: /Dinh dưỡng cho chó mắc bệnh tiểu đường/ }).closest('article')!;
    expect(plain.querySelector('img')).toBeNull();
  });

  it('chuyên mục không có bài: khung rỗng + nút về tất cả bài viết', async () => {
    const user = renderPage('/news?category=khong-co-chuyen-muc-nay');
    expect(await screen.findByText('Chưa có bài viết trong chủ đề này.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Xem tất cả bài viết' }));
    expect(currentUrl()).toBe('/news');
    expect(await screen.findByRole('heading', { level: 2, name: /Royal Canin vs Pedigree/ })).toBeInTheDocument();
  });

  it('?page=2 gửi API page 1 (BE tính từ 0); phân trang đổi URL, trang 1 bỏ tham số', async () => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    const spy = vi.spyOn(contentApi, 'listPublicArticles').mockImplementation(async (query) => ({
      content: [fake((query?.page ?? 0) + 1)],
      page: query?.page ?? 0,
      size: 10,
      totalElements: 25,
      totalPages: 3,
    }));
    const user = renderPage('/news?page=2');
    // Chỉ trang đầu có bài nổi bật; từ trang 2 trở đi mọi bài là thẻ.
    expect(await screen.findByRole('heading', { level: 3, name: 'Bài viết trang 2' })).toBeInTheDocument();
    expect(spy).toHaveBeenCalledWith(expect.objectContaining({ page: 1, size: 10 }));
    const pager = screen.getByRole('navigation', { name: 'Phân trang' });
    expect(pager).toHaveTextContent('Trang 2 / 3');

    await user.click(within(pager).getByRole('button', { name: 'Trang sau' }));
    expect(currentUrl()).toBe('/news?page=3');
    expect(await screen.findByRole('heading', { level: 3, name: 'Bài viết trang 3' })).toBeInTheDocument();
    expect(within(pager).getByRole('button', { name: 'Trang sau' })).toBeDisabled();

    await user.click(within(pager).getByRole('button', { name: 'Trang trước' }));
    await user.click(within(pager).getByRole('button', { name: 'Trang trước' }));
    expect(currentUrl()).toBe('/news');
    expect(await screen.findByRole('heading', { level: 2, name: 'Bài viết trang 1' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 3 })).not.toBeInTheDocument();
  });

  it('lỗi tải bài: báo lỗi có "Thử lại", bấm thì tải lại được', async () => {
    vi.spyOn(contentApi, 'listPublicArticles').mockRejectedValueOnce(gone());
    const user = renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('Không tải được bài viết');
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByRole('heading', { level: 2, name: /Royal Canin vs Pedigree/ })).toBeInTheDocument();
  });

  it('lỗi tải chuyên mục không chặn bài viết: vẫn có chip "Tất cả" và danh sách bài', async () => {
    vi.spyOn(contentApi, 'listPublicArticleCategories').mockRejectedValue(gone());
    renderPage();
    expect(await screen.findByRole('heading', { level: 2, name: /Royal Canin vs Pedigree/ })).toBeInTheDocument();
    expect(chips().getAllByRole('button').map((b) => b.textContent)).toEqual(['Tất cả']);
  });

  it('đang tải: trạng thái đọc được', () => {
    renderPage();
    expect(screen.getByRole('status', { name: 'Đang tải bài viết' })).toBeInTheDocument();
  });
});
