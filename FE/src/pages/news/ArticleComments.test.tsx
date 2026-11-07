import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { articlexApi } from '../../shared/api/articlex.api';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { ArticleComments } from './ArticleComments';

// UC82, UC81 mở rộng: khối bình luận. Dữ liệu mẫu (articlex.mock.ts): bài 1 (royal-canin-vs-pedigree…) có bình luận #1 và #2 đang hiện, #3 bị ẩn, #5 đã xóa.

beforeAll(() => installMockApi(apiClient));

const SLUG = 'royal-canin-vs-pedigree-chon-hang-nao-cho-cho-cung';
const login = async (email?: string) => {
  useSession.getState().clear();
  if (email) await useSession.getState().login({ email, password: MOCK_PASSWORD });
};
beforeEach(() => login());

function renderComments() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ArticleComments articleId={1} slug={SLUG} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const itemOf = async (text: RegExp) => (await screen.findByText(text)).closest('li')!;

describe('khách chưa đăng nhập', () => {
  it('đọc được bình luận đang hiện (không có bình luận bị ẩn), thấy lời mời đăng nhập, không có ô nhập hay nút thao tác', async () => {
    renderComments();
    expect(await screen.findByText(/rất hợp, lông mượt hơn/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Bình luận (2)' })).toBeInTheDocument();
    expect(screen.queryByText(/mua sỉ thức ăn hạt/)).not.toBeInTheDocument();
    expect(screen.getByText('(đã sửa)')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Đăng nhập' })).toHaveAttribute('href', '/auth/login');
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Sửa|Xóa|Ẩn/ })).not.toBeInTheDocument();
  });

  it('bài chưa xuất bản (404): ghi chú nhẹ, không báo lỗi', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <ArticleComments articleId={9999} slug="khong-co-bai-nay" />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    expect(await screen.findByText('Bài viết này chưa mở bình luận.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('khách hàng (CUSTOMER)', () => {
  beforeEach(() => login('khach@petcare.vn'));

  it('đăng, sửa rồi xóa bình luận của mình; bình luận của người khác không có nút', async () => {
    const user = renderComments();
    await screen.findByText(/rất hợp, lông mượt hơn/);
    expect(screen.queryByRole('button', { name: /Sửa|Xóa/ })).not.toBeInTheDocument();

    await user.type(screen.getByRole('textbox', { name: 'Viết bình luận' }), 'Bài viết rất hữu ích!');
    await user.click(screen.getByRole('button', { name: 'Đăng bình luận' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Đã đăng bình luận.');
    const mine = await itemOf(/Bài viết rất hữu ích!/);
    expect(screen.getByRole('textbox', { name: 'Viết bình luận' })).toHaveValue('');

    await user.click(within(mine).getByRole('button', { name: /Sửa/ }));
    const edit = within(mine).getByRole('textbox', { name: 'Sửa bình luận' });
    await user.clear(edit);
    await user.type(edit, 'Đã chỉnh lại nội dung.');
    await user.click(within(mine).getByRole('button', { name: 'Lưu' }));
    const edited = await itemOf(/Đã chỉnh lại nội dung\./);
    expect(within(edited).getByText('(đã sửa)')).toBeInTheDocument();

    await user.click(within(edited).getByRole('button', { name: /Xóa/ }));
    await user.click(within(edited).getByRole('button', { name: 'Xác nhận xóa' }));
    await waitFor(() => expect(screen.queryByText(/Đã chỉnh lại nội dung\./)).not.toBeInTheDocument());
    expect(screen.getByRole('status')).toHaveTextContent('Đã xóa bình luận.');
  });

  it('quá 10 bình luận một giờ: hiện thông báo lỗi của BE ngay chỗ đăng, nội dung vẫn còn trong ô nhập', async () => {
    for (let i = 0; i < 10; i++) await articlexApi.createComment(1, `Bình luận số ${i + 1}`);
    const user = renderComments();
    await screen.findByText(/rất hợp, lông mượt hơn/);
    await user.type(screen.getByRole('textbox', { name: 'Viết bình luận' }), 'Bình luận thứ mười một');
    await user.click(screen.getByRole('button', { name: 'Đăng bình luận' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('quá 10 lần trong một giờ');
    expect(screen.getByRole('textbox', { name: 'Viết bình luận' })).toHaveValue('Bình luận thứ mười một');
  });

  it('phân trang 10 bình luận một trang, cũ nhất trước; không có nút Ẩn', async () => {
    for (let i = 0; i < 10; i++) await articlexApi.createComment(1, `Bình luận số ${i + 1}`);
    const user = renderComments();
    expect(await screen.findByText('Trang 1/2')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(10);
    expect(screen.queryByRole('button', { name: /Ẩn/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Sau' }));
    expect(await screen.findByText('Trang 2/2')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });
});

describe('SUPER_MANAGER (ẩn bình luận)', () => {
  beforeEach(() => login('chuoi@petcare.vn'));

  it('thấy nút Ẩn trên mọi bình luận, không có ô đăng; ẩn kèm lý do thì bình luận biến mất', async () => {
    const user = renderComments();
    const target = await itemOf(/rất hợp, lông mượt hơn/);
    expect(screen.queryByRole('textbox', { name: 'Viết bình luận' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /Ẩn/ })).toHaveLength(2);

    await user.click(within(target).getByRole('button', { name: /Ẩn/ }));
    expect(within(target).getByRole('button', { name: 'Xác nhận ẩn' })).toBeDisabled(); // BR-BV-08: bắt buộc có lý do
    await user.type(within(target).getByRole('textbox', { name: 'Lý do ẩn' }), 'Quảng cáo');
    await user.click(within(target).getByRole('button', { name: 'Xác nhận ẩn' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Đã ẩn bình luận #1');
    await waitFor(() => expect(screen.queryByText(/rất hợp, lông mượt hơn/)).not.toBeInTheDocument());
  });

  it('vừa ẩn nhầm: nút Bỏ ẩn trong thông báo hoàn tác ngay, bình luận hiện lại', async () => {
    const user = renderComments();
    const target = await itemOf(/rất hợp, lông mượt hơn/);
    await user.click(within(target).getByRole('button', { name: /Ẩn/ }));
    await user.type(within(target).getByRole('textbox', { name: 'Lý do ẩn' }), 'Ẩn nhầm');
    await user.click(within(target).getByRole('button', { name: 'Xác nhận ẩn' }));
    await waitFor(() => expect(screen.queryByText(/rất hợp, lông mượt hơn/)).not.toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Bỏ ẩn' }));
    expect(await screen.findByText('Đã bỏ ẩn bình luận #1.')).toBeInTheDocument();
    expect(await screen.findByText(/rất hợp, lông mượt hơn/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Bỏ ẩn' })).not.toBeInTheDocument();
  });

  it('bình luận đã bị người khác ẩn trước: BE trả 409, thông báo hiện ngay chỗ thao tác', async () => {
    const user = renderComments();
    const target = await itemOf(/rất hợp, lông mượt hơn/);
    await articlexApi.hideComment(1, 'Người khác ẩn trước');

    await user.click(within(target).getByRole('button', { name: /Ẩn/ }));
    await user.type(within(target).getByRole('textbox', { name: 'Lý do ẩn' }), 'Quảng cáo');
    await user.click(within(target).getByRole('button', { name: 'Xác nhận ẩn' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/HIDDEN/);
  });
});

describe('vai trò khác (bác sĩ)', () => {
  it('chỉ đọc: không lời mời đăng nhập, không ô nhập, không nút', async () => {
    await login('doctor@store1.vn');
    renderComments();
    await screen.findByText(/rất hợp, lông mượt hơn/);
    expect(screen.queryByRole('link', { name: 'Đăng nhập' })).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Sửa|Xóa|Ẩn/ })).not.toBeInTheDocument();
  });
});
