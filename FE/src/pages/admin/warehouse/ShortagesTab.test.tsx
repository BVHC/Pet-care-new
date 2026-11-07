import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeAll, describe, expect, it } from 'vitest';
import { apiClient } from '../../../shared/api/axios';
import { MOCK_PASSWORD } from '../../../shared/api/mock/identity.mock';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { materialUseApi } from '../../../shared/api/materialuse.api';
import { useSession } from '../../../shared/stores/session.store';
import { ShortagesTab } from './ShortagesTab';

// Thiếu hụt mẫu (materialuse.mock.ts) của chi nhánh 1: dòng 3 (lượt #58, thiếu 2 Amoxicillin 250mg) chưa xử lý, dòng 5 (lượt #40, thiếu 1) đã xử lý.

beforeAll(() => installMockApi(apiClient));

const signIn = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function renderTab() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <ShortagesTab />
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

describe('ShortagesTab (ST07)', () => {
  it('mặc định là chưa xử lý; đổi sang đã xử lý thì thấy ghi chú và không còn nút', async () => {
    await signIn('manager@store1.vn');
    const user = renderTab();
    const row = (await screen.findByText('#58')).closest('tr')!;
    expect(row).toHaveTextContent('Amoxicillin 250mg');
    expect(row).toHaveTextContent('Khám tổng quát');
    expect(within(row).getByRole('button', { name: /Đánh dấu đã xử lý Amoxicillin 250mg lượt #58/ })).toBeInTheDocument();
    expect(screen.queryByText('#40')).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Lọc thiếu hụt'), 'done');
    const done = (await screen.findByText('#40')).closest('tr')!;
    expect(done).toHaveTextContent('Đã nhập thêm Amoxicillin về kho');
    expect(within(done).queryByRole('button')).not.toBeInTheDocument();
  });

  it('đánh dấu đã xử lý: bắt buộc ghi chú, thành công thì dòng rời danh sách chưa xử lý', async () => {
    await signIn('manager@store1.vn');
    const user = renderTab();
    await user.click(await screen.findByRole('button', { name: /Đánh dấu đã xử lý Amoxicillin 250mg lượt #58/ }));
    const form = await screen.findByRole('dialog');
    await user.click(within(form).getByRole('button', { name: 'Ghi nhận' }));
    expect(within(form).getByRole('alert')).toHaveTextContent('Vui lòng ghi việc đã xử lý');

    await user.type(within(form).getByLabelText('Đã xử lý bằng cách nào'), 'Nhập thêm 10 viên');
    await user.click(within(form).getByRole('button', { name: 'Ghi nhận' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Đã ghi nhận xử lý thiếu hụt Amoxicillin 250mg của lượt #58');
    await waitFor(() => expect(screen.queryByText('#58')).not.toBeInTheDocument());
    expect(screen.getByText('Chưa có dữ liệu.')).toBeInTheDocument();
  });

  it('người khác đã xử lý trước: BE trả 409 và hộp hiện lý do', async () => {
    await signIn('manager@store1.vn');
    const user = renderTab();
    await user.click(await screen.findByRole('button', { name: /Đánh dấu đã xử lý Amoxicillin 250mg lượt #58/ }));
    const form = await screen.findByRole('dialog');
    await materialUseApi.resolveShortage(3, { note: 'Kho đã bù hàng' });

    await user.type(within(form).getByLabelText('Đã xử lý bằng cách nào'), 'Tôi xử lý');
    await user.click(within(form).getByRole('button', { name: 'Ghi nhận' }));

    expect(await within(form).findByRole('alert')).toHaveTextContent('Thiếu hụt vật tư này đã được xử lý');
  });

  it('chi nhánh 2 chỉ thấy thiếu hụt của chi nhánh mình', async () => {
    await signIn('manager2@store2.vn');
    renderTab();
    const row = (await screen.findByText('#7001')).closest('tr')!;
    expect(row).toHaveTextContent('Pate Whiskas 85g');
    expect(screen.queryByText('#58')).not.toBeInTheDocument();
  });
});
