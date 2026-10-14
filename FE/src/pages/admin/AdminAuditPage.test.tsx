import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminAuditPage } from './AdminAuditPage';

beforeAll(() => installMockApi(apiClient));

const signIn = async (email: string) => {
  localStorage.clear();
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/audit']}>
        <AdminAuditPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminAuditPage (T22 - UC11)', () => {
  describe('ADMIN view (admin@petcare.vn)', () => {
    beforeEach(() => signIn('admin@petcare.vn'));

    it('renders audit log page and table', async () => {
      renderPage();
      expect(await screen.findByRole('heading', { name: 'Nhật ký kiểm toán' })).toBeInTheDocument();
      expect(screen.getByLabelText('Mã người thực hiện')).toBeInTheDocument();
      expect(screen.getByPlaceholderText('VD: STAFF_')).toBeInTheDocument();
    });

    it('filters audit entries', async () => {
      const user = userEvent.setup();
      renderPage();
      await screen.findByRole('heading', { name: 'Nhật ký kiểm toán' });

      const actorInput = screen.getByLabelText('Mã người thực hiện');
      await user.type(actorInput, '1');

      // Filter input is updated
      expect(actorInput).toHaveValue(1);
    });
  });

  describe('Non-admin view (chuoi@petcare.vn)', () => {
    beforeEach(() => signIn('chuoi@petcare.vn'));

    it('shows access denied message for non-admin', async () => {
      renderPage();
      expect(await screen.findByText('Chỉ quản trị viên mới xem được nhật ký kiểm toán.')).toBeInTheDocument();
    });
  });
});
