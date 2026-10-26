import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { branchApi } from '../../shared/api/branch.api';
import { catalogApi } from '../../shared/api/catalog.api';
import { contentApi } from '../../shared/api/content.api';
import { identityApi } from '../../shared/api/identity.api';
import { installMockApi } from '../../shared/api/mock/mock-api';
import type { PublicVet } from '../../shared/types/public-site';
import { HomePage } from './HomePage';

// Trang chủ đọc dữ liệu thật (T46): sản phẩm, bác sĩ, tin tức, dịch vụ (OurServicesStage), chi nhánh (ContactVetCTA). Seed: 5 sản phẩm
// (xếp theo tên), 1 bác sĩ BS. Minh Anh ở PetCare Cầu Giấy, 8 bài viết, 5 dịch vụ ở 3 nhóm, 1 chi nhánh nhận cấp cứu ngoài giờ.
// STATS và FAQS vẫn tĩnh (UC21, T47). Lỗi giả dùng 403 vì hook công khai không thử lại 4xx.

beforeAll(() => installMockApi(apiClient));
// ScrollTrigger bật setInterval 250ms ở lần nạp đầu; interval sống qua lúc jsdom bị hủy và gọi requestAnimationFrame (đã mất) ở file test khác cùng worker.
afterAll(() => ScrollTrigger.killAll());

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

  it('bác sĩ: hồ sơ thật (chữ cái đầu thay ảnh, chuyên môn, chi nhánh), không còn tên bác sĩ giả', async () => {
    renderPage();
    const section = (await screen.findByRole('heading', { level: 2, name: /Bác Sĩ Chuyên Môn/ })).closest('section')!;
    expect(await within(section).findByRole('heading', { level: 3, name: 'BS. Minh Anh' })).toBeInTheDocument();
    expect(section).toHaveTextContent('Nội khoa, da liễu thú y');
    expect(section).toHaveTextContent('PetCare Cầu Giấy');
    expect(within(section).getByText('MA')).toBeInTheDocument();
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

  it('STATS và FAQ vẫn tĩnh (UC21, T47)', async () => {
    renderPage();
    expect(screen.getByText('Câu hỏi thường gặp')).toBeInTheDocument();
    expect(screen.getByText('Thú cưng được chăm sóc')).toBeInTheDocument();
    await screen.findByRole('heading', { level: 2, name: 'Sản Phẩm Nổi Bật' });
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
