import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { customerPetApi } from '../../../../domains/pets/customer-pet.api';
import { apiClient } from '../../../../shared/api/axios';
import { MOCK_PASSWORD } from '../../../../shared/api/mock/identity.mock';
import { installMockApi } from '../../../../shared/api/mock/mock-api';
import { useSession } from '../../../../shared/stores/session.store';
import { CustomerDetailDialog } from './CustomerDetailDialog';
import { CustomerSearchPanel } from './CustomerSearchPanel';

// Quầy lễ tân (UC22–24, UC26) trên mock: 0905111222 có 3 hồ sơ; Lu (hồ sơ #3) đang có lượt khám mở; Miu (hồ sơ #4) xóa được.

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'reception@store1.vn', password: MOCK_PASSWORD });
});
afterEach(() => vi.restoreAllMocks());

function Desk() {
  const [customerId, setCustomerId] = useState<number | null>(null);
  return (
    <>
      <CustomerSearchPanel onOpenCustomer={setCustomerId} onCreateCustomer={() => {}} />
      <CustomerDetailDialog customerId={customerId} onClose={() => setCustomerId(null)} />
    </>
  );
}

function renderDesk() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <Desk />
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

/** Hai hồ sơ cùng tên "Lê Hoàng Long": chọn theo email đã che như lễ tân làm (BR-KH-10). */
async function openProfile(user: ReturnType<typeof userEvent.setup>, name: string, maskedEmail: string) {
  await user.type(screen.getByLabelText('Tra cứu theo SĐT'), '0905111222');
  await user.click(await screen.findByRole('button', { name: `Mở hồ sơ ${name}, ${maskedEmail}` }));
  return screen.findByRole('dialog', { name: name });
}

describe('tra cứu và hồ sơ khách tại quầy', () => {
  it('SĐT dùng chung ra đủ các hồ sơ kèm email đã che và thú cưng (BR-KH-10)', async () => {
    const user = renderDesk();
    await user.type(screen.getByLabelText('Tra cứu theo SĐT'), '0905111222');
    expect(await screen.findAllByRole('button', { name: /^Mở hồ sơ/ })).toHaveLength(3);
    expect(screen.getByText(/lo\*\*\*@gmail\.com/)).toBeInTheDocument();
    expect(within(screen.getByRole('list', { name: 'Thú cưng của Lê Thị Hoa' })).getByText(/Miu/)).toBeInTheDocument();
  });

  it('chưa đủ 10 số thì chưa tra, có nhắc', async () => {
    const user = renderDesk();
    await user.type(screen.getByLabelText('Tra cứu theo SĐT'), '0905');
    expect(screen.getByText('Nhập đủ 10 số điện thoại để tra cứu.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Mở hồ sơ/ })).not.toBeInTheDocument();
  });

  it('gõ đủ 10 số rồi xóa bớt thì các hồ sơ cũ biến mất, chỉ còn lời nhắc (tránh mở nhầm hồ sơ)', async () => {
    const user = renderDesk();
    const input = screen.getByLabelText('Tra cứu theo SĐT');
    await user.type(input, '0905111222');
    expect(await screen.findAllByRole('button', { name: /^Mở hồ sơ/ })).toHaveLength(3);

    await user.type(input, '{Backspace}{Backspace}');
    expect(screen.getByText('Nhập đủ 10 số điện thoại để tra cứu.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Mở hồ sơ/ })).not.toBeInTheDocument();

    // Sau khi debounce, truy vấn bị tắt và keepPreviousData vẫn giữ kết quả cũ: hàng không được hiện lại.
    await act(() => new Promise((resolve) => setTimeout(resolve, 400)));
    expect(screen.queryByRole('button', { name: /^Mở hồ sơ/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/hồ sơ$/)).not.toBeInTheDocument();
  });

  it('xin BE tối đa 100 dòng mỗi lần tra cứu, không để mặc định 20', async () => {
    const search = vi.spyOn(customerPetApi, 'searchCustomers');
    const user = renderDesk();
    await user.type(screen.getByLabelText('Tra cứu theo SĐT'), '0905111222');
    await screen.findAllByRole('button', { name: /^Mở hồ sơ/ });
    expect(search).toHaveBeenCalledWith(expect.objectContaining({ phone: '0905111222' }));
    expect(Number(search.mock.calls.at(-1)![0].size)).toBe(100);
  });

  it('BE còn nhiều kết quả hơn số dòng đã vẽ thì nhắc thu hẹp từ khóa, không cắt im lặng', async () => {
    const item = { customerId: 1, fullName: 'Khách Một', phone: '0905111222', maskedEmail: null, createdChannel: 'COUNTER' as const, hasAccount: false, pets: [] };
    vi.spyOn(customerPetApi, 'searchCustomers').mockResolvedValue({ content: [item], page: 0, size: 100, totalElements: 130, totalPages: 2 });
    const user = renderDesk();
    await user.type(screen.getByLabelText('Tra cứu theo SĐT'), '0905111222');
    expect(await screen.findByText('Đang hiển thị 1 / 130 kết quả, hãy thu hẹp từ khóa.')).toBeInTheDocument();
  });

  it('đủ kết quả trong một trang thì chỉ ghi số hồ sơ, không có lời nhắc thu hẹp', async () => {
    const user = renderDesk();
    await user.type(screen.getByLabelText('Tra cứu theo SĐT'), '0905111222');
    expect(await screen.findByText('3 hồ sơ')).toBeInTheDocument();
    expect(screen.queryByText(/hãy thu hẹp từ khóa/)).not.toBeInTheDocument();
  });

  it('đánh dấu đã mất bị BE chặn khi thú có lượt khám mở thì báo lỗi trong hộp thoại (BR-KH-05)', async () => {
    const user = renderDesk();
    const profile = await openProfile(user, 'Lê Hoàng Long', 'lo***@gmail.com');
    await user.click(await within(profile).findByRole('button', { name: /Đã mất/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Đánh dấu Lu đã mất' });
    await user.click(within(dialog).getByLabelText('Đã xác nhận với chủ nuôi'));
    await user.click(within(dialog).getByRole('button', { name: 'Tiếp tục' }));
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận đã mất' }));
    expect(await within(dialog).findByText('Thú đang có lượt khám chưa kết thúc')).toBeInTheDocument();
  });

  it('lễ tân tạo khiếu nại thay khách từ hồ sơ khách đang mở (UC85)', async () => {
    const user = renderDesk();
    await user.type(screen.getByLabelText('Tra cứu theo SĐT'), '0912345678');
    await user.click(await screen.findByRole('button', { name: /^Mở hồ sơ Nguyễn Thu Trang/ }));
    const profile = await screen.findByRole('dialog', { name: 'Nguyễn Thu Trang' });
    await user.click(within(profile).getByRole('tab', { name: 'Thông tin khách' }));
    await user.click(within(profile).getByRole('button', { name: 'Tạo khiếu nại' }));
    expect(await screen.findByRole('dialog', { name: 'Tạo khiếu nại thay khách' })).toBeInTheDocument();
  });

  it('sửa email tài khoản khách qua OTP sau khi đối chiếu CCCD (UC22, BR-TK-16)', async () => {
    const user = renderDesk();
    await user.type(screen.getByLabelText('Tra cứu theo SĐT'), '0912345678');
    await user.click(await screen.findByRole('button', { name: /^Mở hồ sơ Nguyễn Thu Trang/ }));
    const profile = await screen.findByRole('dialog', { name: 'Nguyễn Thu Trang' });
    await user.click(within(profile).getByRole('tab', { name: 'Thông tin khách' }));
    await user.click(within(profile).getByRole('button', { name: /Sửa email tài khoản/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Sửa email tài khoản' });
    await user.type(within(dialog).getByLabelText('Email mới'), 'trang.moi@gmail.com');
    await user.click(within(dialog).getByRole('button', { name: 'Gửi mã tới email mới' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Cần đối chiếu CCCD');
    await user.click(within(dialog).getByLabelText('Đã đối chiếu CCCD của khách với họ tên hồ sơ'));
    await user.click(within(dialog).getByRole('button', { name: 'Gửi mã tới email mới' }));
    await user.type(await within(dialog).findByLabelText('Mã OTP'), '123456');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận đổi email' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Sửa email tài khoản' })).not.toBeInTheDocument());
    // Khoảng trống BE-2, ADR-0034 TBD: BE chỉ đổi email TÀI KHOẢN, không đồng bộ customers.email của hồ sơ, nên hồ sơ vẫn nêu email cũ (BE test `customerProfileEmailIsNotSyncedKnownGap`).
    expect(await within(profile).findByText('khach@petcare.vn')).toBeInTheDocument();
    expect(within(profile).queryByText('trang.moi@gmail.com')).not.toBeInTheDocument();
  });

  it('xóa thú chưa phát sinh giao dịch, xác nhận ngay trong hàng (BR-KH-06)', async () => {
    const user = renderDesk();
    const profile = await openProfile(user, 'Lê Thị Hoa', 'chưa có email');
    await user.click(await within(profile).findByRole('button', { name: /Xóa/ }));
    await user.click(within(profile).getByRole('button', { name: 'Xóa' }));
    await waitFor(() => expect(within(profile).getByText('Khách chưa có thú cưng nào.')).toBeInTheDocument());
  });
});
