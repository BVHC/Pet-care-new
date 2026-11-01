import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { customerPetApi } from '../../domains/pets/customer-pet.api';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { BookOnBehalfDialog } from './BookOnBehalfDialog';

// Đặt hộ sai khách là đặt lịch cho nhầm người: từ khóa quá ngắn (dưới 2 ký tự) hoặc ô trống thì không được còn ứng viên của lần tra trước.
// Mock: SĐT 0905111222 có 3 hồ sơ (#2, #3, #4).

beforeAll(() => installMockApi(apiClient));
afterEach(() => vi.restoreAllMocks());
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

  it('họ tên còn 1 ký tự (truy vấn bị tắt) thì hết ứng viên, không báo "không tìm thấy"', async () => {
    const user = renderDialog();
    const input = screen.getByLabelText('Tìm khách theo SĐT hoặc họ tên');
    await user.type(input, '0905111222');
    await waitFor(() => expect(candidates()).toHaveLength(3));

    await user.clear(input);
    await user.type(input, 'L');
    await waitFor(() => expect(candidates()).toHaveLength(0));
    expect(screen.queryByText(/Không tìm thấy khách/)).not.toBeInTheDocument();
  });

  it('gõ dở SĐT (8 số) thì không gọi API, chỉ nhắc nhập đủ 10 số, không báo "không tìm thấy"', async () => {
    const search = vi.spyOn(customerPetApi, 'searchCustomers');
    const user = renderDialog();
    await user.type(screen.getByLabelText('Tìm khách theo SĐT hoặc họ tên'), '09774455');
    expect(screen.getByText('Nhập đủ 10 số điện thoại để tra cứu.')).toBeInTheDocument();

    // Qua hết debounce (250 ms): vẫn không có lệnh gọi nào, kể cả tra theo họ tên bằng chuỗi số.
    await act(() => new Promise((resolve) => setTimeout(resolve, 400)));
    expect(search).not.toHaveBeenCalled();
    expect(screen.getByText('Nhập đủ 10 số điện thoại để tra cứu.')).toBeInTheDocument();
    expect(screen.queryByText(/Không tìm thấy khách/)).not.toBeInTheDocument();
  });

  it('đủ 10 số thì tra theo SĐT, hết lời nhắc', async () => {
    const search = vi.spyOn(customerPetApi, 'searchCustomers');
    const user = renderDialog();
    await user.type(screen.getByLabelText('Tìm khách theo SĐT hoặc họ tên'), '0905111222');
    await waitFor(() => expect(candidates()).toHaveLength(3));
    expect(search).toHaveBeenCalledTimes(1);
    expect(search.mock.calls[0][0]).toMatchObject({ phone: '0905111222' });
    expect(search.mock.calls[0][0]).not.toHaveProperty('name');
    expect(screen.queryByText('Nhập đủ 10 số điện thoại để tra cứu.')).not.toBeInTheDocument();
  });
});
