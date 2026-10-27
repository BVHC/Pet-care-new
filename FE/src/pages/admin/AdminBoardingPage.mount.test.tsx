import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { ACCOUNTS, pinClock } from '../../test/boarding-harness';
import { AdminBoardingPage } from './AdminBoardingPage';

// Ghép thật với 5 dialog của WS STAY / CARELOG (không giả): chỉ kiểm hợp đồng — mở đúng dialog, tiêu đề cố định trong dialog.
// Dùng tiêu đề (heading) trong dialog thay vì getByRole('dialog', { name }) vì Modal của modal-templates chưa gắn tên truy cập cho role="dialog".

beforeAll(() => installMockApi(apiClient));
beforeEach(() => {
  pinClock();
  useSession.getState().clear();
});
afterEach(() => vi.useRealTimers());

async function openAs(email: string) {
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/boarding']}>
        <AdminBoardingPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const rowOf = async (petName: string) => (await screen.findByText(petName, { selector: 'p' })).closest('tr')!;

async function expectDialogTitled(title: string) {
  const dialog = await screen.findByRole('dialog');
  expect((await within(dialog).findAllByRole('heading', { name: title })).length).toBeGreaterThan(0);
  return dialog;
}

describe('AdminBoardingPage ghép với dialog thật của STAY / CARELOG', () => {
  it.each([
    ['Nhận thú', 'Nhận thú', 'Bơ', false],
    ['Hủy vì hết chuồng', 'Hủy vì hết chuồng', 'Bơ', false],
    ['Trả thú', 'Trả thú', 'Cốm', true],
    ['Thú mất', 'Thú mất', 'Cốm', true],
    ['Nhật ký', 'Nhật ký chăm sóc', 'Cốm', true],
  ])('nút %s mở dialog "%s"', async (button, title, pet, inStay) => {
    const user = await openAs(ACCOUNTS.desk);
    if (inStay) await user.click(await screen.findByRole('tab', { name: 'Đang lưu trú' }));
    await user.click(within(await rowOf(pet)).getByRole('button', { name: new RegExp(`^${button} LT-`) }));
    await expectDialogTitled(title);
  });

  it('VET mở được Nhật ký chăm sóc', async () => {
    const user = await openAs(ACCOUNTS.vet);
    await user.click(await screen.findByRole('tab', { name: 'Đang lưu trú' }));
    await user.click(within(await rowOf('Cốm')).getByRole('button', { name: /^Nhật ký LT-/ }));
    await expectDialogTitled('Nhật ký chăm sóc');
  });

  it('CARETAKER mở được Nhận thú', async () => {
    const user = await openAs(ACCOUNTS.caretaker);
    await user.click(within(await rowOf('Bơ')).getByRole('button', { name: /^Nhận thú LT-/ }));
    await expectDialogTitled('Nhận thú');
  });
});
