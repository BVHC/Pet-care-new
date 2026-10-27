import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { branchApi } from '../../shared/api/branch.api';
import { catalogApi } from '../../shared/api/catalog.api';
import { contentApi } from '../../shared/api/content.api';
import { identityApi } from '../../shared/api/identity.api';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { PAGES_STATE_KEY } from '../../shared/api/mock/page-content.mock';
import { pageContentApi } from '../../shared/api/page-content.api';
import { PAGE_DEFAULTS } from '../../shared/constants/page-content';
import type { PageContent, PageKey } from '../../shared/types/page-content';
import type { PublicVet } from '../../shared/types/public-site';
import { HomePage } from './HomePage';

// Trang chủ đọc dữ liệu thật (T46): sản phẩm, bác sĩ, tin tức, dịch vụ (OurServicesStage), chi nhánh (ContactVetCTA). Seed: 5 sản phẩm
// (xếp theo tên), 4 bác sĩ ở PetCare Cầu Giấy (BS. Minh Anh đầu tiên, có ảnh), 8 bài viết, 5 dịch vụ ở 3 nhóm, 1 chi nhánh nhận cấp cứu ngoài giờ.
// Hero, dải số liệu và FAQ đọc nội dung trang do quản trị sửa (UC21, T47). Lỗi giả dùng 403 vì hook công khai không thử lại 4xx.

beforeAll(() => installMockApi(apiClient));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <HomePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const gone = () => new ApiError('ACCESS_DENIED', 'Từ chối', 403);
const emptyPage = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };

const vet = (accountId: number, fullName: string, extra: Partial<PublicVet> = {}): PublicVet => ({
  accountId,
  fullName,
  branchId: 1,
  branchName: 'PetCare Cầu Giấy',
  ...extra,
});

describe('HomePage: dữ liệu thật (T46)', () => {
  it('sản phẩm nổi bật: 4 sản phẩm đầu với giá thật, link chi tiết, không giỏ hàng, không lọc chó/mèo', async () => {
    renderPage();
    const section = (await screen.findByRole('heading', { level: 2, name: 'Sản Phẩm Nổi Bật' })).closest('section')!;
    const links = await within(section).findAllByRole('link', { name: /Hạt Royal Canin|Pate Whiskas|Vaccine/ });
    expect(links.map((l) => l.getAttribute('href'))).toEqual(['/shop/1', '/shop/2', '/shop/6', '/shop/5']);
    expect(links[0]).toHaveTextContent('Hạt Royal Canin Mini Adult 2kg');
    expect(links[0]).toHaveTextContent('450.000 đ');
    expect(links[0]).toHaveTextContent('Thức ăn');
    expect(within(section).queryByText(/Vitamin/)).not.toBeInTheDocument();

    expect(within(section).queryByRole('button')).not.toBeInTheDocument();
    expect(section).not.toHaveTextContent(/Thêm vào giỏ|Cho Chó|Cho Mèo|\$/);
    expect(within(section).getByRole('link', { name: /Xem tất cả sản phẩm/ })).toHaveAttribute('href', '/shop');
  });

  it('bác sĩ: hồ sơ thật (ảnh, chuyên môn, chi nhánh), không còn tên bác sĩ giả', async () => {
    renderPage();
    const section = (await screen.findByRole('heading', { level: 2, name: /Bác Sĩ Chuyên Môn/ })).closest('section')!;
    expect(await within(section).findByRole('heading', { level: 3, name: 'BS. Minh Anh' })).toBeInTheDocument();
    expect(section).toHaveTextContent('Nội khoa, da liễu thú y');
    expect(section).toHaveTextContent('PetCare Cầu Giấy');
    expect(within(section).getByRole('img', { name: 'BS. Minh Anh' })).toHaveAttribute('src', '/imgs/team1.jpg');
    expect(section).not.toHaveTextContent(/Daria Andaloro|Michael Brian|Veterinary Technician/);
    expect(within(section).getByRole('link', { name: /Xem đội ngũ bác sĩ/ })).toHaveAttribute('href', '/doctors');
  });

  it('bác sĩ: chịu được nhiều hồ sơ (tối đa 4 thẻ), ảnh thật hiện alt là tên', async () => {
    vi.spyOn(identityApi, 'listPublicVets').mockResolvedValue([
      vet(1, 'BS. An', { avatarUrl: 'https://cdn.example.com/an.jpg' }),
      vet(2, 'BS. Bình'),
      vet(3, 'BS. Chi'),
      vet(4, 'BS. Dũng'),
      vet(5, 'BS. Em'),
    ]);
    renderPage();
    const section = (await screen.findByRole('heading', { level: 2, name: /Bác Sĩ Chuyên Môn/ })).closest('section')!;
    expect(await within(section).findAllByRole('heading', { level: 3 })).toHaveLength(4);
    expect(within(section).getByRole('img', { name: 'BS. An' })).toHaveAttribute('src', 'https://cdn.example.com/an.jpg');
    expect(within(section).queryByText('BS. Em')).not.toBeInTheDocument();
  });

  it('tin tức: 3 bài mới nhất, link /news/:slug, ngày giờ VN, "Xem tất cả" về /news', async () => {
    renderPage();
    const section = (await screen.findByRole('heading', { level: 2, name: 'Bài Viết Mới Nhất' })).closest('section')!;
    const first = await within(section).findByRole('link', { name: /Royal Canin vs Pedigree/ });
    expect(first).toHaveAttribute('href', '/news/royal-canin-vs-pedigree-chon-hang-nao-cho-cho-cung');
    expect(first).toHaveTextContent('Dr. Minh Thu');
    expect(first).toHaveTextContent('28/07/2026');
    expect(within(section).getAllByRole('heading', { level: 3 })).toHaveLength(3);
    expect(within(section).getByRole('link', { name: /Xem Tất Cả/ })).toHaveAttribute('href', '/news');
  });

  it('dịch vụ (OurServicesStage): nhóm có dữ liệu thành các bước, giá lấy từ servicePriceLabel', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { level: 2, name: 'DỊCH VỤ CỦA CHÚNG TÔI' })).toBeInTheDocument();
    // Seed có đủ 3 nhóm; nhóm đầu là Khám & tiêm chủng với dịch vụ rẻ nhất "Tiêm phòng" (Từ 50.000 đ).
    expect(await screen.findByRole('heading', { level: 3, name: 'KHÁM & TIÊM PHÒNG' })).toBeInTheDocument();
    expect(screen.getByText('Tiêm phòng · Từ 50.000 đ')).toBeInTheDocument();
    // BR-CK-02: khám / tiêm / thẩm mỹ kèm ghi chú chi phí phụ thuộc chỉ định của bác sĩ.
    expect(screen.getByText('Chi phí thực tế phụ thuộc chỉ định của bác sĩ')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /^Dịch vụ / })).toHaveLength(3);
    const stage = screen.getByRole('heading', { level: 2, name: 'DỊCH VỤ CỦA CHÚNG TÔI' }).closest('section')!;
    expect(within(stage).getByRole('link', { name: /ĐẶT LỊCH NGAY/ })).toHaveAttribute('href', '/booking');
    // Giá cứng cũ không còn.
    expect(document.body).not.toHaveTextContent(/120\.000đ|350\.000đ|500\.000đ/);
  });

  it('chi nhánh (ContactVetCTA): tên, số điện thoại gọi được, cờ cấp cứu, link bản đồ', async () => {
    renderPage();
    const maps = await screen.findByRole('link', { name: /PetCare Cầu Giấy/ });
    expect(maps.getAttribute('href')).toMatch(/^https:\/\/www\.google\.com\/maps\//);
    expect(maps).toHaveAttribute('rel', 'noopener noreferrer');
    const chip = maps.closest('li')!;
    expect(within(chip).getByRole('link', { name: /0901234567/ })).toHaveAttribute('href', 'tel:0901234567');
    expect(chip).toHaveTextContent('Cấp cứu 24/7');
    expect(document.body).not.toHaveTextContent(/PetCare Quận 1|PetCare Quận 7/);
  });

  it('đang tải: sản phẩm, bác sĩ, tin tức có skeleton đọc được, chưa có thẻ giả', async () => {
    renderPage();
    expect(screen.getByRole('status', { name: 'Đang tải sản phẩm' })).toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Đang tải bác sĩ' })).toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Đang tải bài viết' })).toBeInTheDocument();
    await screen.findByRole('link', { name: /Hạt Royal Canin/ });
    expect(screen.queryByRole('status', { name: 'Đang tải sản phẩm' })).not.toBeInTheDocument();
  });
});

describe('HomePage: không có dữ liệu hoặc lỗi thì ẩn gọn, không placeholder giả', () => {
  const absent = [
    'Sản Phẩm Nổi Bật',
    /Bác Sĩ Chuyên Môn/,
    'Bài Viết Mới Nhất',
    'DỊCH VỤ CỦA CHÚNG TÔI',
  ];

  it('API trả rỗng: các section dữ liệu biến mất, phần tĩnh vẫn còn', async () => {
    vi.spyOn(catalogApi, 'listPublicProducts').mockResolvedValue(emptyPage);
    vi.spyOn(contentApi, 'listPublicArticles').mockResolvedValue(emptyPage);
    vi.spyOn(identityApi, 'listPublicVets').mockResolvedValue([]);
    vi.spyOn(catalogApi, 'listPublicServices').mockResolvedValue([]);
    vi.spyOn(branchApi, 'listPublicBranches').mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText('Câu hỏi thường gặp')).toBeInTheDocument();
    await vi.waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument());
    for (const name of absent) expect(screen.queryByRole('heading', { name })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Xem tất cả sản phẩm/ })).not.toBeInTheDocument();
    // Không chi nhánh: khung liên hệ vẫn có nút đặt lịch, không chip nào.
    const cta = screen.getByRole('heading', { level: 2, name: /BẠN CẦN TƯ VẤN/ }).closest('section')!;
    expect(within(cta).getByRole('link', { name: /ĐẶT LỊCH NGAY/ })).toHaveAttribute('href', '/booking');
    expect(within(cta).queryByRole('list')).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('Cấp cứu 24/7');
  });

  it('API lỗi: cùng kết quả, không văng lỗi ra trang chủ', async () => {
    vi.spyOn(catalogApi, 'listPublicProducts').mockRejectedValue(gone());
    vi.spyOn(contentApi, 'listPublicArticles').mockRejectedValue(gone());
    vi.spyOn(identityApi, 'listPublicVets').mockRejectedValue(gone());
    vi.spyOn(catalogApi, 'listPublicServices').mockRejectedValue(gone());
    vi.spyOn(branchApi, 'listPublicBranches').mockRejectedValue(gone());
    renderPage();
    expect(await screen.findByText('Câu hỏi thường gặp')).toBeInTheDocument();
    await vi.waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument());
    for (const name of absent) expect(screen.queryByRole('heading', { name })).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('chỉ một nhóm dịch vụ: hiện một bước, không có chấm điều hướng', async () => {
    vi.spyOn(catalogApi, 'listPublicServices').mockResolvedValue([
      { serviceId: 3, name: 'Tắm & sấy', group: 'GROOMING', price: 120_000, priceIsFrom: true, branchIds: [1] },
    ]);
    renderPage();
    expect(await screen.findByRole('heading', { level: 3, name: 'SPA & CẮT TỈA LÔNG' })).toBeInTheDocument();
    expect(screen.getByText('Tắm & sấy · Từ 120.000 đ')).toBeInTheDocument();
    expect(screen.getByText('Chi phí thực tế phụ thuộc chỉ định của bác sĩ')).toBeInTheDocument();
    expect(screen.queryAllByRole('button', { name: /^Dịch vụ / })).toHaveLength(0);
  });

  it('chỉ có lưu trú: giá theo đêm, không có ghi chú chỉ định của bác sĩ, nút dẫn tới chi nhánh', async () => {
    vi.spyOn(catalogApi, 'listPublicServices').mockResolvedValue([
      { serviceId: 5, name: 'Chuồng chó nhỏ', group: 'BOARDING', price: 200_000, priceIsFrom: false, branchIds: [1] },
    ]);
    renderPage();
    expect(await screen.findByRole('heading', { level: 3, name: 'KHÁCH SẠN LƯU TRÚ' })).toBeInTheDocument();
    expect(screen.getByText('Chuồng chó nhỏ · 200.000 đ / đêm')).toBeInTheDocument();
    expect(screen.queryByText('Chi phí thực tế phụ thuộc chỉ định của bác sĩ')).not.toBeInTheDocument();
    const stage = screen.getByRole('heading', { level: 2, name: 'DỊCH VỤ CỦA CHÚNG TÔI' }).closest('section')!;
    expect(within(stage).getByRole('link', { name: /LIÊN HỆ CHI NHÁNH/ })).toHaveAttribute('href', '/branches');
  });
});

describe('HomePage: hero, dải số liệu và FAQ đọc nội dung trang (UC21, T47)', () => {
  const statsBand = () => screen.getByText('Sự tin tưởng của bạn là ưu tiên của chúng tôi').closest('section')!;
  const faqSection = () => screen.getByText('Câu hỏi thường gặp').closest('section')!;
  const heroTitle = () => screen.getByRole('heading', { level: 1 });
  const stat = (value: string, label: string) => ({ value, label });
  const bannerWith = (stats: ReturnType<typeof stat>[]) => ({ ...PAGE_DEFAULTS.HOME_BANNER, headline: 'Mèo\nChó', stats });
  const NEW_STATS = [stat('20K+', 'Bé được chăm sóc'), stat('30+', 'Bác sĩ nội trú'), stat('48h', 'Trả kết quả'), stat('5/5', 'Khách hài lòng')];

  /** Ghi thẳng state mock như admin đã sửa (mock đọc lại state ở mọi request). */
  function setPages(pages: Partial<Record<PageKey, unknown>>) {
    const state = Object.fromEntries(
      Object.entries(pages).map(([key, content]) => [key, { key, title: null, content, updatedAt: '2026-10-20T08:00:00+07:00' }]),
    );
    localStorage.setItem(PAGES_STATE_KEY, JSON.stringify(state));
  }

  /** Giữ request nội dung trang lại tới khi test trả lời từng khóa. */
  function hold() {
    const waiting = new Map<PageKey, (content: unknown) => void>();
    vi.spyOn(pageContentApi, 'getPublic').mockImplementation(
      (key) =>
        new Promise<PageContent>((resolve) =>
          waiting.set(key, (content) => resolve({ key, title: null, content: content as PageContent['content'], updatedAt: '2026-10-20T08:00:00+07:00' })),
        ),
    );
    return waiting;
  }

  it('BE chưa có nội dung ({}): hero, dải số liệu và FAQ hiện đúng chữ mặc định (chữ tĩnh trước UC21)', async () => {
    renderPage();
    expect(await within(statsBand()).findByText('Thú cưng được chăm sóc')).toBeInTheDocument();
    expect(within(statsBand()).getAllByText(/^(10K\+|500\+|24h|4\.9\/5)$/)).toHaveLength(4);
    expect(heroTitle().textContent).toBe('Pet\nCare');
    const buttons = await within(faqSection()).findAllByRole('button');
    expect(buttons).toHaveLength(4);
    expect(buttons[0]).toHaveTextContent('PetCare hoạt động ở khu vực nào?');
    expect(buttons[0]).toHaveAttribute('aria-expanded', 'true');
    expect(faqSection()).toHaveTextContent('Hiện chúng tôi phục vụ tại các thành phố lớn');
    await screen.findByRole('heading', { level: 2, name: 'Sản Phẩm Nổi Bật' });
  });

  it('nội dung đã sửa: hero, số liệu, FAQ đổi theo và chữ mặc định không còn; bấm mở / đóng từng câu, xuống dòng giữ nguyên', async () => {
    setPages({
      HOME_BANNER: bannerWith(NEW_STATS),
      FAQ: { items: [{ question: 'Hỏi một?', answer: 'Đáp một.\nDòng hai.' }, { question: 'Hỏi hai?', answer: 'Đáp hai.' }] },
    });
    const user = userEvent.setup();
    renderPage();
    const band = statsBand();
    expect(await within(band).findByText('Bé được chăm sóc')).toBeInTheDocument();
    expect(within(band).getByText('20K+')).toBeInTheDocument();
    expect(band).not.toHaveTextContent('Thú cưng được chăm sóc');
    await waitFor(() => expect(heroTitle().textContent).toBe('Mèo\nChó'));

    const faq = faqSection();
    const buttons = await within(faq).findAllByRole('button');
    expect(buttons.map((b) => b.textContent)).toEqual(['Hỏi một?', 'Hỏi hai?']);
    expect(faq).not.toHaveTextContent('PetCare hoạt động ở khu vực nào?');
    expect(within(faq).getByText('Đáp một.', { exact: false }).textContent).toBe('Đáp một.\nDòng hai.');
    await user.click(buttons[1]);
    expect(buttons[1]).toHaveAttribute('aria-expanded', 'true');
    expect(buttons[0]).toHaveAttribute('aria-expanded', 'false');
    await user.click(buttons[1]);
    expect(buttons[1]).toHaveAttribute('aria-expanded', 'false');
  });

  it('sai dạng (số ô cố định sai, FAQ rỗng): về chữ mặc định, không trống', async () => {
    setPages({ HOME_BANNER: bannerWith(NEW_STATS.slice(0, 3)), FAQ: { items: [] } });
    renderPage();
    expect(await within(statsBand()).findByText('Thú cưng được chăm sóc')).toBeInTheDocument();
    expect(statsBand()).not.toHaveTextContent('Bé được chăm sóc');
    expect(await within(faqSection()).findAllByRole('button')).toHaveLength(4);
    expect(heroTitle().textContent).toBe('Pet\nCare');
  });

  it('request nội dung trang lỗi: vẫn hiện chữ mặc định, skeleton không kẹt, không văng lỗi', async () => {
    vi.spyOn(pageContentApi, 'getPublic').mockRejectedValue(gone());
    renderPage();
    expect(await within(statsBand()).findByText('Thú cưng được chăm sóc')).toBeInTheDocument();
    expect(await within(faqSection()).findAllByRole('button')).toHaveLength(4);
    expect(heroTitle().textContent).toBe('Pet\nCare');
    expect(screen.queryByRole('status', { name: 'Đang tải số liệu' })).not.toBeInTheDocument();
    expect(screen.queryByRole('status', { name: 'Đang tải câu hỏi' })).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('đang tải: dải số liệu và FAQ là skeleton, chưa lộ chữ mặc định; hero giữ chỗ (opacity-0); từng khóa hiện khi nó về', async () => {
    const waiting = hold();
    renderPage();
    expect(screen.getByRole('status', { name: 'Đang tải số liệu' })).toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Đang tải câu hỏi' })).toBeInTheDocument();
    expect(screen.queryByText('Thú cưng được chăm sóc')).not.toBeInTheDocument();
    expect(screen.queryByText('PetCare hoạt động ở khu vực nào?')).not.toBeInTheDocument();
    expect(heroTitle().closest('[class*="opacity-0"]')).not.toBeNull();

    await act(async () => waiting.get('FAQ')!({ items: [{ question: 'Hỏi một?', answer: 'Đáp một.' }] }));
    expect(await within(faqSection()).findByRole('button', { name: 'Hỏi một?' })).toBeInTheDocument();
    expect(screen.queryByRole('status', { name: 'Đang tải câu hỏi' })).not.toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Đang tải số liệu' })).toBeInTheDocument();

    await act(async () => waiting.get('HOME_BANNER')!(bannerWith(NEW_STATS)));
    expect(await within(statsBand()).findByText('Bé được chăm sóc')).toBeInTheDocument();
    await waitFor(() => expect(heroTitle().closest('[class*="opacity-0"]')).toBeNull());
    expect(heroTitle().textContent).toBe('Mèo\nChó');
  });

  it('chỉ văn bản thuần: thẻ HTML gõ vào số liệu / FAQ hiện thành chữ, không thành phần tử (ADR-0071)', async () => {
    setPages({
      HOME_BANNER: bannerWith([stat('<b>1</b>', '<i>x</i>'), ...NEW_STATS.slice(1)]),
      FAQ: { items: [{ question: '<b id="q">Hỏi</b>?', answer: '<img id="a" src="x" onerror="alert(1)">' }] },
    });
    renderPage();
    expect(await within(statsBand()).findByText('<b>1</b>')).toBeInTheDocument();
    expect(within(statsBand()).getByText('<i>x</i>')).toBeInTheDocument();
    expect(statsBand().querySelector('b, i')).toBeNull();
    expect(await within(faqSection()).findByRole('button', { name: '<b id="q">Hỏi</b>?' })).toBeInTheDocument();
    expect(within(faqSection()).getByText('<img id="a" src="x" onerror="alert(1)">')).toBeInTheDocument();
    for (const id of ['q', 'a']) expect(document.getElementById(id)).toBeNull();
  });

  it('nội dung trang về (hoặc đổi) làm đổi chiều cao trang: đo lại ScrollTrigger sau khi render', async () => {
    const waiting = hold();
    const refresh = vi.spyOn(ScrollTrigger, 'refresh');
    renderPage();
    await screen.findByRole('heading', { level: 2, name: 'Sản Phẩm Nổi Bật' });
    await screen.findByRole('heading', { level: 2, name: /Bác Sĩ Chuyên Môn/ });
    await screen.findByRole('heading', { level: 2, name: 'Bài Viết Mới Nhất' });
    await new Promise((resolve) => setTimeout(resolve, 120)); // để các khung rAF của dữ liệu T46 chạy hết
    const before = refresh.mock.calls.length;

    await act(async () => waiting.get('HOME_BANNER')!(bannerWith(NEW_STATS)));
    await waitFor(() => expect(refresh.mock.calls.length).toBeGreaterThan(before));
    const afterBanner = refresh.mock.calls.length;
    await act(async () => waiting.get('FAQ')!({ items: [{ question: 'Hỏi một?', answer: 'Đáp một.' }] }));
    await waitFor(() => expect(refresh.mock.calls.length).toBeGreaterThan(afterBanner));
  });
});
