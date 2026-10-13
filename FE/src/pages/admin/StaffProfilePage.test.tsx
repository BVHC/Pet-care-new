import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { profileApi } from '../../shared/api/profile.api';
import { useSession } from '../../shared/stores/session.store';
import { StaffProfilePage } from './StaffProfilePage';

// Hồ sơ mẫu (identity.mock.ts): SĐT 0900000 + id; BS. Minh Anh (id 5, chi nhánh 1) có chuyên môn và mô tả ngắn.
beforeAll(() => installMockApi(apiClient));

const signIn = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <StaffProfilePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('staff profile (UC06)', () => {
  it('shows the profile with the email read-only (BR-TK-15) and the branch', async () => {
    await signIn('reception@store1.vn');
    renderPage();
    expect(await screen.findByLabelText('Họ tên')).toHaveValue('Lan Chi');
    expect(screen.getByLabelText('Số điện thoại')).toHaveValue('0900000004');
    expect(screen.getByLabelText('Email')).toHaveValue('reception@store1.vn');
    expect(screen.getByLabelText('Email')).toHaveAttribute('readonly');
    expect(await screen.findByText('PetCare Cầu Giấy')).toBeInTheDocument();
  });

  it('saves the personal details and shows the new name in the header', async () => {
    await signIn('reception@store1.vn');
    const user = userEvent.setup();
    renderPage();
    const name = await screen.findByLabelText('Họ tên');
    await user.clear(name);
    await user.type(name, 'Nguyễn Lan Chi');
    await user.click(screen.getByRole('button', { name: 'Lưu thông tin' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Đã cập nhật hồ sơ');
    expect(useSession.getState().fullName).toBe('Nguyễn Lan Chi');
  });

  it('puts the BE errors under their fields', async () => {
    await signIn('reception@store1.vn');
    const user = userEvent.setup();
    renderPage();
    const phone = await screen.findByLabelText('Số điện thoại');
    await user.clear(phone);
    await user.type(phone, '0123');
    await user.type(screen.getByLabelText('Ảnh đại diện'), 'http://anh.vn/a.png');
    await user.click(screen.getByRole('button', { name: 'Lưu thông tin' }));

    expect(await screen.findByText('Số điện thoại phải có dạng 0xxxxxxxxx')).toBeInTheDocument();
    expect(screen.getByText('Ảnh đại diện phải là đường dẫn https://')).toBeInTheDocument();
  });

  it('keeps the public introduction to vets', async () => {
    await signIn('reception@store1.vn');
    renderPage();
    await screen.findByLabelText('Họ tên');
    expect(screen.queryByRole('heading', { name: 'Hồ sơ giới thiệu công khai' })).not.toBeInTheDocument();
  });
});

describe('public introduction of a vet (UC06, BR-TK-20)', () => {
  it('previews the card and clears the specialty when the field is emptied', async () => {
    await signIn('doctor@store1.vn');
    const user = userEvent.setup();
    renderPage();
    const intro = (await screen.findByRole('heading', { name: 'Hồ sơ giới thiệu công khai' })).closest('section')!;
    const preview = within(intro).getByRole('article', { name: 'Xem trước' });
    expect(within(preview).getByText('Nội khoa, da liễu thú y')).toBeInTheDocument();

    await user.clear(within(intro).getByLabelText('Chuyên môn'));
    await user.click(within(intro).getByRole('button', { name: 'Lưu hồ sơ giới thiệu' }));

    expect(await within(intro).findByRole('status')).toHaveTextContent('Đã lưu hồ sơ giới thiệu');
    expect((await profileApi.me()).staffProfile?.specialty).toBeNull();
    expect(within(preview).queryByText('Nội khoa, da liễu thú y')).not.toBeInTheDocument();
  });

  it('stops a bio over 500 characters before sending', async () => {
    await signIn('doctor@store1.vn');
    const user = userEvent.setup();
    renderPage();
    const intro = (await screen.findByRole('heading', { name: 'Hồ sơ giới thiệu công khai' })).closest('section')!;
    fireEvent.change(within(intro).getByLabelText('Mô tả ngắn'), { target: { value: 'a'.repeat(501) } });
    expect(within(intro).getByText('501/500')).toBeInTheDocument();
    await user.click(within(intro).getByRole('button', { name: 'Lưu hồ sơ giới thiệu' }));

    expect(within(intro).getByRole('alert')).toHaveTextContent('Mô tả ngắn tối đa 500 ký tự');
  });
});
