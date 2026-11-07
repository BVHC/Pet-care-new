import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { customerPetApi } from '../../domains/pets/customer-pet.api';
import { ApiError } from '../../shared/api/api-error';
import { aiApi } from '../../shared/api/ai.api';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { RecommendPage } from './RecommendPage';

// UC92. khach@petcare.vn có Mochi, Bim, Tôm; khachmoi@petcare.vn chưa có thú. Công thức điểm kiểm ở ai.mock.test.ts: ở đây chỉ kiểm cách hiện.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => useSession.getState().clear());
const login = (email: string) => useSession.getState().login({ email, password: MOCK_PASSWORD });

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <RecommendPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

describe('chưa đăng nhập hoặc không phải khách hàng', () => {
  it('khách vãng lai: lời mời đăng nhập, không gọi danh sách thú', async () => {
    const pets = vi.spyOn(customerPetApi, 'listMyPets');
    renderPage();
    expect(screen.getByRole('heading', { name: 'Gợi ý dành cho khách hàng của PetCare' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Đăng nhập' })).toHaveAttribute('href', '/auth/login');
    expect(pets).not.toHaveBeenCalled();
  });

  it('nhân viên (bác sĩ): nhắc dùng tài khoản khách, không gọi API của khách', async () => {
    await login('doctor@store1.vn');
    const pets = vi.spyOn(customerPetApi, 'listMyPets');
    renderPage();
    expect(screen.getByText(/Tài khoản nhân viên không có thú cưng/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Đăng nhập' })).not.toBeInTheDocument();
    expect(pets).not.toHaveBeenCalled();
  });
});

describe('khách hàng', () => {
  it('khách chưa có thú: hướng dẫn thêm thú cưng, không gọi API gợi ý', async () => {
    await login('khachmoi@petcare.vn');
    const reco = vi.spyOn(aiApi, 'getRecommendations');
    renderPage();
    expect(await screen.findByRole('heading', { name: 'Bạn chưa có thú cưng nào' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Thêm thú cưng' })).toHaveAttribute('href', '/pets');
    expect(reco).not.toHaveBeenCalled();
  });

  it('thú của mình ở thanh bên; thú đầu tiên được chọn sẵn và hiện gợi ý theo nhóm kèm lý do, giá', async () => {
    await login('khach@petcare.vn');
    renderPage();
    const sidebar = await screen.findByRole('complementary', { name: 'Thú cưng của bạn' });
    for (const name of ['Mochi', 'Bim', 'Tôm']) expect(within(sidebar).getByRole('button', { name: new RegExp(name) })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 1, name: /^Gợi ý cho / })).toBeInTheDocument();
    // Ít nhất một nhóm có thẻ gợi ý với lý do, điểm phù hợp và giá
    const group = await screen.findByRole('region', { name: 'Sản phẩm' });
    const card = within(group).getAllByRole('listitem')[0];
    expect(card).toHaveTextContent(/Phù hợp \d+%/);
    expect(card).toHaveTextContent(/\d[\d.]* đ/);
  });

  it('đổi thú thì tải gợi ý của thú đó (mỗi thú một lần gọi #4)', async () => {
    await login('khach@petcare.vn');
    const reco = vi.spyOn(aiApi, 'getRecommendations');
    const user = renderPage();
    const sidebar = await screen.findByRole('complementary', { name: 'Thú cưng của bạn' });
    await screen.findByRole('region', { name: 'Sản phẩm' });
    const firstId = reco.mock.calls[0][0];
    const buttons = within(sidebar).getAllByRole('button');
    await user.click(buttons[1]);
    await waitFor(() => expect(reco.mock.calls.length).toBeGreaterThanOrEqual(2));
    expect(reco.mock.calls.at(-1)![0]).not.toBe(firstId);
    expect(buttons[1]).toHaveAttribute('aria-pressed', 'true');
  });

  it('lỗi từ BE (404): báo lỗi ngay trong khung, có Thử lại và gọi lại được', async () => {
    await login('khach@petcare.vn');
    vi.spyOn(aiApi, 'getRecommendations').mockRejectedValueOnce(new ApiError('RESOURCE_NOT_FOUND', 'Không tìm thấy thú cưng', 404));
    const user = renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('Không tìm thấy thú cưng');
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByRole('region', { name: 'Sản phẩm' })).toBeInTheDocument();
  });
});
