import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { branchApi } from '../../shared/api/branch.api';
import { catalogApi } from '../../shared/api/catalog.api';
import { installMockApi } from '../../shared/api/mock/mock-api';
import type { PublicBranch } from '../../shared/types/branch';
import type { PublicService } from '../../shared/types/catalog';
import { ServicesPage } from './ServicesPage';

// UC15, BR-CK-01/02: dịch vụ công khai theo nhóm, giá "Từ X đ" / "X đ / đêm", lọc theo chi nhánh phía client.
// Seed: một chi nhánh ACTIVE (PetCare Cầu Giấy, id 1) nên bộ lọc ẩn; ca lọc dùng dữ liệu hai chi nhánh bằng spy.
// Lỗi giả dùng mã 4xx vì hook công khai không thử lại 4xx (shouldRetry), nên lỗi hiện ngay thay vì sau ~7 giây thử lại.

beforeAll(() => installMockApi(apiClient));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/services']}>
        <ServicesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const branch = (branchId: number, name: string): PublicBranch => ({
  branchId,
  name,
  address: `${branchId} Phố Huế, Hà Nội`,
  phone: `09000000${branchId}`,
  latitude: 21,
  longitude: 105.8,
  acceptsAfterHoursEmergency: false,
  weeklyHours: [],
});

const service = (serviceId: number, name: string, group: PublicService['group'], price: number, extra: Partial<PublicService> = {}): PublicService => ({
  serviceId,
  name,
  group,
  price,
  priceIsFrom: false,
  description: null,
  kennelType: null,
  branchIds: [1],
  ...extra,
});

const twoBranches = [branch(1, 'PetCare Cầu Giấy'), branch(2, 'PetCare Hà Đông')];
const mixedServices = [
  service(1, 'Khám tổng quát', 'MEDICAL', 150_000, { branchIds: [1, 2] }),
  service(2, 'Tiêm phòng', 'MEDICAL', 50_000, { priceIsFrom: true, branchIds: [1] }),
  service(3, 'Tắm & sấy', 'GROOMING', 120_000, { priceIsFrom: true, branchIds: [2] }),
];

describe('ServicesPage (UC15)', () => {
  it('hiện dịch vụ theo nhóm với giá đúng BR-CK-02 từ dữ liệu seed', async () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Dịch vụ tại PetCare' })).toBeInTheDocument();
    expect(document.title).toBe('Dịch vụ | PetCare');

    const medical = await screen.findByRole('region', { name: 'Khám & tiêm chủng' });
    expect(within(medical).getByRole('heading', { level: 3, name: 'Khám tổng quát' })).toBeInTheDocument();
    expect(within(medical).getByText('150.000 đ')).toBeInTheDocument();
    expect(within(medical).getByText('Từ 50.000 đ')).toBeInTheDocument();
    expect(within(medical).getAllByText('PetCare Cầu Giấy').length).toBeGreaterThan(0);

    const grooming = screen.getByRole('region', { name: 'Thẩm mỹ' });
    expect(within(grooming).getByText('Từ 120.000 đ')).toBeInTheDocument();
    expect(within(grooming).getByText('Từ 250.000 đ')).toBeInTheDocument();
  });

  it('lưu trú tính giá theo đêm, kèm loại chuồng (loài và cân nặng tối đa)', async () => {
    renderPage();
    const boarding = await screen.findByRole('region', { name: 'Lưu trú' });
    expect(within(boarding).getByRole('heading', { level: 3, name: 'Chuồng chó nhỏ' })).toBeInTheDocument();
    expect(within(boarding).getByText('200.000 đ / đêm')).toBeInTheDocument();
    expect(within(boarding).getByText('Dành cho chó, tối đa 10 kg')).toBeInTheDocument();
  });

  it('ghi chú chi phí phụ thuộc chỉ định của bác sĩ chỉ ở nhóm khám/tiêm và thẩm mỹ (BR-CK-02)', async () => {
    renderPage();
    const note = /Chi phí thực tế phụ thuộc chỉ định của bác sĩ/;
    expect(within(await screen.findByRole('region', { name: 'Khám & tiêm chủng' })).getByText(note)).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Thẩm mỹ' })).getByText(note)).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Lưu trú' })).queryByText(note)).not.toBeInTheDocument();
  });

  it('"Đặt lịch" dẫn tới /booking cho khám và thẩm mỹ; lưu trú không đặt qua lịch hẹn nên chỉ dẫn xem chi nhánh', async () => {
    renderPage();
    const medical = await screen.findByRole('region', { name: 'Khám & tiêm chủng' });
    expect(within(medical).getByRole('link', { name: 'Đặt lịch Khám tổng quát' })).toHaveAttribute('href', '/booking');
    expect(within(screen.getByRole('region', { name: 'Thẩm mỹ' })).getByRole('link', { name: 'Đặt lịch Tắm & sấy' })).toHaveAttribute('href', '/booking');

    const boarding = screen.getByRole('region', { name: 'Lưu trú' });
    expect(within(boarding).queryByRole('link', { name: /Đặt lịch/ })).not.toBeInTheDocument();
    expect(within(boarding).getByRole('link', { name: 'Xem số điện thoại chi nhánh' })).toHaveAttribute('href', '/branches');
    // BR-CK-03 / T57: trang công khai không có nút mua.
    expect(screen.queryByRole('button', { name: /mua|giỏ hàng/i })).not.toBeInTheDocument();
  });

  it('ẩn bộ lọc chi nhánh khi chỉ có một chi nhánh', async () => {
    renderPage();
    await screen.findByRole('region', { name: 'Khám & tiêm chủng' });
    expect(screen.queryByRole('group', { name: 'Lọc theo chi nhánh' })).not.toBeInTheDocument();
  });

  it('lọc theo chi nhánh ngay ở client (không gọi API thêm), nhóm rỗng ẩn đi', async () => {
    vi.spyOn(branchApi, 'listPublicBranches').mockResolvedValue(twoBranches);
    const list = vi.spyOn(catalogApi, 'listPublicServices').mockResolvedValue(mixedServices);
    const user = renderPage();

    const filter = await screen.findByRole('group', { name: 'Lọc theo chi nhánh' });
    expect(within(filter).getByRole('button', { name: 'Tất cả chi nhánh' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('heading', { name: 'Tiêm phòng' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Tắm & sấy' })).toBeInTheDocument();

    await user.click(within(filter).getByRole('button', { name: 'PetCare Hà Đông' }));
    expect(within(filter).getByRole('button', { name: 'PetCare Hà Đông' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('heading', { name: 'Khám tổng quát' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Tắm & sấy' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Tiêm phòng' })).not.toBeInTheDocument();

    await user.click(within(filter).getByRole('button', { name: 'PetCare Cầu Giấy' }));
    expect(screen.queryByRole('heading', { name: 'Tắm & sấy' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Thẩm mỹ' })).not.toBeInTheDocument();
    expect(list).toHaveBeenCalledTimes(1);
  });

  it('chi nhánh chưa bật dịch vụ nào: báo rõ và cho quay về tất cả chi nhánh', async () => {
    vi.spyOn(branchApi, 'listPublicBranches').mockResolvedValue([...twoBranches, branch(3, 'PetCare Long Biên')]);
    vi.spyOn(catalogApi, 'listPublicServices').mockResolvedValue(mixedServices);
    const user = renderPage();

    await user.click(await screen.findByRole('button', { name: 'PetCare Long Biên' }));
    expect(screen.getByText('Chi nhánh này chưa có dịch vụ nào.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Xem tất cả chi nhánh' }));
    expect(screen.getByRole('heading', { name: 'Tiêm phòng' })).toBeInTheDocument();
  });

  it('đang tải: hiện khung chờ có nhãn, chưa hiện trạng thái rỗng hay lỗi', async () => {
    renderPage();
    expect(screen.getByRole('status', { name: 'Đang tải dịch vụ' })).toBeInTheDocument();
    expect(screen.queryByText(/Chưa có dịch vụ/)).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await screen.findByRole('region', { name: 'Khám & tiêm chủng' });
    expect(screen.queryByRole('status', { name: 'Đang tải dịch vụ' })).not.toBeInTheDocument();
  });

  it('lỗi tải: thông báo kèm "Thử lại", bấm là tải lại và hiện dữ liệu', async () => {
    const failing = vi.spyOn(catalogApi, 'listPublicServices').mockRejectedValue(new ApiError('ACCESS_DENIED', 'Từ chối', 403));
    const user = renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('Không tải được danh sách dịch vụ');
    expect(screen.queryByRole('region', { name: 'Khám & tiêm chủng' })).not.toBeInTheDocument();

    failing.mockRestore();
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByRole('region', { name: 'Khám & tiêm chủng' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('lỗi tải chi nhánh cũng hiện thông báo lỗi có "Thử lại"', async () => {
    vi.spyOn(branchApi, 'listPublicBranches').mockRejectedValue(new ApiError('ACCESS_DENIED', 'Từ chối', 403));
    renderPage();
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Thử lại' })).toBeInTheDocument();
  });

  it('chưa có dịch vụ nào: câu rỗng có nghĩa và dẫn tới trang chi nhánh', async () => {
    vi.spyOn(catalogApi, 'listPublicServices').mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText('Hiện chưa có dịch vụ nào được công bố.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Xem chi nhánh để gọi hỏi' })).toHaveAttribute('href', '/branches');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
