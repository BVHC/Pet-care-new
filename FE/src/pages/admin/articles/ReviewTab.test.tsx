import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../../shared/api/axios';
import { articlexApi } from '../../../shared/api/articlex.api';
import { contentApi } from '../../../shared/api/content.api';
import { MOCK_PASSWORD } from '../../../shared/api/mock/identity.mock';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { useSession } from '../../../shared/stores/session.store';
import { AdminArticlesPage } from '../AdminArticlesPage';

// UC81 mở rộng: tab "Chờ duyệt". Bài mẫu của bác sĩ (articlex.mock.ts, tạo lười khi truy vấn PENDING_REVIEW): #12 (BS. Minh Anh) và #15 (BS. Quốc Bảo) chờ duyệt;
// #13 bị từ chối, #14 đã xuất bản, #16 đã ẩn. Bình luận mẫu: #1 đang hiện, #3 bị ẩn.

beforeAll(() => installMockApi(apiClient));
beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'chuoi@petcare.vn', password: MOCK_PASSWORD });
});

function renderTab() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/articles/review']}>
        <Routes>
          <Route path="/admin/articles/:tab?" element={<AdminArticlesPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const T12 = 'Dấu hiệu mèo bị viêm đường tiết niệu cần đi khám sớm';
const T15 = 'Chế độ ăn cho mèo sau triệt sản';
const rowOf = async (title: string) => (await screen.findByText(title)).closest('tr')!;
const openReview = async (user: ReturnType<typeof renderTab>, title: string) => {
  await user.click(within(await rowOf(title)).getByRole('button', { name: /Xem và duyệt/ }));
  return screen.findByRole('dialog', { name: 'Duyệt bài viết' });
};

describe('danh sách chờ duyệt', () => {
  it('có tab "Chờ duyệt" và liệt kê đúng các bài PENDING_REVIEW kèm chuyên mục', async () => {
    renderTab();
    expect(screen.getByRole('tab', { name: 'Chờ duyệt' })).toHaveAttribute('data-state', 'active');
    const table = await screen.findByRole('table', { name: 'Bài chờ duyệt' });
    await within(table).findByText(T12);
    expect(within(table).getAllByRole('row')).toHaveLength(3); // tiêu đề + 2 bài
    expect(within(await rowOf(T15)).getByText('Chăm sóc hằng ngày')).toBeInTheDocument();
    expect(within(table).queryByText(/cho chó ăn xương/i)).not.toBeInTheDocument(); // bài bị từ chối không nằm trong hàng chờ
  });

  it('mở chi tiết thì thấy tác giả (danh sách không có tác giả) và nội dung', async () => {
    const user = renderTab();
    const dialog = await openReview(user, T12);
    expect(await within(dialog).findByText('BS. Minh Anh')).toBeInTheDocument();
    expect(within(dialog).getByText('Sức khỏe thú y')).toBeInTheDocument();
    expect(within(dialog).getByTitle('Nội dung bài viết')).toHaveAttribute('srcdoc', expect.stringContaining('Viêm đường tiết niệu ở mèo'));
    expect(within(dialog).getByRole('button', { name: 'Duyệt và xuất bản' })).toBeEnabled();
  });
});

describe('duyệt và từ chối', () => {
  it('duyệt: bài PUBLISHED hiện ở trang công khai, rời hàng chờ, có thông báo', async () => {
    const user = renderTab();
    const dialog = await openReview(user, T12);
    await user.click(await within(dialog).findByRole('button', { name: 'Duyệt và xuất bản' }));
    expect(await screen.findByRole('status')).toHaveTextContent(`Đã duyệt và xuất bản bài “${T12}”`);
    await waitFor(() => expect(screen.queryByText(T12)).not.toBeInTheDocument());
    expect((await contentApi.getPublicArticle('dau-hieu-meo-viem-duong-tiet-nieu-can-di-kham-som')).title).toBe(T12);
  });

  it('từ chối: bắt buộc có lý do (BR-BV-06); sau khi nhập thì bài sang REJECTED kèm lý do cho tác giả', async () => {
    const user = renderTab();
    const dialog = await openReview(user, T15);
    await user.click(await within(dialog).findByRole('button', { name: 'Từ chối' }));
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận từ chối' }));
    expect(within(dialog).getByText(/Nhập lý do từ chối/)).toBeInTheDocument();

    await user.type(within(dialog).getByRole('textbox', { name: 'Lý do từ chối' }), 'Thiếu nguồn tham khảo');
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận từ chối' }));
    expect(await screen.findByRole('status')).toHaveTextContent(`Đã từ chối bài “${T15}”`);
    await waitFor(() => expect(screen.queryByText(T15)).not.toBeInTheDocument());
    const rejected = await contentApi.listArticles({ status: 'REJECTED' });
    expect(rejected.content.some((a) => a.title === T15)).toBe(true);
  });

  it('bài đã được duyệt ở nơi khác sau khi mở: bấm Duyệt nhận 409 ngay trong hộp thoại, bài vẫn mở', async () => {
    const user = renderTab();
    const dialog = await openReview(user, T12);
    const approve = await within(dialog).findByRole('button', { name: 'Duyệt và xuất bản' });
    await articlexApi.approve(12);

    await user.click(approve);
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/PENDING_REVIEW|PUBLISHED/);
    expect(screen.getByRole('dialog', { name: 'Duyệt bài viết' })).toBeInTheDocument();
  });

  it('bài không còn chờ duyệt: chỉ xem, không có nút Duyệt / Từ chối', async () => {
    const user = renderTab();
    await screen.findByText(T12);
    await articlexApi.approve(12);
    const dialog = await openReview(user, T12);
    await within(dialog).findByText('Đang đăng');
    expect(within(dialog).queryByRole('button', { name: /Duyệt và xuất bản|Từ chối/ })).not.toBeInTheDocument();
  });
});

describe('bỏ ẩn bình luận theo mã', () => {
  it('bỏ ẩn bình luận #3 thì bình luận hiện lại ở trang công khai; chưa nhập mã thì nút bị khóa', async () => {
    const user = renderTab();
    const button = await screen.findByRole('button', { name: 'Bỏ ẩn' });
    expect(button).toBeDisabled();
    await user.type(screen.getByRole('textbox', { name: 'Mã bình luận' }), '3');
    await user.click(button);
    expect(await screen.findByText('Đã bỏ ẩn bình luận #3 (bài #1).')).toBeInTheDocument();
    const page = await articlexApi.listComments('royal-canin-vs-pedigree-chon-hang-nao-cho-cho-cung');
    expect(page.content.map((c) => c.commentId)).toContain(3);
  });

  it('bình luận đang hiện (không bị ẩn): BE trả 409, thông báo lỗi hiện ngay dưới ô nhập', async () => {
    const user = renderTab();
    await user.type(await screen.findByRole('textbox', { name: 'Mã bình luận' }), '1');
    await user.click(screen.getByRole('button', { name: 'Bỏ ẩn' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/ACTIVE/);
  });
});
