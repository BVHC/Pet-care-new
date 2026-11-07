import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, describe, expect, it } from 'vitest';
import { apiClient } from '../../../shared/api/axios';
import { inventoryApi } from '../../../shared/api/inventory.api';
import { MOCK_PASSWORD } from '../../../shared/api/mock/identity.mock';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { stockopsApi } from '../../../shared/api/stockops.api';
import { useSession } from '../../../shared/stores/session.store';
import { CountsTab } from './CountsTab';

// Phiên mẫu (stockops.mock.ts): chi nhánh 1 có #1 đã xác nhận (vaccine, đếm khớp) và #2 đã hủy, chưa có phiên OPEN; chi nhánh 2 đang có #3 OPEN (lô 18, Royal Canin).

beforeAll(() => installMockApi(apiClient));

const signIn = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function renderTab() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <CountsTab />
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

describe('CountsTab (UC78)', () => {
  it('liệt kê phiên của chi nhánh mình theo trạng thái; xem phiên đã xác nhận không còn ô nhập', async () => {
    await signIn('manager@store1.vn');
    const user = renderTab();
    await screen.findByRole('button', { name: /^Mở phiên KK-\d{6}-0001$/ });
    expect(screen.getByRole('button', { name: /^Mở phiên KK-\d{6}-0002$/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /-0003$/ })).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Lọc trạng thái phiên kiểm kê'), 'CANCELLED');
    await waitFor(() => expect(screen.queryByRole('button', { name: /-0001$/ })).not.toBeInTheDocument());
    await user.selectOptions(screen.getByLabelText('Lọc trạng thái phiên kiểm kê'), '');

    await user.click(await screen.findByRole('button', { name: /^Mở phiên KK-\d{6}-0001$/ }));
    const detail = await screen.findByRole('dialog');
    expect(await within(detail).findAllByText('Vaccine Dại Rabisin')).toHaveLength(2);
    expect(within(detail).getByText('Đã xác nhận')).toBeInTheDocument();
    expect(within(detail).queryByRole('spinbutton')).not.toBeInTheDocument();
    expect(within(detail).queryByRole('button', { name: 'Xác nhận kiểm kê' })).not.toBeInTheDocument();
  });

  it('mở phiên toàn kho, đếm lệch một lô rồi xác nhận: số nhập chưa lưu được gửi kèm và phiên thành Đã xác nhận', async () => {
    await signIn('manager@store1.vn');
    const user = renderTab();
    await user.click(await screen.findByRole('button', { name: 'Mở phiên kiểm kê' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Mở phiên' }));

    expect(await screen.findByRole('status')).toHaveTextContent(/Đã mở phiên kiểm kê KK-\d{6}-0004/);
    const detail = await screen.findByRole('dialog', { name: /Phiên kiểm kê KK-\d{6}-0004/ });
    const confirm = within(detail).getByRole('button', { name: 'Xác nhận kiểm kê' });
    expect(confirm).toBeDisabled();
    expect(within(detail).getByRole('button', { name: 'Lưu số đếm' })).toBeDisabled();

    const input = await within(detail).findByLabelText('Số đếm Amoxicillin 250mg AMX2405');
    await user.type(input, '-1');
    await user.click(confirm);
    expect(within(detail).getByRole('alert')).toHaveTextContent(/số đếm là số nguyên từ 0 trở lên/);

    await user.clear(input);
    await user.type(input, '190');
    expect(within(input.closest('tr')!).getByText('-10')).toBeInTheDocument();
    await user.click(confirm);

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(/Đã xác nhận kiểm kê KK-\d{6}-0004/));
    const adjustments = await inventoryApi.listAdjustments({});
    expect(adjustments.content[0]).toMatchObject({ reason: 'COUNT_DIFF' });
  });

  it('theo danh mục: bắt buộc chọn danh mục; lưu số đếm không đóng hộp', async () => {
    await signIn('manager@store1.vn');
    const user = renderTab();
    await user.click(await screen.findByRole('button', { name: 'Mở phiên kiểm kê' }));
    const form = await screen.findByRole('dialog');
    await user.selectOptions(within(form).getByLabelText('Phạm vi'), 'CATEGORY');
    await user.click(within(form).getByRole('button', { name: 'Mở phiên' }));
    expect(within(form).getByRole('alert')).toHaveTextContent('Kiểm kê theo danh mục phải chọn danh mục sản phẩm');

    await user.selectOptions(within(form).getByLabelText('Danh mục sản phẩm'), await within(form).findByRole('option', { name: 'Vaccine' }));
    await user.click(within(form).getByRole('button', { name: 'Mở phiên' }));
    const detail = await screen.findByRole('dialog', { name: /Phiên kiểm kê KK-\d{6}-0004/ });
    const input = await within(detail).findByLabelText('Số đếm Vaccine Dại Rabisin RB2401');
    await user.type(input, '17');
    await user.click(within(detail).getByRole('button', { name: 'Lưu số đếm' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Đã lưu số đếm');
    expect(screen.getByRole('dialog', { name: /Phiên kiểm kê/ })).toBeInTheDocument();
    expect(within(detail).getByRole('button', { name: 'Lưu số đếm' })).toBeDisabled();

    // Xóa trắng số đã lưu: chặn tại form, không xác nhận (BE không gỡ được số đã lưu, xác nhận sẽ vẫn điều chỉnh theo số cũ).
    await user.clear(await within(detail).findByLabelText('Số đếm Vaccine Dại Rabisin RB2401'));
    await user.click(within(detail).getByRole('button', { name: 'Xác nhận kiểm kê' }));
    expect(within(detail).getByRole('alert')).toHaveTextContent('đã có số đếm 17, không để trống được');
    expect((await stockopsApi.listCounts({ status: 'OPEN' })).content).toHaveLength(1);
  });

  it('chi nhánh đã có phiên đang mở: mở phiên thứ hai nhận 409 và hộp hiện lý do', async () => {
    await signIn('manager2@store2.vn');
    const user = renderTab();
    await screen.findByRole('button', { name: /^Mở phiên KK-\d{6}-0003$/ });
    await user.click(screen.getByRole('button', { name: 'Mở phiên kiểm kê' }));
    const form = await screen.findByRole('dialog');
    await user.click(within(form).getByRole('button', { name: 'Mở phiên' }));

    expect(await within(form).findByRole('alert')).toHaveTextContent('Chi nhánh đã có phiên kiểm kê đang mở');
  });

  it('hủy phiên bắt buộc lý do; phiên người khác vừa xác nhận thì nhận 409', async () => {
    await signIn('manager2@store2.vn');
    const user = renderTab();
    await user.click(await screen.findByRole('button', { name: /^Mở phiên KK-\d{6}-0003$/ }));
    const detail = await screen.findByRole('dialog');
    await within(detail).findByLabelText('Số đếm Hạt Royal Canin Mini Adult 2kg lô mặc định');

    await user.click(within(detail).getByRole('button', { name: 'Hủy phiên' }));
    await user.click(within(detail).getByRole('button', { name: 'Xác nhận hủy phiên' }));
    expect(within(detail).getByRole('alert')).toHaveTextContent('Vui lòng nhập lý do hủy phiên kiểm kê');

    await stockopsApi.saveCountLines(3, { lines: [{ stockLotId: 18, countedQty: 7 }] });
    await stockopsApi.confirmCount(3);
    await user.type(within(detail).getByLabelText('Lý do hủy phiên (bắt buộc)'), 'Đếm nhầm kho');
    await user.click(within(detail).getByRole('button', { name: 'Xác nhận hủy phiên' }));
    expect(await within(detail).findByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái phiên kiểm kê từ CONFIRMED sang CANCELLED');
  });
});
