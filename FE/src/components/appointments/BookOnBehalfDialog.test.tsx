import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { BookOnBehalfDialog } from './BookOnBehalfDialog';

// Đặt hộ sai khách là đặt lịch cho nhầm người: từ khóa quá ngắn (dưới 2 ký tự) hoặc ô trống thì không được còn ứng viên của lần tra trước.
// Mock: SĐT 0905111222 có 3 hồ sơ (#2, #3, #4).

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'reception@store1.vn', password: MOCK_PASSWORD });
});

function renderDialog() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <BookOnBehalfDialog open branchId={1} onClose={() => {}} />
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const candidates = () => screen.queryAllByRole('button', { name: /^Chọn khách / });

describe('BookOnBehalfDialog: ứng viên khách', () => {
  it('xóa ô tìm thì hết ứng viên của lần tra trước', async () => {
    const user = renderDialog();
    const input = screen.getByLabelText('Tìm khách theo SĐT hoặc họ tên');
    await user.type(input, '0905111222');
    await waitFor(() => expect(candidates()).toHaveLength(3));

    await user.clear(input);
    await waitFor(() => expect(candidates()).toHaveLength(0));
  });

  it('từ khóa còn 1 ký tự (truy vấn bị tắt) thì hết ứng viên, không báo "không tìm thấy"', async () => {
    const user = renderDialog();
    const input = screen.getByLabelText('Tìm khách theo SĐT hoặc họ tên');
    await user.type(input, '0905111222');
    await waitFor(() => expect(candidates()).toHaveLength(3));

    await user.clear(input);
    await user.type(input, '0');
    await waitFor(() => expect(candidates()).toHaveLength(0));
    expect(screen.queryByText(/Không tìm thấy khách/)).not.toBeInTheDocument();
  });
});
