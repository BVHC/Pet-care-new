import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminNotificationTemplatesPage } from './AdminNotificationTemplatesPage';

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
      <MemoryRouter initialEntries={['/admin/notification-templates']}>
        <AdminNotificationTemplatesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// identity-v1 #34: `subject` bắt buộc với mọi kênh. Mock đã siết theo BE nên bản cũ (gửi subject: null cho IN_APP) bị 400.
describe('AdminNotificationTemplatesPage (T22 - UC10)', () => {
  beforeEach(() => signIn('admin@petcare.vn'));

  it('mẫu IN_APP cũng có ô Tiêu đề và lưu được với tiêu đề không null', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Sửa BRANCH_MANAGER_LOCKED_APP' }));

    const dialog = await screen.findByRole('dialog');
    const subject = within(dialog).getByLabelText('Tiêu đề *');
    expect(subject).toHaveValue('Quản lý chi nhánh bị khóa');
    expect(subject).toBeRequired();

    await user.type(subject, ' (đã sửa)');
    await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('Quản lý chi nhánh bị khóa (đã sửa)')).toBeInTheDocument();
  });

  it('tiêu đề chỉ toàn khoảng trắng: báo lỗi, không gọi API, hộp thoại ở lại', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Sửa BRANCH_MANAGER_LOCKED_APP' }));

    const dialog = await screen.findByRole('dialog');
    const subject = within(dialog).getByLabelText('Tiêu đề *');
    await user.clear(subject);
    await user.type(subject, '   ');
    await user.click(within(dialog).getByRole('button', { name: 'Lưu' }));

    expect(await within(dialog).findByText(/Tiêu đề không được để trống/)).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});
