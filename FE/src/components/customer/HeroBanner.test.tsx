import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { PAGES_STATE_KEY } from '../../shared/api/mock/page-content.mock';
import { pageContentApi } from '../../shared/api/page-content.api';
import { PAGE_DEFAULTS } from '../../shared/constants/page-content';
import type { PageContent } from '../../shared/types/page-content';
import { HeroBanner } from './HeroBanner';

// Hero trang chủ (UC21, khóa HOME_BANNER): chữ do quản trị sửa, mặc định = chữ tĩnh cũ. Lỗi giả dùng 403 vì hook công khai không thử lại 4xx.

beforeAll(() => installMockApi(apiClient));

function renderHero() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <HeroBanner />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const banner = PAGE_DEFAULTS.HOME_BANNER;
const edited = {
  ...banner,
  headline: 'Mèo\nChó',
  subheadline: 'Yêu thương\nmỗi ngày',
  ctaLabel: 'Hẹn khám ngay',
  heroStat: { value: '3.000+', label: 'Gia đình\nđồng hành' },
};

/** Ghi thẳng state mock như admin đã sửa (mock đọc lại state ở mọi request). */
function setBanner(content: unknown) {
  localStorage.setItem(PAGES_STATE_KEY, JSON.stringify({ HOME_BANNER: { key: 'HOME_BANNER', title: null, content, updatedAt: '2026-10-20T08:00:00+07:00' } }));
}

const h1 = () => screen.getByRole('heading', { level: 1 });
/** Cả khối chữ của hero có nằm trong một phần tử `opacity-0` (giữ chỗ, chưa hiện) hay không. */
const hidden = () => h1().closest('[class*="opacity-0"]') !== null;

describe('HeroBanner: chữ mặc định khi BE chưa có nội dung ({}), sai dạng hoặc lỗi', () => {
  it('{} (BE seed): đúng chữ hero cũ, xuống dòng giữ nguyên, nút đặt lịch trỏ /booking', async () => {
    renderHero();
    await waitFor(() => expect(hidden()).toBe(false));
    expect(h1().textContent).toBe('Pet\nCare');
    expect(h1()).toHaveClass('whitespace-pre-line');
    expect(screen.getByText('Tận tâm chăm sóc', { exact: false }).textContent).toBe('Tận tâm chăm sóc\nbé cưng của bạn');
    expect(screen.getByRole('link', { name: 'Đặt Lịch Ngay' })).toHaveAttribute('href', '/booking');
    expect(screen.getByText('2.500+')).toBeInTheDocument();
    expect(screen.getByText('Chủ thú cưng', { exact: false }).textContent).toBe('Chủ thú cưng\ntin tưởng');
  });

  it('sai dạng (thiếu trường, quá giới hạn): cả bộ về mặc định', async () => {
    setBanner({ ...edited, ctaLabel: undefined });
    renderHero();
    await waitFor(() => expect(hidden()).toBe(false));
    expect(h1().textContent).toBe('Pet\nCare');
    expect(screen.getByRole('link', { name: 'Đặt Lịch Ngay' })).toBeInTheDocument();
    expect(screen.queryByText('Mèo', { exact: false })).not.toBeInTheDocument();
  });

  it('request lỗi: hero vẫn hiện chữ mặc định (không trống, không kẹt opacity-0)', async () => {
    vi.spyOn(pageContentApi, 'getPublic').mockRejectedValue(new ApiError('ACCESS_DENIED', 'Từ chối', 403));
    renderHero();
    await waitFor(() => expect(hidden()).toBe(false));
    expect(h1().textContent).toBe('Pet\nCare');
    expect(screen.getByRole('link', { name: 'Đặt Lịch Ngay' })).toHaveAttribute('href', '/booking');
    expect(screen.getByText('2.500+')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('HeroBanner: chữ do quản trị sửa', () => {
  it('tiêu đề, mô tả, nút, số liệu đổi theo nội dung đã lưu; chữ mặc định không còn', async () => {
    setBanner(edited);
    renderHero();
    await waitFor(() => expect(h1().textContent).toBe('Mèo\nChó'));
    expect(screen.getByText('Yêu thương', { exact: false }).textContent).toBe('Yêu thương\nmỗi ngày');
    expect(screen.getByRole('link', { name: 'Hẹn khám ngay' })).toHaveAttribute('href', '/booking');
    expect(screen.getByText('3.000+')).toBeInTheDocument();
    expect(screen.getByText('Gia đình', { exact: false }).textContent).toBe('Gia đình\nđồng hành');
    for (const old of ['Tận tâm chăm sóc', 'Đặt Lịch Ngay', '2.500+', 'Chủ thú cưng']) {
      expect(screen.queryByText(old, { exact: false })).not.toBeInTheDocument();
    }
    expect(hidden()).toBe(false);
  });

  it('không nháy: chưa có câu trả lời thì cả khối chữ nằm trong phần tử opacity-0, có câu trả lời thì bỏ lớp đó và hiện chữ đã sửa', async () => {
    let answer!: (page: PageContent) => void;
    vi.spyOn(pageContentApi, 'getPublic').mockReturnValue(new Promise<PageContent>((resolve) => (answer = resolve)));
    renderHero();
    expect(hidden()).toBe(true);
    await act(async () => answer({ key: 'HOME_BANNER', title: null, content: edited, updatedAt: '2026-10-20T08:00:00+07:00' }));
    await waitFor(() => expect(hidden()).toBe(false));
    expect(h1().textContent).toBe('Mèo\nChó');
  });

  it('chỉ văn bản thuần: thẻ HTML gõ vào hiện thành chữ, không thành phần tử', async () => {
    setBanner({ ...edited, headline: '<b id="x">Mèo</b>', ctaLabel: '<i id="y">Đặt</i>' });
    renderHero();
    await waitFor(() => expect(h1().textContent).toBe('<b id="x">Mèo</b>'));
    expect(screen.getByRole('link', { name: '<i id="y">Đặt</i>' })).toBeInTheDocument();
    expect(document.getElementById('x')).toBeNull();
    expect(document.getElementById('y')).toBeNull();
  });
});

describe('HeroBanner: khối còn lại', () => {
  it('không còn link "Xem tất cả đánh giá Google" và không link nào trỏ /review (BR-DG-02: feedback không công khai)', async () => {
    renderHero();
    await waitFor(() => expect(hidden()).toBe(false));
    expect(screen.queryByText(/đánh giá Google/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Google/i })).not.toBeInTheDocument();
    for (const link of screen.getAllByRole('link')) expect(link.getAttribute('href')).not.toBe('/review');
  });

  it('ba thẻ dưới hero giữ nguyên (không có khóa)', async () => {
    renderHero();
    await waitFor(() => expect(hidden()).toBe(false));
    const hrefs = screen.getAllByRole('link').map((l) => l.getAttribute('href'));
    expect(hrefs).toEqual(expect.arrayContaining(['/news', '/recommend', '/hotel']));
    expect(within(screen.getByRole('link', { name: /Khách Sạn/ })).getByText('đặt phòng ngay')).toBeInTheDocument();
  });
});
