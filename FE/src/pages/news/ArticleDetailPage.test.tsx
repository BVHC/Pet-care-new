import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { contentApi } from '../../shared/api/content.api';
import { installMockApi } from '../../shared/api/mock/mock-api';
import type { PublicArticle } from '../../shared/types/public-site';
import { ArticleDetailPage } from './ArticleDetailPage';

// UC17, BR-BV-03, ADR-0071: chi tiết bài. Seed: bài PUBLISHED đọc được theo slug; bài DRAFT (meo-cao-sofa-5-cach-ngan-chan), bài
// HIDDEN (cho-so-phao-hoa-4-buoc-binh-tinh) và slug lạ cùng một 404. Bài 10 thuộc chuyên mục ẩn nhưng vẫn đọc được.
// Lỗi giả dùng 403 vì hook công khai không thử lại 4xx (404 là khung "không tìm thấy").

beforeAll(() => installMockApi(apiClient));

function renderPage(slug: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/news/${slug}`]}>
        <Routes>
          <Route path="/news/:slug" element={<ArticleDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const article = (content: string, extra: Partial<PublicArticle> = {}): PublicArticle => ({
  articleId: 77,
  title: 'Bài thử nghiệm HTML',
  slug: 'bai-thu-nghiem-html',
  categoryName: 'Kiến thức nuôi thú cưng',
  categorySlug: 'kien-thuc-nuoi-thu-cung',
  authorDisplayName: 'Dr. Thử Nghiệm',
  publishedAt: '2026-07-01T09:00:00Z',
  content,
  ...extra,
});

const notFound = () => screen.findByRole('heading', { level: 1, name: 'Không tìm thấy bài viết' });

describe('ArticleDetailPage (UC17)', () => {
  it('hiện bài PUBLISHED: tiêu đề, chuyên mục (link lọc), tác giả, ngày xuất bản, ảnh bìa và nội dung', async () => {
    renderPage('royal-canin-vs-pedigree-chon-hang-nao-cho-cho-cung');
    expect(await screen.findByRole('heading', { level: 1, name: 'Royal Canin vs Pedigree: nên chọn hãng nào cho chó cưng?' })).toBeInTheDocument();
    expect(document.title).toBe('Royal Canin vs Pedigree: nên chọn hãng nào cho chó cưng? | PetCare');

    const header = screen.getByRole('article');
    expect(within(header).getByRole('link', { name: 'Kiến thức nuôi thú cưng' })).toHaveAttribute('href', '/news?category=kien-thuc-nuoi-thu-cung');
    expect(header).toHaveTextContent('Dr. Minh Thu');
    expect(within(header).getByText('28/07/2026').closest('time')).toHaveAttribute('datetime', '2026-07-28T09:00:00+07:00');
    expect(within(header).getByText(/Nhiều chủ nuôi vẫn đang phân vân giữa Royal Canin và Pedigree/)).toBeInTheDocument();
    // Ảnh bìa trang trí (tiêu đề đã nằm ngay trên), dùng ảnh nội bộ của mock.
    expect(header.querySelector('img')).toHaveAttribute('src', '/imgs/blog_post01-Ic8URbXk.jpg');

    expect(screen.getByRole('link', { name: /Tất cả bài viết/ })).toHaveAttribute('href', '/news');
  });

  it('nội dung HTML được render thành phần tử thật: heading con và danh sách', async () => {
    renderPage('meo-bo-an-3-ngay-khi-nao-can-cap-cuu');
    const heading = await screen.findByRole('heading', { level: 2, name: 'Khi nào cần đưa đi cấp cứu ngay' });
    const body = screen.getByRole('article');
    expect(heading.closest('article')).toBe(body);
    expect(within(body).getAllByRole('heading', { level: 2 })).toHaveLength(2);
    expect(within(body).getAllByRole('listitem')).toHaveLength(8);
    // Không còn thẻ literal trong chữ.
    expect(body).not.toHaveTextContent('<h2>');
  });

  it('bài ở chuyên mục ẩn vẫn đọc được (BR-BV-04)', async () => {
    renderPage('chon-khach-san-thu-cung-uy-tin');
    expect(await screen.findByRole('heading', { level: 1, name: /Cách chọn khách sạn thú cưng uy tín/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Lưu trú & dịch vụ' })).toHaveAttribute('href', '/news?category=luu-tru-dich-vu');
  });

  it('bài không có ảnh bìa: không có thẻ ảnh, không khối trống', async () => {
    renderPage('day-cun-4-thang-tuoi-di-ve-sinh-dung-cho');
    expect(await screen.findByRole('heading', { level: 1, name: /Cách tôi dạy bé cún 4 tháng tuổi/ })).toBeInTheDocument();
    expect(screen.getByRole('article').querySelector('img')).toBeNull();
  });

  it('ảnh trong nội dung bài được giữ lại kèm alt, blockquote và đoạn văn', async () => {
    vi.spyOn(contentApi, 'getPublicArticle').mockResolvedValue(
      article(
        '<p>Mở đầu.</p><blockquote>Trích dẫn hay</blockquote><img src="https://cdn.example.com/a.jpg" alt="Ảnh minh họa"><p>Kết <em>bài</em>.</p>',
      ),
    );
    renderPage('bai-thu-nghiem-html');
    expect(await screen.findByRole('img', { name: 'Ảnh minh họa' })).toHaveAttribute('src', 'https://cdn.example.com/a.jpg');
    expect(screen.getByText('Trích dẫn hay').tagName).toBe('BLOCKQUOTE');
    expect(screen.getByText('bài').tagName).toBe('EM');
    // Mốc "…Z" vẫn ra ngày giờ Việt Nam.
    expect(screen.getByText('01/07/2026')).toBeInTheDocument();
  });

  it.each([
    ['meo-cao-sofa-5-cach-ngan-chan', 'bài DRAFT'],
    ['cho-so-phao-hoa-4-buoc-binh-tinh', 'bài HIDDEN'],
    ['khong-co-bai-nay', 'slug lạ'],
  ])('/news/%s (%s): cùng một khung "Không tìm thấy bài viết" + link về tin tức (BR-BV-03)', async (slug) => {
    renderPage(slug);
    expect(await notFound()).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Xem tất cả bài viết' })).toHaveAttribute('href', '/news');
    expect(screen.queryByRole('button', { name: 'Thử lại' })).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(/DRAFT|HIDDEN|nháp|đã ẩn/i);
  });

  it('lỗi khác 404: báo lỗi có "Thử lại", bấm thì tải lại được', async () => {
    vi.spyOn(contentApi, 'getPublicArticle').mockRejectedValueOnce(new ApiError('ACCESS_DENIED', 'Từ chối', 403));
    const user = renderPage('vaccine-5-benh-cho-cho-con-lich-tiem');
    expect(await screen.findByRole('alert')).toHaveTextContent('Không tải được bài viết');
    expect(screen.queryByRole('heading', { name: 'Không tìm thấy bài viết' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByRole('heading', { level: 1, name: /Vaccine 5 bệnh cho chó con/ })).toBeInTheDocument();
  });

  it('đang tải: trạng thái đọc được', () => {
    renderPage('vaccine-5-benh-cho-cho-con-lich-tiem');
    expect(screen.getByRole('status', { name: 'Đang tải bài viết' })).toBeInTheDocument();
  });
});
