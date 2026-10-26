import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { catalogApi } from '../../shared/api/catalog.api';
import { identityApi } from '../../shared/api/identity.api';
import { installMockApi } from '../../shared/api/mock/mock-api';
import type { PublicVet } from '../../shared/types/public-site';
import { AboutPage } from './AboutPage';

// Giới thiệu đọc dữ liệu thật (T46): đội ngũ bác sĩ (UC15) và dịch vụ (UC15). STATS, VALUES, khối liên hệ vẫn tĩnh (UC21, T47).
// Seed: 1 bác sĩ BS. Minh Anh (chưa có ảnh) ở PetCare Cầu Giấy, 5 dịch vụ ở 3 nhóm. Lỗi giả dùng 403 vì hook công khai không thử lại 4xx.

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
  it('đội ngũ: bác sĩ thật với chữ cái đầu thay ảnh, chuyên môn và chi nhánh; không còn đội ngũ giả', async () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: /PetCare — Người bạn đồng hành/ })).toBeInTheDocument();
    const team = (await screen.findByRole('heading', { level: 2, name: 'Đội ngũ bác sĩ' })).closest('section')!;
    const card = (await within(team).findByRole('heading', { level: 3, name: 'BS. Minh Anh' })).closest('div')!;
    expect(card).toHaveTextContent('Nội khoa, da liễu thú y');
    expect(card).toHaveTextContent('PetCare Cầu Giấy');
    expect(within(card).getByText('MA')).toBeInTheDocument();
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

  it('STATS, VALUES và khối liên hệ vẫn tĩnh (UC21, T47)', () => {
    renderPage();
    expect(screen.getByText('Khách hàng tin tưởng')).toBeInTheDocument();
    expect(within(sectionOf('Giá trị cốt lõi')).getByRole('heading', { level: 3, name: 'Yêu thương' })).toBeInTheDocument();
    expect(sectionOf('Liên hệ với chúng tôi')).toHaveTextContent('1900 1234');
  });
});
