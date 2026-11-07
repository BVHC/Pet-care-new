import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { ComplaintsPage } from './ComplaintsPage';

// Giờ cố định 06/11/2026 09:00 (giờ VN). Dữ liệu mẫu ở shared/api/mock/complaint.mock.ts, khách khach@petcare.vn (khách 1) có 10 khiếu nại:
//   Chờ nhận 4 (Làm đẹp, Mua hàng quá hạn, Lưu trú chi nhánh 2, Vấn đề khác) · Đang xử lý 1 (Chất lượng dịch vụ) · Đã giải quyết 2 (Mua hàng, Làm đẹp đã mở lại 2 lần)
//   · Đã đóng 3 (Lưu trú do khách đóng, Thái độ nhân viên do khách rút, Chất lượng dịch vụ tự đóng). khachmoi@petcare.vn (khách 2) chỉ có 2.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-06T09:00:00+07:00'));
});
afterEach(() => vi.useRealTimers());

async function renderPage(email = 'khach@petcare.vn') {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
  render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <ComplaintsPage />
      </QueryClientProvider>
    </MemoryRouter>,
  );
  return userEvent.setup();
}

type User = Awaited<ReturnType<typeof renderPage>>;
const items = async () => within(await screen.findByRole('list', { name: 'Danh sách khiếu nại' })).getAllByRole('listitem');
const openTab = (user: User, name: string) => user.click(screen.getByRole('tab', { name }));
/** Mở khiếu nại đầu tiên có chủ đề `topic` trong danh sách đang hiện. */
async function openComplaint(user: User, topic: string) {
  const item = (await items()).find((li) => li.textContent?.includes(topic))!;
  await user.click(within(item).getByRole('button', { name: /^Xem khiếu nại/ }));
  return screen.findByRole('dialog', { name: /^Khiếu nại KN-/ });
}

describe('ComplaintsPage: danh sách (#2)', () => {
  it('liệt kê 10 khiếu nại của mình, có link Khiếu nại ở menu tài khoản, lọc theo trạng thái', async () => {
    const user = await renderPage();
    expect(await items()).toHaveLength(10);
    expect(screen.getByRole('link', { name: 'Khiếu nại' })).toHaveAttribute('href', '/complaints');
    await openTab(user, 'Chờ nhận');
    await waitFor(async () => expect(await items()).toHaveLength(4));
    await openTab(user, 'Đã đóng');
    await waitFor(async () => expect(await items()).toHaveLength(3));
  });

  it('khách khác chỉ thấy khiếu nại của chính họ', async () => {
    await renderPage('khachmoi@petcare.vn');
    expect(await items()).toHaveLength(2);
  });
});

describe('ComplaintsPage: gửi khiếu nại mới (#1)', () => {
  async function openForm(user: User) {
    await user.click(screen.getByRole('button', { name: 'Gửi khiếu nại' }));
    const dialog = await screen.findByRole('dialog', { name: 'Gửi khiếu nại' });
    await waitFor(() => expect(within(within(dialog).getByLabelText('Chi nhánh')).getAllByRole('option').length).toBeGreaterThan(1));
    return dialog;
  }
  const submit = (user: User, dialog: HTMLElement) => user.click(within(dialog).getByRole('button', { name: 'Gửi khiếu nại' }));

  it('gửi thành công: hộp đóng, báo mã khiếu nại, danh sách thêm một mục (sang trang 2)', async () => {
    const user = await renderPage();
    await items();
    const dialog = await openForm(user);
    await user.selectOptions(within(dialog).getByLabelText('Chủ đề'), 'PURCHASE');
    const branch = within(within(dialog).getByLabelText('Chi nhánh')).getAllByRole('option')[1] as HTMLOptionElement;
    await user.selectOptions(within(dialog).getByLabelText('Chi nhánh'), branch.value);
    await user.type(within(dialog).getByLabelText('Nội dung'), 'Đơn hàng giao thiếu một túi hạt, mong được bù.');
    await submit(user, dialog);
    expect(await screen.findByText(/^Đã gửi khiếu nại KN-261106-/)).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(await screen.findByText(/Trang 1\/2 · 11 khiếu nại/)).toBeInTheDocument();
  });

  it('thiếu chủ đề: báo ngay, không gọi máy chủ', async () => {
    const user = await renderPage();
    const dialog = await openForm(user);
    await submit(user, dialog);
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Chọn chủ đề khiếu nại.');
  });

  it('nội dung quá ngắn: lỗi BR-DG-05 của máy chủ hiện trong hộp', async () => {
    const user = await renderPage();
    const dialog = await openForm(user);
    await user.selectOptions(within(dialog).getByLabelText('Chủ đề'), 'OTHER');
    await user.type(within(dialog).getByLabelText('Nội dung'), 'Ngắn quá');
    await submit(user, dialog);
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/Nội dung khiếu nại phải từ 10 đến 2000 ký tự/);
    expect(screen.getByRole('dialog', { name: 'Gửi khiếu nại' })).toBeInTheDocument();
  });

  it('đối tượng liên quan không phải của mình: lỗi BR-DG-05', async () => {
    const user = await renderPage();
    const dialog = await openForm(user);
    await user.selectOptions(within(dialog).getByLabelText('Chủ đề'), 'PURCHASE');
    const branch = within(within(dialog).getByLabelText('Chi nhánh')).getAllByRole('option')[1] as HTMLOptionElement;
    await user.selectOptions(within(dialog).getByLabelText('Chi nhánh'), branch.value);
    await user.selectOptions(within(dialog).getByLabelText('Liên quan đến (tùy chọn)'), 'ONLINE_ORDER');
    await user.type(within(dialog).getByLabelText('Mã số'), '5002');
    await user.type(within(dialog).getByLabelText('Nội dung'), 'Đơn này giao sai địa chỉ so với đặt.');
    await submit(user, dialog);
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Đối tượng liên quan không thuộc khách hàng này');
  });
});

describe('ComplaintsPage: chi tiết và trao đổi (#3–4)', () => {
  it('khiếu nại đang xử lý: xem trao đổi, nhắn thêm; chỉ có Rút (không Đóng / Mở lại)', async () => {
    const user = await renderPage();
    await openTab(user, 'Đang xử lý');
    const dialog = await openComplaint(user, 'Chất lượng dịch vụ');
    expect(within(dialog).getByText(/nhật ký chăm sóc ghi thiếu nhiều bữa ăn/)).toBeInTheDocument();
    expect(within(dialog).getByText(/đang đối chiếu nhật ký với camera/)).toBeInTheDocument();
    expect(within(dialog).getByText('Đặt chỗ lưu trú #5')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Rút khiếu nại' })).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Đóng khiếu nại' })).not.toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Mở lại' })).not.toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Tin nhắn cho chi nhánh'), 'Em bổ sung ảnh chụp nhật ký ạ');
    await user.click(within(dialog).getByRole('button', { name: 'Gửi tin nhắn' }));
    expect(await within(dialog).findByText('Em bổ sung ảnh chụp nhật ký ạ')).toBeInTheDocument();
  });
});

describe('ComplaintsPage: đóng, rút, mở lại (#5–6)', () => {
  it('rút khi còn xử lý: phải xác nhận; rút xong là Đã đóng và không còn nút Mở lại', async () => {
    const user = await renderPage();
    await openTab(user, 'Đang xử lý');
    const dialog = await openComplaint(user, 'Chất lượng dịch vụ');
    await user.click(within(dialog).getByRole('button', { name: 'Rút khiếu nại' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('không mở lại được');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận rút' }));
    expect(await within(dialog).findByText('Đã rút khiếu nại.')).toBeInTheDocument();
    await waitFor(() => expect(within(dialog).getByText(/· Khách đã rút$/)).toBeInTheDocument());
    expect(within(dialog).queryByRole('button', { name: 'Mở lại' })).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText('Tin nhắn cho chi nhánh')).not.toBeInTheDocument();
  });

  it('đã giải quyết: đóng được, rồi mở lại kèm lý do thì quay về Chờ nhận', async () => {
    const user = await renderPage();
    await openTab(user, 'Đã giải quyết');
    const dialog = await openComplaint(user, 'Mua hàng');
    expect(within(dialog).getByText(/Chi nhánh đã gửi bù gói thức ăn/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Đóng khiếu nại' }));
    expect(await within(dialog).findByText(/^Đã đóng khiếu nại/)).toBeInTheDocument();
    await user.click(await within(dialog).findByRole('button', { name: 'Mở lại' }));
    await user.type(within(dialog).getByLabelText('Lý do mở lại'), 'Gói thức ăn bù vẫn thiếu một túi');
    await user.click(within(dialog).getByRole('button', { name: 'Mở lại khiếu nại' }));
    await waitFor(() => expect(within(dialog).getByRole('button', { name: 'Rút khiếu nại' })).toBeInTheDocument());
    expect(within(dialog).getByText('Gói thức ăn bù vẫn thiếu một túi')).toBeInTheDocument();
  });

  it('mở lại quá số lần cho phép: lỗi BR-DG-07 hiện ngay dưới ô lý do', async () => {
    const user = await renderPage();
    await openTab(user, 'Đã giải quyết');
    const dialog = await openComplaint(user, 'Làm đẹp');
    await user.click(within(dialog).getByRole('button', { name: 'Mở lại' }));
    await user.type(within(dialog).getByLabelText('Lý do mở lại'), 'Vẫn chưa được giải quyết thỏa đáng');
    await user.click(within(dialog).getByRole('button', { name: 'Mở lại khiếu nại' }));
    expect(await within(dialog).findByText('Khiếu nại chỉ được mở lại tối đa 2 lần')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Lý do mở lại')).toHaveValue('Vẫn chưa được giải quyết thỏa đáng');
  });

  it('đã đóng: khách tự đóng mở lại được; khách đã rút thì không có nút Mở lại', async () => {
    const user = await renderPage();
    await openTab(user, 'Đã đóng');
    await items();
    const closedByCustomer = await openComplaint(user, 'Lưu trú');
    expect(within(closedByCustomer).getByRole('button', { name: 'Mở lại' })).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const withdrawn = await openComplaint(user, 'Thái độ nhân viên');
    expect(within(withdrawn).getByText(/· Khách đã rút$/)).toBeInTheDocument();
    expect(within(withdrawn).queryByRole('button', { name: 'Mở lại' })).not.toBeInTheDocument();
  });

});
