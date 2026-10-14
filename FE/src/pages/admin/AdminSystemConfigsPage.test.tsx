import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminSystemConfigsPage } from './AdminSystemConfigsPage';

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
      <MemoryRouter initialEntries={['/admin/system-configs']}>
        <AdminSystemConfigsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const rowOf = async (text: string) => (await screen.findByText(text)).closest('tr')!;

describe('AdminSystemConfigsPage (T22 - UC10)', () => {
  describe('ADMIN view (admin@petcare.vn)', () => {
    beforeEach(() => signIn('admin@petcare.vn'));

    it('renders system configurations table', async () => {
      renderPage();
      expect(await screen.findByText('vet.bio_max_length')).toBeInTheDocument();
      expect(await screen.findByText('customer.address_book_max')).toBeInTheDocument();

      const bioRow = await rowOf('vet.bio_max_length');
      expect(within(bioRow).getByText(/500/)).toBeInTheDocument();
      expect(within(bioRow).getByText('Số nguyên')).toBeInTheDocument();
    });

    it('edits a configuration parameter value', async () => {
      const user = userEvent.setup();
      renderPage();

      const bioRow = await rowOf('vet.bio_max_length');
      const editBtn = within(bioRow).getByRole('button', { name: /Sửa vet\.bio_max_length/i });
      await user.click(editBtn);

      const modal = screen.getByRole('dialog');
      expect(within(modal).getByText('Sửa vet.bio_max_length')).toBeInTheDocument();

      const input = within(modal).getByLabelText(/Giá trị/i);
      await user.clear(input);
      await user.type(input, '600');

      await user.click(within(modal).getByRole('button', { name: 'Lưu thay đổi' }));

      // Table updates with new value
      const updatedRow = await rowOf('vet.bio_max_length');
      expect(within(updatedRow).getByText(/600/)).toBeInTheDocument();
    });
  });

  describe('Non-admin view (chuoi@petcare.vn)', () => {
    beforeEach(() => signIn('chuoi@petcare.vn'));

    it('shows access denied message for non-admin', async () => {
      renderPage();
      expect(await screen.findByText('Chỉ quản trị viên mới có thể sửa tham số hệ thống.')).toBeInTheDocument();
      expect(screen.queryByText('vet.bio_max_length')).not.toBeInTheDocument();
    });
  });
});
