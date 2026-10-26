import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { branchApi } from '../../shared/api/branch.api';
import { identityApi } from '../../shared/api/identity.api';
import { installMockApi } from '../../shared/api/mock/mock-api';
import type { DayHours, PublicBranch } from '../../shared/types/branch';
import type { PublicVet } from '../../shared/types/public-site';
import { BranchesPage } from './BranchesPage';

// UC15, BR-CK-01, BR-CN-05: chi nhánh ACTIVE kèm SĐT (tel:), cờ cấp cứu, giờ làm việc theo tuần, bác sĩ của chi nhánh.
// Seed: PetCare Cầu Giấy (id 1, nhận cấp cứu ngoài giờ, mở 08:00-12:00 và 13:30-19:00, Chủ nhật chỉ buổi sáng), bác sĩ BS. Minh Anh.
// Không tính "hôm nay" / "đang mở" nên kết quả không phụ thuộc múi giờ máy. Lỗi giả dùng 4xx vì hook công khai không thử lại 4xx.

beforeAll(() => installMockApi(apiClient));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/branches']}>
        <BranchesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const week = (sunday: DayHours['ranges']): DayHours[] =>
  [1, 2, 3, 4, 5, 6, 7].map((dayOfWeek) => ({
    dayOfWeek,
    ranges: dayOfWeek === 7 ? sunday : [{ open: '08:00', close: '17:00' }],
  }));

const branch = (branchId: number, name: string, extra: Partial<PublicBranch> = {}): PublicBranch => ({
  branchId,
  name,
  address: `${branchId} Phố Huế, Hà Nội`,
  phone: `090000000${branchId}`,
  latitude: 21.0128,
  longitude: 105.8031,
  acceptsAfterHoursEmergency: false,
  weeklyHours: week([]),
  ...extra,
});

const vet = (accountId: number, fullName: string, branchId: number, branchName: string, extra: Partial<PublicVet> = {}): PublicVet => ({
  accountId,
  fullName,
  branchId,
  branchName,
  ...extra,
});

const gone = () => new ApiError('ACCESS_DENIED', 'Từ chối', 403);

describe('BranchesPage (UC15)', () => {
  it('hiện chi nhánh seed: địa chỉ, số điện thoại gọi được, cờ cấp cứu, giờ làm việc theo tuần', async () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Hệ thống chi nhánh' })).toBeInTheDocument();
    expect(document.title).toBe('Chi nhánh | PetCare');

    const card = await screen.findByRole('article', { name: 'PetCare Cầu Giấy' });
    expect(within(card).getByText('123 Trần Duy Hưng, Cầu Giấy, Hà Nội')).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: '0901234567' })).toHaveAttribute('href', 'tel:0901234567');
    expect(within(card).getByText('Cấp cứu 24/7')).toBeInTheDocument();

    const hours = within(card).getByRole('table', { name: 'Giờ làm việc của PetCare Cầu Giấy' });
    expect(within(hours).getAllByRole('row')).toHaveLength(7);
    expect(within(within(hours).getByRole('row', { name: /Thứ Hai/ })).getByText('08:00 - 12:00 và 13:30 - 19:00')).toBeInTheDocument();
    expect(within(within(hours).getByRole('row', { name: /Chủ nhật/ })).getByText('08:00 - 12:00')).toBeInTheDocument();
  });

  it('link bản đồ từ vĩ độ, kinh độ, mở tab mới an toàn', async () => {
    renderPage();
    const link = await screen.findByRole('link', { name: 'Xem bản đồ PetCare Cầu Giấy (mở tab mới)' });
    expect(link).toHaveAttribute('href', 'https://www.google.com/maps/search/?api=1&query=21.0128,105.8031');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link.getAttribute('rel')).toContain('noopener');
    expect(link.getAttribute('rel')).toContain('noreferrer');
  });

  it('không có tọa độ thì không hiện link bản đồ', async () => {
    // Hợp đồng luôn trả số; chỉ phòng dữ liệu hỏng (null) để không sinh link "null,null".
    const broken = { latitude: null, longitude: null } as unknown as Partial<PublicBranch>;
    vi.spyOn(branchApi, 'listPublicBranches').mockResolvedValue([branch(1, 'PetCare Cầu Giấy', broken)]);
    renderPage();
    await screen.findByRole('article', { name: 'PetCare Cầu Giấy' });
    expect(screen.queryByRole('link', { name: /Xem bản đồ/ })).not.toBeInTheDocument();
  });

  it('liệt kê bác sĩ của chi nhánh và dẫn sang trang đội ngũ bác sĩ', async () => {
    renderPage();
    const card = await screen.findByRole('article', { name: 'PetCare Cầu Giấy' });
    expect(await within(card).findByText('BS. Minh Anh')).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: 'Xem đội ngũ bác sĩ' })).toHaveAttribute('href', '/doctors');
  });

  it('nhiều chi nhánh: cờ cấp cứu, ngày nghỉ và bác sĩ đúng từng chi nhánh, bác sĩ chỉ tải một lần', async () => {
    vi.spyOn(branchApi, 'listPublicBranches').mockResolvedValue([
      branch(1, 'PetCare Cầu Giấy', { acceptsAfterHoursEmergency: true }),
      branch(2, 'PetCare Hà Đông', { weeklyHours: week([]) }),
      branch(3, 'PetCare Long Biên', { weeklyHours: week([{ open: '09:00', close: '11:00' }]) }),
    ]);
    const vets = vi
      .spyOn(identityApi, 'listPublicVets')
      .mockResolvedValue([vet(5, 'BS. Minh Anh', 1, 'PetCare Cầu Giấy'), vet(9, 'BS. Quốc Bảo', 2, 'PetCare Hà Đông', { specialty: 'Ngoại khoa' })]);
    renderPage();

    const cau = await screen.findByRole('article', { name: 'PetCare Cầu Giấy' });
    const dong = screen.getByRole('article', { name: 'PetCare Hà Đông' });
    const long = screen.getByRole('article', { name: 'PetCare Long Biên' });

    expect(within(cau).getByText('Cấp cứu 24/7')).toBeInTheDocument();
    expect(within(dong).queryByText('Cấp cứu 24/7')).not.toBeInTheDocument();
    expect(within(long).queryByText('Cấp cứu 24/7')).not.toBeInTheDocument();

    expect(within(within(dong).getByRole('row', { name: /Chủ nhật/ })).getByText('Nghỉ')).toBeInTheDocument();
    expect(within(within(long).getByRole('row', { name: /Chủ nhật/ })).queryByText('Nghỉ')).not.toBeInTheDocument();

    expect(await within(dong).findByText('BS. Quốc Bảo')).toBeInTheDocument();
    expect(within(dong).getByText('Ngoại khoa')).toBeInTheDocument();
    expect(within(dong).queryByText('BS. Minh Anh')).not.toBeInTheDocument();
    expect(within(long).getByText('Chưa có bác sĩ nào được giới thiệu tại chi nhánh này.')).toBeInTheDocument();
    expect(within(long).queryByRole('link', { name: 'Xem đội ngũ bác sĩ' })).not.toBeInTheDocument();
    expect(vets).toHaveBeenCalledTimes(1);
  });

  it('chưa có bản giờ mở cửa thì báo chưa cập nhật, không vẽ bảng rỗng', async () => {
    vi.spyOn(branchApi, 'listPublicBranches').mockResolvedValue([branch(1, 'PetCare Cầu Giấy', { weeklyHours: [] })]);
    renderPage();
    const card = await screen.findByRole('article', { name: 'PetCare Cầu Giấy' });
    expect(within(card).getByText('Chưa cập nhật giờ làm việc.')).toBeInTheDocument();
    expect(within(card).queryByRole('table')).not.toBeInTheDocument();
  });

  it('đang tải: khung chờ có nhãn, chưa hiện rỗng hay lỗi', async () => {
    renderPage();
    expect(screen.getByRole('status', { name: 'Đang tải chi nhánh' })).toBeInTheDocument();
    expect(screen.queryByText(/chưa có chi nhánh/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await screen.findByRole('article', { name: 'PetCare Cầu Giấy' });
    expect(screen.queryByRole('status', { name: 'Đang tải chi nhánh' })).not.toBeInTheDocument();
  });

  it('lỗi tải chi nhánh: thông báo kèm "Thử lại", bấm là tải lại', async () => {
    const failing = vi.spyOn(branchApi, 'listPublicBranches').mockRejectedValue(gone());
    const user = renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('Không tải được danh sách chi nhánh');

    failing.mockRestore();
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByRole('article', { name: 'PetCare Cầu Giấy' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('lỗi tải bác sĩ chỉ báo trong thẻ, chi nhánh vẫn hiện và thử lại được', async () => {
    const failing = vi.spyOn(identityApi, 'listPublicVets').mockRejectedValue(gone());
    const user = renderPage();

    const card = await screen.findByRole('article', { name: 'PetCare Cầu Giấy' });
    expect(await within(card).findByText('Chưa tải được danh sách bác sĩ.')).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: '0901234567' })).toBeInTheDocument();

    failing.mockRestore();
    await user.click(within(card).getByRole('button', { name: 'Thử lại' }));
    expect(await within(card).findByText('BS. Minh Anh')).toBeInTheDocument();
  });

  it('không có chi nhánh ACTIVE: câu rỗng có nghĩa', async () => {
    vi.spyOn(branchApi, 'listPublicBranches').mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText('Hiện chưa có chi nhánh nào đang hoạt động.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
  });
});
