import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { catalogApi } from '../../shared/api/catalog.api';
import { identityApi } from '../../shared/api/identity.api';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { PAGES_STATE_KEY } from '../../shared/api/mock/page-content.mock';
import { pageContentApi } from '../../shared/api/page-content.api';
import type { PageContent } from '../../shared/types/page-content';
import type { PublicVet } from '../../shared/types/public-site';
import { AboutPage } from './AboutPage';

// Giới thiệu đọc dữ liệu thật (T46): đội ngũ bác sĩ (UC15) và dịch vụ (UC15). Số liệu và giá trị cốt lõi đọc nội dung trang do quản trị sửa
// (UC21, T47); khối liên hệ vẫn tĩnh (không có khóa).
// Seed: 4 bác sĩ (BS. Minh Anh đầu tiên, có ảnh) ở PetCare Cầu Giấy, 5 dịch vụ ở 3 nhóm. Lỗi giả dùng 403 vì hook công khai không thử lại 4xx.

beforeAll(() => installMockApi(apiClient));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AboutPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const gone = () => new ApiError('ACCESS_DENIED', 'Từ chối', 403);
const sectionOf = (name: string) => screen.getByRole('heading', { level: 2, name }).closest('section')!;

const vet = (accountId: number, fullName: string, extra: Partial<PublicVet> = {}): PublicVet => ({
  accountId,
  fullName,
  branchId: 1,
  branchName: 'PetCare Cầu Giấy',
  ...extra,
});

describe('AboutPage (T46)', () => {
  it('đội ngũ: bác sĩ thật với ảnh, chuyên môn và chi nhánh; không còn đội ngũ giả', async () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: /PetCare — Người bạn đồng hành/ })).toBeInTheDocument();
    const team = (await screen.findByRole('heading', { level: 2, name: 'Đội ngũ bác sĩ' })).closest('section')!;
    const card = (await within(team).findByRole('heading', { level: 3, name: 'BS. Minh Anh' })).closest('div')!;
    expect(card).toHaveTextContent('Nội khoa, da liễu thú y');
    expect(card).toHaveTextContent('PetCare Cầu Giấy');
    expect(within(card).getByRole('img', { name: 'BS. Minh Anh' })).toHaveAttribute('src', '/imgs/team1.jpg');
    expect(team).not.toHaveTextContent(/Nguyễn Văn Minh|Ngô Thị Hương|Lê Minh Tuấn|năm kinh nghiệm/);
    expect(within(team).getByRole('link', { name: /Xem đội ngũ bác sĩ/ })).toHaveAttribute('href', '/doctors');
  });

  it('đội ngũ: ảnh thật hiện alt là tên; nhiều bác sĩ thì xếp lưới; chưa có chuyên môn ghi "Bác sĩ thú y"', async () => {
    vi.spyOn(identityApi, 'listPublicVets').mockResolvedValue([
      vet(1, 'BS. An', { avatarUrl: 'https://cdn.example.com/an.jpg', specialty: 'Phẫu thuật' }),
      vet(2, 'BS. Bình'),
      vet(3, 'BS. Chi'),
    ]);
    renderPage();
    const team = (await screen.findByRole('heading', { level: 2, name: 'Đội ngũ bác sĩ' })).closest('section')!;
    expect(await within(team).findAllByRole('heading', { level: 3 })).toHaveLength(3);
    expect(within(team).getByRole('img', { name: 'BS. An' })).toHaveAttribute('src', 'https://cdn.example.com/an.jpg');
    expect(within(team).getByText('Phẫu thuật')).toBeInTheDocument();
    expect(within(team).getAllByText('Bác sĩ thú y')).toHaveLength(2);
  });

  it('dịch vụ: mỗi nhóm có dữ liệu một thẻ liệt kê tên dịch vụ thật, link sang /services', async () => {
    renderPage();
    const services = (await screen.findByRole('heading', { level: 2, name: 'Dịch vụ của chúng tôi' })).closest('section')!;
    const medical = (await within(services).findByRole('heading', { level: 3, name: 'Khám & tiêm chủng' })).closest('div')!;
    expect(medical).toHaveTextContent('Khám tổng quát');
    expect(medical).toHaveTextContent('Tiêm phòng');
    expect(within(services).getByRole('heading', { level: 3, name: 'Thẩm mỹ' }).closest('div')).toHaveTextContent('Cắt tỉa lông');
    expect(within(services).getByRole('heading', { level: 3, name: 'Lưu trú' }).closest('div')).toHaveTextContent('Chuồng chó nhỏ');
    expect(services).not.toHaveTextContent(/1000\+ sản phẩm|Mua sắm thực phẩm/);
    expect(within(services).getByRole('link', { name: /Xem tất cả dịch vụ/ })).toHaveAttribute('href', '/services');
  });

  it('chưa có bác sĩ hoặc dịch vụ: section tương ứng ẩn, trang không lỗi', async () => {
    vi.spyOn(identityApi, 'listPublicVets').mockResolvedValue([]);
    vi.spyOn(catalogApi, 'listPublicServices').mockResolvedValue([]);
    renderPage();
    expect(await screen.findByRole('heading', { level: 2, name: 'Giá trị cốt lõi' })).toBeInTheDocument();
    await vi.waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument());
    expect(screen.queryByRole('heading', { level: 2, name: 'Đội ngũ bác sĩ' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 2, name: 'Dịch vụ của chúng tôi' })).not.toBeInTheDocument();
  });

  it('API lỗi: cùng kết quả, không văng lỗi', async () => {
    vi.spyOn(identityApi, 'listPublicVets').mockRejectedValue(gone());
    vi.spyOn(catalogApi, 'listPublicServices').mockRejectedValue(gone());
    renderPage();
    expect(await screen.findByRole('heading', { level: 2, name: 'Giá trị cốt lõi' })).toBeInTheDocument();
    await vi.waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument());
    expect(screen.queryByRole('heading', { level: 2, name: 'Đội ngũ bác sĩ' })).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('đang tải: skeleton đọc được cho đội ngũ và dịch vụ', async () => {
    renderPage();
    expect(screen.getByRole('status', { name: 'Đang tải đội ngũ bác sĩ' })).toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Đang tải dịch vụ' })).toBeInTheDocument();
    await screen.findByRole('heading', { level: 3, name: 'BS. Minh Anh' });
  });

  it('khối liên hệ vẫn tĩnh (không có khóa, ngoài UC21)', () => {
    renderPage();
    expect(sectionOf('Liên hệ với chúng tôi')).toHaveTextContent('1900 1234');
  });
});

describe('AboutPage: số liệu và giá trị cốt lõi đọc nội dung trang (UC21, T47, khóa ABOUT)', () => {
  const stat = (value: string, label: string) => ({ value, label });
  const value = (title: string, description: string) => ({ title, description });
  const NEW_STATS = [stat('99+', 'Bé được cứu'), stat('20+', 'Năm gắn bó'), stat('70+', 'Bác sĩ nội trú'), stat('5/5', 'Khách hài lòng')];
  const NEW_VALUES = [value('Tận tâm', 'Luôn lắng nghe.'), value('Minh bạch', 'Giá rõ ràng.\nKhông phát sinh.'), value('Nhanh', 'Có mặt trong 15 phút.'), value('Bền', 'Theo dõi sau điều trị.')];
  const statsOf = (anchor: string) => screen.getByText(anchor, { exact: false }).closest('section')!;

  /** Ghi thẳng state mock như admin đã sửa (mock đọc lại state ở mọi request). */
  function setAbout(content: unknown) {
    localStorage.setItem(PAGES_STATE_KEY, JSON.stringify({ ABOUT: { key: 'ABOUT', title: null, content, updatedAt: '2026-10-20T08:00:00+07:00' } }));
  }

  it('BE chưa có nội dung ({}): đúng chữ mặc định (chữ tĩnh trước UC21), mỗi giá trị một biểu tượng', async () => {
    renderPage();
    expect(await screen.findByText('Khách hàng tin tưởng')).toBeInTheDocument();
    const stats = statsOf('Khách hàng tin tưởng');
    expect(within(stats).getAllByText(/^(10K\+|15\+|50\+|4\.9\/5)$/)).toHaveLength(4);
    expect(stats).toHaveTextContent('Năm kinh nghiệm');
    const values = sectionOf('Giá trị cốt lõi');
    expect(await within(values).findAllByRole('heading', { level: 3 })).toHaveLength(4);
    expect(within(values).getByRole('heading', { level: 3, name: 'Yêu thương' })).toBeInTheDocument();
    expect(values.querySelectorAll('svg')).toHaveLength(4);
  });

  it('nội dung đã sửa: số liệu và giá trị đổi theo, chữ mặc định không còn, xuống dòng giữ nguyên', async () => {
    setAbout({ stats: NEW_STATS, values: NEW_VALUES });
    renderPage();
    expect(await screen.findByText('Bé được cứu')).toBeInTheDocument();
    expect(within(statsOf('Bé được cứu')).getByText('99+')).toBeInTheDocument();
    const values = sectionOf('Giá trị cốt lõi');
    expect((await within(values).findAllByRole('heading', { level: 3 })).map((h) => h.textContent)).toEqual(['Tận tâm', 'Minh bạch', 'Nhanh', 'Bền']);
    expect(within(values).getByText('Giá rõ ràng.', { exact: false }).textContent).toBe('Giá rõ ràng.\nKhông phát sinh.');
    expect(values.querySelectorAll('svg')).toHaveLength(4);
    expect(document.body).not.toHaveTextContent(/Khách hàng tin tưởng|Yêu thương|Năm kinh nghiệm/);
  });

  it('sai dạng (thiếu giá trị, số ô cố định sai, quá giới hạn): về chữ mặc định, không trống', async () => {
    setAbout({ stats: NEW_STATS.slice(0, 2), values: NEW_VALUES });
    renderPage();
    expect(await screen.findByText('Khách hàng tin tưởng')).toBeInTheDocument();
    expect(within(sectionOf('Giá trị cốt lõi')).getByRole('heading', { level: 3, name: 'Yêu thương' })).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('Bé được cứu');
  });

  it('request nội dung trang lỗi: vẫn hiện chữ mặc định, skeleton không kẹt, không văng lỗi', async () => {
    vi.spyOn(pageContentApi, 'getPublic').mockRejectedValue(gone());
    renderPage();
    expect(await screen.findByText('Khách hàng tin tưởng')).toBeInTheDocument();
    expect(await within(sectionOf('Giá trị cốt lõi')).findByRole('heading', { level: 3, name: 'Yêu thương' })).toBeInTheDocument();
    expect(screen.queryByRole('status', { name: 'Đang tải số liệu' })).not.toBeInTheDocument();
    expect(screen.queryByRole('status', { name: 'Đang tải giá trị cốt lõi' })).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('đang tải: skeleton đọc được, chưa lộ chữ mặc định để khỏi nháy; về rồi mới hiện chữ đã sửa', async () => {
    let answer!: (page: PageContent) => void;
    vi.spyOn(pageContentApi, 'getPublic').mockReturnValue(new Promise<PageContent>((resolve) => (answer = resolve)));
    renderPage();
    expect(screen.getByRole('status', { name: 'Đang tải số liệu' })).toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Đang tải giá trị cốt lõi' })).toBeInTheDocument();
    expect(screen.queryByText('Khách hàng tin tưởng')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 3, name: 'Yêu thương' })).not.toBeInTheDocument();

    await act(async () => answer({ key: 'ABOUT', title: null, content: { stats: NEW_STATS, values: NEW_VALUES }, updatedAt: '2026-10-20T08:00:00+07:00' }));
    expect(await screen.findByText('Bé được cứu')).toBeInTheDocument();
    expect(screen.queryByRole('status', { name: 'Đang tải số liệu' })).not.toBeInTheDocument();
  });

  it('chỉ văn bản thuần: thẻ HTML gõ vào hiện thành chữ, không thành phần tử (ADR-0071)', async () => {
    setAbout({
      stats: [stat('<b>1</b>', '<i>x</i>'), ...NEW_STATS.slice(1)],
      values: [value('<b id="t">Tận tâm</b>', '<img id="d" src="x" onerror="alert(1)">'), ...NEW_VALUES.slice(1)],
    });
    renderPage();
    expect(await screen.findByText('<b>1</b>')).toBeInTheDocument();
    expect(screen.getByText('<i>x</i>')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 3, name: '<b id="t">Tận tâm</b>' })).toBeInTheDocument();
    expect(screen.getByText('<img id="d" src="x" onerror="alert(1)">')).toBeInTheDocument();
    expect(statsOf('<i>x</i>').querySelector('b, i')).toBeNull();
    for (const id of ['t', 'd']) expect(document.getElementById(id)).toBeNull();
  });
});
