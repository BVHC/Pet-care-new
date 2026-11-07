import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { articlexApi } from '../../shared/api/articlex.api';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminMyArticlesPage } from './AdminMyArticlesPage';

// UC80, VET. Bài mẫu của BS. Minh Anh (doctor@store1.vn; articlex.mock.ts): #11 DRAFT, #12 PENDING_REVIEW, #13 REJECTED (có lý do), #14 PUBLISHED.
// BS. Quốc Bảo (#15 chờ duyệt, #16 ẩn) không được thấy bài của người khác.

beforeAll(() => installMockApi(apiClient));
const login = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};
beforeEach(() => login('doctor@store1.vn'));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AdminMyArticlesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const T = {
  draft: 'Chăm sóc răng miệng cho chó: 5 thói quen mỗi tuần',
  pending: 'Dấu hiệu mèo bị viêm đường tiết niệu cần đi khám sớm',
  rejected: 'Cho chó ăn xương có an toàn không?',
  published: 'Lịch tiêm phòng cơ bản cho chó con trong năm đầu',
};
const rowOf = async (title: string) => (await screen.findByText(title)).closest('tr')!;
const buttonsOf = (row: HTMLElement) => within(row).getAllByRole('button').map((b) => b.getAttribute('aria-label') ?? b.textContent);

describe('danh sách bài của tôi', () => {
  it('chỉ thấy bài của mình (4 bài), mỗi bài một trạng thái; bài của bác sĩ khác không hiện', async () => {
    renderPage();
    await rowOf(T.draft);
    expect(within(await rowOf(T.draft)).getByText('Nháp')).toBeInTheDocument();
    expect(within(await rowOf(T.pending)).getByText('Chờ duyệt')).toBeInTheDocument();
    expect(within(await rowOf(T.rejected)).getByText('Bị từ chối')).toBeInTheDocument();
    expect(within(await rowOf(T.published)).getByText('Đã xuất bản')).toBeInTheDocument();
    expect(screen.queryByText('Chế độ ăn cho mèo sau triệt sản')).not.toBeInTheDocument();
    expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(5);
  });

  it('lọc theo trạng thái', async () => {
    const user = renderPage();
    await rowOf(T.draft);
    await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), 'REJECTED');
    await waitFor(() => expect(screen.queryByText(T.draft)).not.toBeInTheDocument());
    expect(screen.getByText(T.rejected)).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), 'HIDDEN');
    expect(await screen.findByText('Không có bài nào ở trạng thái này.')).toBeInTheDocument();
  });

  it('nút theo trạng thái (BR-BV-07): nháp sửa + gửi duyệt; chờ duyệt chỉ rút; bị từ chối chỉ sửa; đã xuất bản chỉ xem', async () => {
    renderPage();
    expect(buttonsOf(await rowOf(T.draft))).toEqual([`Xem ${T.draft}`, `Sửa ${T.draft}`, `Gửi duyệt ${T.draft}`]);
    expect(buttonsOf(await rowOf(T.pending))).toEqual([`Xem ${T.pending}`, `Rút ${T.pending}`]);
    expect(buttonsOf(await rowOf(T.rejected))).toEqual([`Xem lý do từ chối ${T.rejected}`, `Sửa ${T.rejected}`]);
    expect(buttonsOf(await rowOf(T.published))).toEqual([`Xem ${T.published}`]);
  });

  it('bài bị từ chối: mở xem thấy lý do của người duyệt', async () => {
    const user = renderPage();
    await user.click(within(await rowOf(T.rejected)).getByRole('button', { name: /Xem lý do từ chối/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Bài viết của tôi' });
    expect(await within(dialog).findByRole('note')).toHaveTextContent('Cần dẫn nguồn tham khảo và bổ sung cảnh báo riêng về xương nấu chín');
    expect(within(dialog).getByRole('button', { name: 'Sửa bài' })).toBeInTheDocument();
  });

  it('tài khoản không phải VET (SUPER_MANAGER): BE từ chối, trang báo lỗi có Thử lại, không có nút thao tác trên bài', async () => {
    await login('chuoi@petcare.vn');
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByRole('button', { name: 'Thử lại' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Gửi duyệt|Rút/ })).not.toBeInTheDocument();
  });
});

describe('soạn, sửa, gửi duyệt, rút', () => {
  it('soạn bài mới → nháp; gửi duyệt khi chưa chọn chuyên mục bị BR-BV-01 chặn; chọn chuyên mục rồi gửi duyệt thì thành Chờ duyệt', async () => {
    const user = renderPage();
    await rowOf(T.draft);
    await user.click(screen.getByRole('button', { name: /Soạn bài mới/ }));
    const form = await screen.findByRole('dialog', { name: 'Soạn bài mới' });
    await user.click(within(form).getByRole('button', { name: 'Lưu nháp' }));
    expect(within(form).getByText('Nhập tiêu đề.')).toBeInTheDocument(); // chưa gửi request
    await user.type(within(form).getByLabelText('Tiêu đề'), 'Cách cắt móng cho mèo');
    await user.type(within(form).getByLabelText('Nội dung (HTML)'), '<p>Cắt từng chút một.</p>');
    await user.click(within(form).getByRole('button', { name: 'Lưu nháp' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Đã lưu bản nháp “Cách cắt móng cho mèo”');

    const created = await rowOf('Cách cắt móng cho mèo');
    expect(within(created).getByText('Nháp')).toBeInTheDocument();
    await user.click(within(created).getByRole('button', { name: /Gửi duyệt/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('còn thiếu chuyên mục'); // BR-BV-01

    await user.click(within(await rowOf('Cách cắt móng cho mèo')).getByRole('button', { name: /Sửa/ }));
    const edit = await screen.findByRole('dialog', { name: 'Sửa bài viết' });
    await user.selectOptions(await within(edit).findByLabelText('Chuyên mục'), 'Sức khỏe thú y');
    await user.click(within(edit).getByRole('button', { name: 'Lưu thay đổi' }));
    await screen.findByText(/Đã lưu “Cách cắt móng cho mèo”/);
    await user.click(within(await rowOf('Cách cắt móng cho mèo')).getByRole('button', { name: /Gửi duyệt/ }));
    expect(await screen.findByText('Đã gửi duyệt “Cách cắt móng cho mèo”.')).toBeInTheDocument();
    await waitFor(() => expect(within(screen.getByText('Cách cắt móng cho mèo').closest('tr')!).getByText('Chờ duyệt')).toBeInTheDocument());
  });

  it('sửa nháp: form điền sẵn từ chi tiết, đổi tiêu đề rồi lưu', async () => {
    const user = renderPage();
    await user.click(within(await rowOf(T.draft)).getByRole('button', { name: /Sửa/ }));
    const form = await screen.findByRole('dialog', { name: 'Sửa bài viết' });
    const title = await within(form).findByLabelText('Tiêu đề');
    expect(title).toHaveValue(T.draft);
    await user.clear(title);
    await user.type(title, 'Răng miệng chó: 5 thói quen');
    await user.click(within(form).getByRole('button', { name: 'Lưu thay đổi' }));
    expect(await screen.findByText('Đã lưu “Răng miệng chó: 5 thói quen”')).toBeInTheDocument();
    expect(await rowOf('Răng miệng chó: 5 thói quen')).toBeInTheDocument();
  });

  it('sửa bài bị từ chối → về Nháp, hết lý do từ chối, được gửi duyệt lại', async () => {
    const user = renderPage();
    await user.click(within(await rowOf(T.rejected)).getByRole('button', { name: /Sửa/ }));
    const form = await screen.findByRole('dialog', { name: 'Sửa bài viết' });
    const content = await within(form).findByLabelText('Nội dung (HTML)');
    await user.type(content, '<p>Nguồn: hướng dẫn của hội thú y.</p>');
    await user.click(within(form).getByRole('button', { name: 'Lưu thay đổi' }));
    expect(await screen.findByRole('status')).toHaveTextContent('bài về trạng thái Nháp');
    await waitFor(() => expect(within(screen.getByText(T.rejected).closest('tr')!).getByText('Nháp')).toBeInTheDocument());
    expect(buttonsOf(await rowOf(T.rejected))).toContain(`Gửi duyệt ${T.rejected}`);
    expect((await articlexApi.getMy(13)).reviewNote).toBeNull();
  });

  it('rút bài đang chờ duyệt về nháp', async () => {
    const user = renderPage();
    await user.click(within(await rowOf(T.pending)).getByRole('button', { name: /Rút/ }));
    expect(await screen.findByRole('status')).toHaveTextContent(`Đã rút “${T.pending}” về nháp.`);
    await waitFor(() => expect(within(screen.getByText(T.pending).closest('tr')!).getByText('Nháp')).toBeInTheDocument());
  });

  it('bài vừa bị rút ở nơi khác: bấm Rút nhận 409 ngay trên trang', async () => {
    const user = renderPage();
    const row = await rowOf(T.pending);
    await articlexApi.withdraw(12);
    await user.click(within(row).getByRole('button', { name: /Rút/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/DRAFT/);
  });
});
