import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import PetsPage from './PetsPage';

// khach@petcare.vn: Mochi (có bệnh án, hẹn tái khám 24/10/2026), Bim (có mũi tiêm), Tôm.

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'khach@petcare.vn', password: MOCK_PASSWORD });
});

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/pets']}>
        <PetsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

describe('PetsPage (UC24, UC25)', () => {
  it('liệt kê thú của tôi', async () => {
    renderPage();
    const list = await screen.findByRole('list', { name: 'Thú cưng của tôi' });
    for (const name of ['Mochi', 'Bim', 'Tôm']) expect(within(list).getByText(name)).toBeInTheDocument();
  });

  it('hồ sơ sức khỏe hiện chẩn đoán, đơn thuốc, lịch tái khám nhưng không có ghi chú nội bộ (BR-KH-07)', async () => {
    const user = renderPage();
    await user.click(await screen.findByText('Mochi'));
    const drawer = await screen.findByRole('dialog', { name: /Mochi/ });
    await user.click(within(drawer).getByRole('tab', { name: 'Sức khỏe' }));
    expect(await within(drawer).findByText('Viêm da dị ứng — tái khám')).toBeInTheDocument();
    expect(within(drawer).getByText('Tái khám ngày 24/10/2026')).toBeInTheDocument();
    expect(within(drawer).getAllByText(/Cetirizine 10mg/).length).toBeGreaterThan(0);
    expect(within(drawer).queryByText(/Chủ hay quên/)).not.toBeInTheDocument();
    expect(within(drawer).queryByText(/Cân nhắc đổi thức ăn/)).not.toBeInTheDocument();
  });

  it('chủ tự khai cân nặng: ≤ 0 bị chặn ở giao diện (BR-KH-04), giá trị hợp lệ thành lần đo mới nhất', async () => {
    const user = renderPage();
    await user.click(await screen.findByText('Bim'));
    const drawer = await screen.findByRole('dialog', { name: /Bim/ });
    await user.click(within(drawer).getByRole('tab', { name: 'Sức khỏe' }));
    const input = await within(drawer).findByLabelText('Cân nặng (kg)');
    await user.type(input, '0');
    await user.click(within(drawer).getByRole('button', { name: 'Ghi' }));
    expect(within(drawer).getByText('Cân nặng phải lớn hơn 0')).toBeInTheDocument();
    await user.clear(input);
    await user.type(input, '7,2');
    await user.click(within(drawer).getByRole('button', { name: 'Ghi' }));
    expect(await within(drawer).findByText('7.2 kg')).toBeInTheDocument();
  });
});
