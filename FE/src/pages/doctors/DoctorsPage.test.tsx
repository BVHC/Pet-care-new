import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { identityApi } from '../../shared/api/identity.api';
import { installMockApi } from '../../shared/api/mock/mock-api';
import type { PublicVet } from '../../shared/types/public-site';
import { DoctorsPage } from './DoctorsPage';

// UC15, BR-TK-20: hồ sơ công khai của bác sĩ (ảnh, chuyên môn, mô tả, chi nhánh), không có email / SĐT.
// Seed: 4 bác sĩ ở PetCare Cầu Giấy (BS. Minh Anh đầu tiên), đều có ảnh, chuyên môn và mô tả. Lỗi giả dùng 4xx vì hook công khai không thử lại 4xx.

beforeAll(() => installMockApi(apiClient));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/doctors']}>
        <DoctorsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { user: userEvent.setup(), ...view };
}

const vet = (accountId: number, fullName: string, branchId: number, branchName: string, extra: Partial<PublicVet> = {}): PublicVet => ({
  accountId,
  fullName,
  branchId,
  branchName,
  ...extra,
});

const gone = () => new ApiError('ACCESS_DENIED', 'Từ chối', 403);

describe('DoctorsPage (UC15)', () => {
  it('hiện bác sĩ seed: tên, ảnh, chuyên môn, mô tả và chi nhánh', async () => {
    const { container } = renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Đội ngũ bác sĩ' })).toBeInTheDocument();
    expect(document.title).toBe('Bác sĩ | PetCare');

    const card = (await screen.findByRole('heading', { level: 3, name: 'BS. Minh Anh' })).closest('article')!;
    expect(within(card).getByText('PetCare Cầu Giấy')).toBeInTheDocument();
    expect(within(card).getByText('Nội khoa, da liễu thú y')).toBeInTheDocument();
    expect(within(card).getByText(/^Hơn 8 năm khám và điều trị chó mèo/)).toBeInTheDocument();
    expect(card.querySelector('img')).toHaveAttribute('src', '/imgs/team1.jpg');
    expect(within(card).queryByText('MA')).not.toBeInTheDocument();
    // Hợp đồng không trả email / SĐT của bác sĩ (BR-TK-20): trang không được hiện liên hệ cá nhân.
    expect(container.querySelector('a[href^="tel:"], a[href^="mailto:"]')).toBeNull();
    expect(screen.queryByRole('group', { name: 'Lọc theo chi nhánh' })).not.toBeInTheDocument();
  });

  it('chưa có chuyên môn và mô tả: ghi nhãn "Bác sĩ thú y", không để khoảng trống', async () => {
    vi.spyOn(identityApi, 'listPublicVets').mockResolvedValue([vet(9, 'BS. Quốc Bảo', 1, 'PetCare Cầu Giấy', { specialty: null, bio: null })]);
    renderPage();
    const card = (await screen.findByRole('heading', { level: 3, name: 'BS. Quốc Bảo' })).closest('article')!;
    expect(within(card).getByText('Bác sĩ thú y')).toBeInTheDocument();
    expect(card.querySelectorAll('p')).toHaveLength(2);
  });

  it('hiện chuyên môn và mô tả ngắn khi có; ảnh tải lỗi thì quay về chữ cái đầu, không còn thẻ ảnh vỡ', async () => {
    vi.spyOn(identityApi, 'listPublicVets').mockResolvedValue([
      vet(5, 'BS. Minh Anh', 1, 'PetCare Cầu Giấy', {
        avatarUrl: '/imgs/team1.jpg',
        specialty: 'Nội khoa chó mèo',
        bio: 'Hơn 8 năm khám và điều trị bệnh đường tiêu hóa ở thú cưng.',
      }),
    ]);
    renderPage();

    const card = (await screen.findByRole('heading', { level: 3, name: 'BS. Minh Anh' })).closest('article')!;
    expect(within(card).getByText('Nội khoa chó mèo')).toBeInTheDocument();
    expect(within(card).queryByText('Bác sĩ thú y')).not.toBeInTheDocument();
    expect(within(card).getByText('Hơn 8 năm khám và điều trị bệnh đường tiêu hóa ở thú cưng.')).toBeInTheDocument();

    const photo = card.querySelector('img')!;
    expect(photo).toHaveAttribute('src', '/imgs/team1.jpg');
    expect(within(card).queryByText('MA')).not.toBeInTheDocument();

    fireEvent.error(photo);
    expect(card.querySelector('img')).toBeNull();
    expect(within(card).getByText('MA')).toBeInTheDocument();
  });

  it('nhiều bác sĩ: mỗi người một thẻ; lọc theo chi nhánh ở client, không gọi API thêm', async () => {
    const list = vi.spyOn(identityApi, 'listPublicVets').mockResolvedValue([
      vet(5, 'BS. Minh Anh', 1, 'PetCare Cầu Giấy'),
      vet(9, 'BS. Quốc Bảo', 2, 'PetCare Hà Đông'),
      vet(11, 'BS. Thu Hà', 2, 'PetCare Hà Đông'),
    ]);
    const { user } = renderPage();

    const filter = await screen.findByRole('group', { name: 'Lọc theo chi nhánh' });
    expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(3);
    expect(within(filter).getByRole('button', { name: 'Tất cả chi nhánh' })).toHaveAttribute('aria-pressed', 'true');

    await user.click(within(filter).getByRole('button', { name: 'PetCare Hà Đông' }));
    expect(within(filter).getByRole('button', { name: 'PetCare Hà Đông' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(['BS. Quốc Bảo', 'BS. Thu Hà']);

    await user.click(within(filter).getByRole('button', { name: 'Tất cả chi nhánh' }));
    expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(3);
    expect(list).toHaveBeenCalledTimes(1);
  });

  it('chịu được nhiều bản ghi', async () => {
    vi.spyOn(identityApi, 'listPublicVets').mockResolvedValue(
      Array.from({ length: 9 }, (_, i) => vet(i + 1, `BS. Tên ${i + 1}`, (i % 3) + 1, `PetCare Chi nhánh ${(i % 3) + 1}`)),
    );
    renderPage();
    expect(await screen.findAllByRole('heading', { level: 3 })).toHaveLength(9);
    expect(within(screen.getByRole('group', { name: 'Lọc theo chi nhánh' })).getAllByRole('button')).toHaveLength(4);
  });

  it('đang tải: khung chờ có nhãn, chưa hiện rỗng hay lỗi', async () => {
    renderPage();
    expect(screen.getByRole('status', { name: 'Đang tải đội ngũ bác sĩ' })).toBeInTheDocument();
    expect(screen.queryByText(/chưa có hồ sơ/)).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await screen.findByRole('heading', { level: 3, name: 'BS. Minh Anh' });
    expect(screen.queryByRole('status', { name: 'Đang tải đội ngũ bác sĩ' })).not.toBeInTheDocument();
  });

  it('lỗi tải: thông báo kèm "Thử lại", bấm là tải lại và hiện dữ liệu', async () => {
    const failing = vi.spyOn(identityApi, 'listPublicVets').mockRejectedValue(gone());
    const { user } = renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('Không tải được danh sách bác sĩ');

    failing.mockRestore();
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByRole('heading', { level: 3, name: 'BS. Minh Anh' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('chưa có bác sĩ nào công khai: câu rỗng có nghĩa và dẫn tới trang chi nhánh', async () => {
    vi.spyOn(identityApi, 'listPublicVets').mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText('Hiện chưa có hồ sơ bác sĩ nào được công bố.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Xem chi nhánh để gọi hỏi' })).toHaveAttribute('href', '/branches');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
