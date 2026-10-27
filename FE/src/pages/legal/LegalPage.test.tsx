import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { PAGES_STATE_KEY } from '../../shared/api/mock/page-content.mock';
import { pageContentApi } from '../../shared/api/page-content.api';
import { PAGE_DEFAULTS } from '../../shared/constants/page-content';
import type { PageContent } from '../../shared/types/page-content';
import { PrivacyPage, TermsPage } from './LegalPage';

// Điều khoản (/terms) và Chính sách bảo mật (/privacy) cùng đọc nội dung trang POLICY do quản trị sửa (UC21, T47). Mặc định = chữ tĩnh cũ
// (7 mục mỗi tài liệu). Khối liên hệ cuối trang vẫn tĩnh (không có khóa). Lỗi giả dùng 403 vì hook công khai không thử lại 4xx.

beforeAll(() => installMockApi(apiClient));

function renderPage(page: 'terms' | 'privacy') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>{page === 'terms' ? <TermsPage /> : <PrivacyPage />}</MemoryRouter>
    </QueryClientProvider>,
  );
}

const gone = () => new ApiError('ACCESS_DENIED', 'Từ chối', 403);
const AT = '2026-10-20T08:00:00+07:00';
const doc = (over: Partial<(typeof PAGE_DEFAULTS.POLICY)['terms']> = {}) => ({
  lead: 'Mở đầu mới.',
  updatedOn: '01/11/2026',
  sections: [
    { heading: 'Điều một', body: 'Đoạn A.\n\n   \nĐoạn B.' },
    { heading: 'Điều hai', body: 'Chỉ một đoạn.' },
  ],
  ...over,
});

/** Ghi thẳng state mock như admin đã sửa (mock đọc lại state ở mọi request). */
function setPolicy(content: unknown) {
  localStorage.setItem(PAGES_STATE_KEY, JSON.stringify({ POLICY: { key: 'POLICY', title: null, content, updatedAt: AT } }));
}

const sections = () => screen.getAllByRole('heading', { level: 2 });
const article = () => document.getElementById('muc-1')!.closest('article')!;

describe('TermsPage / PrivacyPage: chữ mặc định khi BE chưa có nội dung ({}), sai dạng hoặc lỗi', () => {
  it('Điều khoản: tiêu đề, lead, ngày cập nhật, 7 mục đánh số, đoạn tách theo dòng, mục lục trỏ #muc-N', async () => {
    renderPage('terms');
    expect(screen.getByRole('heading', { level: 1, name: 'Điều khoản sử dụng' })).toBeInTheDocument();
    expect(await screen.findByText(PAGE_DEFAULTS.POLICY.terms.lead)).toBeInTheDocument();
    expect(screen.getByText('Cập nhật lần cuối: 16/09/2026')).toBeInTheDocument();
    expect(sections().map((h) => h.textContent)).toEqual(
      PAGE_DEFAULTS.POLICY.terms.sections.map((s, i) => `${i + 1}. ${s.heading}`),
    );
    expect(sections()[0]).toHaveTextContent('1. Chấp nhận điều khoản');
    expect(within(article()).getAllByText(/Khi tạo tài khoản|Nếu bạn không đồng ý/)).toHaveLength(2);
    const toc = screen.getAllByRole('link', { name: /^\d\. / });
    expect(toc.map((a) => a.getAttribute('href'))).toEqual(['#muc-1', '#muc-2', '#muc-3', '#muc-4', '#muc-5', '#muc-6', '#muc-7']);
    expect(document.getElementById('muc-3')).toHaveTextContent('3. Đặt phòng và thanh toán');
    expect(screen.getByRole('link', { name: 'Đọc Chính sách bảo mật' })).toHaveAttribute('href', '/privacy');
  });

  it('Bảo mật: tiêu đề, lead, 7 mục, liên kết sang Điều khoản', async () => {
    renderPage('privacy');
    expect(screen.getByRole('heading', { level: 1, name: 'Chính sách bảo mật' })).toBeInTheDocument();
    expect(await screen.findByText(PAGE_DEFAULTS.POLICY.privacy.lead)).toBeInTheDocument();
    expect(sections()).toHaveLength(7);
    expect(sections()[0]).toHaveTextContent('1. Dữ liệu chúng tôi thu thập');
    expect(sections()[6]).toHaveTextContent('7. Cookie');
    expect(screen.getByRole('link', { name: 'Đọc Điều khoản sử dụng' })).toHaveAttribute('href', '/terms');
  });

  it('khối liên hệ cuối trang vẫn tĩnh (không có khóa)', async () => {
    renderPage('terms');
    await screen.findByText(PAGE_DEFAULTS.POLICY.terms.lead);
    expect(screen.getByRole('link', { name: 'hotro@petcare.vn' })).toHaveAttribute('href', 'mailto:hotro@petcare.vn');
    expect(document.body).toHaveTextContent('1900 6868');
  });

  it('sai dạng (thiếu tài liệu, danh sách mục rỗng, lead rỗng): cả bộ về mặc định', async () => {
    for (const content of [{ terms: doc() }, { terms: doc({ sections: [] }), privacy: doc() }, { terms: doc({ lead: '   ' }), privacy: doc() }]) {
      setPolicy(content);
      const { unmount } = renderPage('terms');
      expect(await screen.findByText(PAGE_DEFAULTS.POLICY.terms.lead)).toBeInTheDocument();
      expect(sections()).toHaveLength(7);
      expect(document.body).not.toHaveTextContent('Mở đầu mới.');
      unmount();
    }
  });

  it('request nội dung trang lỗi: vẫn hiện chữ mặc định, skeleton không kẹt, không văng lỗi', async () => {
    vi.spyOn(pageContentApi, 'getPublic').mockRejectedValue(gone());
    renderPage('privacy');
    expect(await screen.findByText(PAGE_DEFAULTS.POLICY.privacy.lead)).toBeInTheDocument();
    expect(sections()).toHaveLength(7);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('TermsPage / PrivacyPage: chữ do quản trị sửa', () => {
  it('mỗi trang lấy đúng tài liệu của nó; ngày cập nhật, lead, mục và mục lục đổi; chữ mặc định không còn', async () => {
    setPolicy({
      terms: doc({ lead: 'Mở đầu điều khoản.', updatedOn: '01/11/2026' }),
      privacy: doc({ lead: 'Mở đầu bảo mật.', updatedOn: '02/11/2026', sections: [{ heading: 'Chỉ một mục', body: 'Nội dung.' }] }),
    });
    const terms = renderPage('terms');
    expect(await screen.findByText('Mở đầu điều khoản.')).toBeInTheDocument();
    expect(screen.getByText('Cập nhật lần cuối: 01/11/2026')).toBeInTheDocument();
    expect(sections().map((h) => h.textContent)).toEqual(['1. Điều một', '2. Điều hai']);
    expect(screen.getAllByRole('link', { name: /^\d\. / }).map((a) => a.getAttribute('href'))).toEqual(['#muc-1', '#muc-2']);
    expect(document.body).not.toHaveTextContent(/Chấp nhận điều khoản|Mở đầu bảo mật/);
    terms.unmount();

    renderPage('privacy');
    expect(await screen.findByText('Mở đầu bảo mật.')).toBeInTheDocument();
    expect(screen.getByText('Cập nhật lần cuối: 02/11/2026')).toBeInTheDocument();
    expect(sections().map((h) => h.textContent)).toEqual(['1. Chỉ một mục']);
    expect(document.body).not.toHaveTextContent(/Điều một|Mở đầu điều khoản|Dữ liệu chúng tôi thu thập/);
  });

  it('mỗi dòng của nội dung là một đoạn; dòng trống và dòng chỉ có khoảng trắng bị bỏ', async () => {
    setPolicy({ terms: doc(), privacy: doc() });
    renderPage('terms');
    await screen.findByText('Mở đầu mới.');
    const first = document.getElementById('muc-1')!;
    expect([...first.querySelectorAll('p')].map((p) => p.textContent)).toEqual(['Đoạn A.', 'Đoạn B.']);
    expect(document.getElementById('muc-2')!.querySelectorAll('p')).toHaveLength(1);
  });

  it('đang tải: skeleton đọc được, chưa lộ chữ mặc định để khỏi nháy; về rồi mới hiện chữ đã sửa', async () => {
    let answer!: (page: PageContent) => void;
    vi.spyOn(pageContentApi, 'getPublic').mockReturnValue(new Promise<PageContent>((resolve) => (answer = resolve)));
    renderPage('terms');
    expect(screen.getByRole('heading', { level: 1, name: 'Điều khoản sử dụng' })).toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Đang tải nội dung' })).toBeInTheDocument();
    expect(screen.queryByText(PAGE_DEFAULTS.POLICY.terms.lead)).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 2 })).not.toBeInTheDocument();
    expect(screen.queryByText(/Cập nhật lần cuối/)).not.toBeInTheDocument();

    await act(async () => answer({ key: 'POLICY', title: null, content: { terms: doc(), privacy: doc() }, updatedAt: AT }));
    expect(await screen.findByText('Mở đầu mới.')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('chỉ văn bản thuần: thẻ HTML gõ vào hiện thành chữ, không thành phần tử (ADR-0071)', async () => {
    setPolicy({
      terms: doc({
        lead: '<b id="l">Mở đầu</b>',
        updatedOn: '<i id="u">nay</i>',
        sections: [{ heading: '<u id="h">Điều</u>', body: '<img id="i" src="x" onerror="alert(1)">\n<script id="s">alert(1)</script>' }],
      }),
      privacy: doc(),
    });
    renderPage('terms');
    expect(await screen.findByText('<b id="l">Mở đầu</b>')).toBeInTheDocument();
    expect(screen.getByText('Cập nhật lần cuối: <i id="u">nay</i>')).toBeInTheDocument();
    expect(sections()[0]).toHaveTextContent('1. <u id="h">Điều</u>');
    expect(screen.getByText('<img id="i" src="x" onerror="alert(1)">')).toBeInTheDocument();
    expect(screen.getByText('<script id="s">alert(1)</script>')).toBeInTheDocument();
    for (const id of ['l', 'u', 'h', 'i', 's']) expect(document.getElementById(id)).toBeNull();
  });
});
