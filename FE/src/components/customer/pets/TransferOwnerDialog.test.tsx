import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { customerPetApi } from '@/domains/pets/customer-pet.api';
import type { PetResponse } from '@/domains/pets/types';
import { apiClient } from '../../../shared/api/axios';
import { MOCK_PASSWORD } from '../../../shared/api/mock/identity.mock';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { useSession } from '../../../shared/stores/session.store';
import { TransferOwnerDialog } from './TransferOwnerDialog';

// Chuyển chủ là thao tác khó hoàn tác (BR-KH-08): ô tìm đã bị xóa hoặc chưa đủ điều kiện thì không được còn ứng viên của lần tra trước.
// Mock: SĐT 0905111222 có 3 hồ sơ (#2, #3, #4); thú thuộc hồ sơ #99 nên không hồ sơ nào bị lọc.

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'reception@store1.vn', password: MOCK_PASSWORD });
});

const pet: PetResponse = {
  petId: 1,
  customerId: 99,
  name: 'Mochi',
  species: 'CAT',
  sex: 'FEMALE',
  birthDateEstimated: false,
  speciesLocked: false,
  deletable: true,
};

function renderDialog() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <TransferOwnerDialog pet={pet} onClose={() => {}} />
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const candidates = () => screen.queryAllByRole('button', { name: /^Lê / });

describe('TransferOwnerDialog: ứng viên chủ mới', () => {
  it('xóa ô tìm thì hết ứng viên của lần tra trước', async () => {
    const user = renderDialog();
    const input = screen.getByLabelText('Tìm chủ mới (SĐT hoặc họ tên)');
    await user.type(input, '0905111222');
    await waitFor(() => expect(candidates()).toHaveLength(3));

    await user.clear(input);
    await waitFor(() => expect(candidates()).toHaveLength(0));
  });

  it('BE còn nhiều kết quả hơn số dòng đã vẽ thì nhắc thu hẹp từ khóa, đếm theo content (gồm cả chủ hiện tại bị lọc) (L-15)', async () => {
    const base = { phone: '0905111222', maskedEmail: null, createdChannel: 'COUNTER' as const, hasAccount: false, pets: [] };
    const content = [
      { ...base, customerId: pet.customerId, fullName: 'Lê Chủ Cũ' },
      { ...base, customerId: 2, fullName: 'Lê Chủ Mới' },
    ];
    vi.spyOn(customerPetApi, 'searchCustomers').mockResolvedValue({ content, page: 0, size: 100, totalElements: 130, totalPages: 2 });
    const user = renderDialog();
    await user.type(screen.getByLabelText('Tìm chủ mới (SĐT hoặc họ tên)'), '0905111222');
    expect(await screen.findByText('Đang hiển thị 2 / 130 kết quả, hãy thu hẹp từ khóa.')).toBeInTheDocument();
    expect(candidates()).toHaveLength(1);
  });

  it('SĐT bị xóa bớt (chưa đủ 10 số) thì hết ứng viên, chỉ còn lời nhắc', async () => {
    const user = renderDialog();
    const input = screen.getByLabelText('Tìm chủ mới (SĐT hoặc họ tên)');
    await user.type(input, '0905111222');
    await waitFor(() => expect(candidates()).toHaveLength(3));

    await user.type(input, '{Backspace}');
    expect(screen.getByText('Nhập đủ 10 số điện thoại.')).toBeInTheDocument();
    await waitFor(() => expect(candidates()).toHaveLength(0));
  });
});
