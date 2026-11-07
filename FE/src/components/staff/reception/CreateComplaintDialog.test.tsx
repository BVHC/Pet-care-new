import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../../shared/api/axios';
import { complaintApi } from '../../../shared/api/complaint.api';
import { MOCK_PASSWORD } from '../../../shared/api/mock/identity.mock';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { useSession } from '../../../shared/stores/session.store';
import { CreateComplaintDialog } from './CreateComplaintDialog';

// Lễ tân reception@store1.vn (Lan Chi, chi nhánh 1) tạo thay khách 2 (khachmoi@petcare.vn, Lê Hoàng Long). Dữ liệu mẫu ở shared/api/mock/complaint.mock.ts:
// lễ tân này đã có 1 khiếu nại tạo thay (#10); đơn online 5001 là của khách 1.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-06T09:00:00+07:00'));
});
afterEach(() => vi.useRealTimers());

async function renderDialog(props: { customerId?: number } = { customerId: 2 }, email = 'reception@store1.vn') {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
  const onOpenChange = vi.fn();
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <CreateComplaintDialog open onOpenChange={onOpenChange} {...props} />
    </QueryClientProvider>,
  );
  const dialog = await screen.findByRole('dialog', { name: 'Tạo khiếu nại thay khách' });
  return { user: userEvent.setup(), dialog, onOpenChange };
}

describe('CreateComplaintDialog (UC85 #7, lễ tân)', () => {
  it('có customerId: vào thẳng biểu mẫu, hiện tên khách, không có ô chọn chi nhánh', async () => {
    const { dialog } = await renderDialog();
    expect(await within(dialog).findByText('Lê Hoàng Long')).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Đổi khách' })).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText('Chi nhánh')).not.toBeInTheDocument();
    expect(within(dialog).getByLabelText('Chủ đề')).toBeInTheDocument();
  });

  it('tạo thành công: hộp đóng, khiếu nại gắn chi nhánh của lễ tân và được ghi là tạo thay', async () => {
    const { user, dialog, onOpenChange } = await renderDialog();
    await user.selectOptions(within(dialog).getByLabelText('Chủ đề'), 'SERVICE_QUALITY');
    await user.type(within(dialog).getByLabelText('Nội dung'), 'Khách chờ khám quá lâu so với giờ hẹn.');
    await user.click(within(dialog).getByRole('button', { name: 'Tạo khiếu nại' }));
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
    const mine = (await complaintApi.listComplaints()).content;
    expect(mine).toHaveLength(2);
    const created = await complaintApi.getComplaint(Math.max(...mine.map((c) => c.complaintId)));
    expect(created).toMatchObject({ customerId: 2, branchId: 1, topic: 'SERVICE_QUALITY', createdByStaff: true, status: 'OPEN' });
  });

  it('thiếu chủ đề / nội dung: báo ngay, hộp giữ nguyên', async () => {
    const { user, dialog, onOpenChange } = await renderDialog();
    await user.click(within(dialog).getByRole('button', { name: 'Tạo khiếu nại' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Chọn chủ đề khiếu nại.');
    await user.selectOptions(within(dialog).getByLabelText('Chủ đề'), 'STAFF');
    await user.click(within(dialog).getByRole('button', { name: 'Tạo khiếu nại' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Nhập nội dung khiếu nại.');
    expect(onOpenChange).not.toHaveBeenCalled();
  });

  it('đối tượng liên quan không thuộc khách (400 BR-DG-05): lỗi hiện trong hộp, hộp không đóng', async () => {
    const { user, dialog, onOpenChange } = await renderDialog();
    await user.selectOptions(within(dialog).getByLabelText('Chủ đề'), 'PURCHASE');
    await user.selectOptions(within(dialog).getByLabelText('Liên quan đến (tùy chọn)'), 'ONLINE_ORDER');
    await user.type(within(dialog).getByLabelText('Mã số'), '5001');
    await user.type(within(dialog).getByLabelText('Nội dung'), 'Đơn hàng giao thiếu hàng so với hóa đơn.');
    await user.click(within(dialog).getByRole('button', { name: 'Tạo khiếu nại' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Đối tượng liên quan không thuộc khách hàng này');
    expect(onOpenChange).not.toHaveBeenCalled();
  });

  it('không có customerId: tìm khách trước, chọn xong mới ra biểu mẫu và đổi khách được', async () => {
    const { user, dialog } = await renderDialog({});
    expect(within(dialog).queryByLabelText('Chủ đề')).not.toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Tìm khách theo SĐT hoặc họ tên'), 'Lê Hoàng Long');
    const pick = (await within(dialog).findAllByRole('button', { name: 'Chọn khách Lê Hoàng Long' }))[0];
    await user.click(pick);
    expect(await within(dialog).findByLabelText('Chủ đề')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Đổi khách' }));
    expect(await within(dialog).findByLabelText('Tìm khách theo SĐT hoặc họ tên')).toBeInTheDocument();
  });

  it('vai không phải lễ tân (bác sĩ): máy chủ trả 403, hộp hiện lỗi', async () => {
    const { user, dialog, onOpenChange } = await renderDialog({ customerId: 2 }, 'doctor@store1.vn');
    await user.selectOptions(within(dialog).getByLabelText('Chủ đề'), 'OTHER');
    await user.type(within(dialog).getByLabelText('Nội dung'), 'Thử tạo khiếu nại bằng tài khoản bác sĩ.');
    await user.click(within(dialog).getByRole('button', { name: 'Tạo khiếu nại' }));
    expect(await within(dialog).findByRole('alert')).toBeInTheDocument();
    expect(onOpenChange).not.toHaveBeenCalled();
  });
});
