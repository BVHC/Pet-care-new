import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { contentApi } from '../../shared/api/content.api';
import { CONTENT_STATE_KEY, seedContent } from '../../shared/api/mock/content.seed';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminArticlesPage } from './AdminArticlesPage';

// Dữ liệu mẫu ở shared/api/mock/content.seed.ts: 4 chuyên mục (#4 ẩn), 10 bài (#6 DRAFT, #8 HIDDEN, #10 thuộc chuyên mục ẩn).
// Luật BE ở content.mock.ts. Mock đọc lại localStorage mỗi request nên test đổi state "sau lưng" giao diện để dựng ca bản tải cũ.
beforeAll(() => installMockApi(apiClient));

beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'chuoi@petcare.vn', password: MOCK_PASSWORD });
});

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>;
}

function renderAt(path = '/admin/articles/articles') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route
            path="/admin/articles/:tab?"
            element={
              <>
                <AdminArticlesPage />
                <Where />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return client;
}

const T = {
  royal: 'Royal Canin vs Pedigree: nên chọn hãng nào cho chó cưng?',
  vaccine: 'Vaccine 5 bệnh cho chó con: lịch tiêm và những điều cần biết',
  draft: /^Mèo cào sofa: 5 cách ngăn chặn/,
  hidden: /^Chó sợ pháo hoa/,
};
const draftRow = async () => (await screen.findByText(T.draft)).closest('tr')!;
const rowOf = async (title: string | RegExp) => (await screen.findByText(title)).closest('tr')!;
const cellsOf = (row: HTMLElement) => within(row).getAllByRole('cell');
const bodyRows = () => within(screen.getAllByRole('table')[0]).getAllByRole('row').slice(1);

/** Ghi state mock trước khi render (như một bản tải cũ hoặc người khác đã sửa). */
function withState(change: (state: ReturnType<typeof seedContent>) => void) {
  const state = seedContent();
  change(state);
  localStorage.setItem(CONTENT_STATE_KEY, JSON.stringify(state));
}

/** 15 bài PUBLISHED thêm vào seed: tổng 25 bài (23 PUBLISHED, 1 DRAFT, 1 HIDDEN), quá 1 trang (20). */
function withManyArticles() {
  withState((state) => {
    for (let i = 1; i <= 15; i++) {
      state.articles.push({
        ...state.articles[0],
        articleId: 200 + i,
        title: `Bài mẫu số ${String(i).padStart(2, '0')}`,
        slug: `bai-mau-so-${i}`,
        updatedAt: `2026-05-${String(i).padStart(2, '0')}T09:00:00+07:00`,
      });
    }
  });
}

type User = ReturnType<typeof userEvent.setup>;

async function openNewArticle(user: User) {
  await user.click(await screen.findByRole('button', { name: 'Soạn bài mới' }));
  return screen.findByRole('dialog');
}

async function openEdit(user: User, title: string | RegExp) {
  await user.click(within(await rowOf(title)).getByRole('button', { name: /^Sửa / }));
  await screen.findByLabelText('Tiêu đề'); // chi tiết (có content) đã tải xong; khung chờ và khung form là hai dialog khác nhau
  return screen.getByRole('dialog');
}

async function fillNewArticle(user: User, form: HTMLElement, fields: { title: string; content: string; category?: string }) {
  await user.selectOptions(within(form).getByLabelText('Chuyên mục'), fields.category ?? 'Sức khỏe thú y');
  await user.type(within(form).getByLabelText('Tiêu đề'), fields.title);
  await user.click(within(form).getByLabelText('Nội dung (HTML)'));
  await user.paste(fields.content);
}

const submit = (user: User, form: HTMLElement, name: string) => user.click(within(form).getByRole('button', { name }));

describe('khung trang và tab (UC81)', () => {
  it('giữ <h1> "Bài viết"; /admin/articles mở tab Bài viết', async () => {
    renderAt('/admin/articles');
    expect(screen.getByRole('heading', { level: 1, name: 'Bài viết' })).toBeInTheDocument();
    expect(await screen.findByText(T.royal)).toBeInTheDocument();
    expect(screen.getByTestId('where')).toHaveTextContent('/admin/articles/articles');
    expect(screen.getByRole('tab', { name: 'Bài viết' })).toHaveAttribute('data-state', 'active');
  });

  it('URL tab sai chuyển về tab Bài viết; bấm tab đổi URL', async () => {
    const user = userEvent.setup();
    renderAt('/admin/articles/khong-co');
    expect(await screen.findByText(T.royal)).toBeInTheDocument();
    expect(screen.getByTestId('where')).toHaveTextContent('/admin/articles/articles');

    await user.click(screen.getByRole('tab', { name: 'Chuyên mục' }));
    expect(screen.getByTestId('where')).toHaveTextContent('/admin/articles/categories');
    expect(await screen.findByText('Kiến thức nuôi thú cưng')).toBeInTheDocument();
  });
});

describe('tab Bài viết: danh sách, lọc, phân trang', () => {
  it('liệt kê 10 bài theo cập nhật mới nhất; ghép tên chuyên mục (kể cả chuyên mục ẩn), huy hiệu trạng thái, ngày giờ Việt Nam', async () => {
    renderAt();
    const first = await rowOf(T.royal);
    expect(bodyRows()).toHaveLength(10);
    expect(within(bodyRows()[0]).getByText(T.royal)).toBeInTheDocument();
    const [title, category, status, dates] = cellsOf(first);
    expect(title).toHaveTextContent('royal-canin-vs-pedigree-chon-hang-nao-cho-cho-cung');
    expect(category).toHaveTextContent('Kiến thức nuôi thú cưng');
    expect(status).toHaveTextContent('Đang đăng');
    expect(dates).toHaveTextContent('28/07/2026 09:00'); // cập nhật
    expect(dates).toHaveTextContent('Đăng lần đầu 28/07/2026');

    const draft = cellsOf(await draftRow());
    expect(draft[2]).toHaveTextContent('Nháp');
    expect(draft[3]).toHaveTextContent('12/07/2026 07:00');
    expect(draft[3]).toHaveTextContent('Chưa xuất bản');
    expect(cellsOf(await rowOf(T.hidden))[2]).toHaveTextContent('Đã ẩn');
    expect(cellsOf(await rowOf(/^Cách chọn khách sạn thú cưng/))[1]).toHaveTextContent('Lưu trú & dịch vụ');
  });

  it('đang tải thì có chữ chờ; lỗi thì có thông báo và nút Thử lại', async () => {
    const user = userEvent.setup();
    vi.spyOn(contentApi, 'listArticles').mockRejectedValueOnce(new ApiError('ACCESS_DENIED', 'Không có quyền thực hiện thao tác này', 403));
    renderAt();
    expect(screen.getByText('Đang tải…')).toBeInTheDocument();
    expect(await screen.findByRole('alert')).toHaveTextContent('Không có quyền thực hiện thao tác này');
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByText(T.royal)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('chưa có bài nào: câu rỗng có nghĩa kèm lối soạn bài đầu tiên', async () => {
    withState((state) => {
      state.articles = [];
    });
    renderAt();
    expect(await screen.findByText(/Chưa có bài viết nào/)).toBeInTheDocument();
    expect(screen.queryByText('Không có bài viết nào khớp bộ lọc.')).not.toBeInTheDocument();
  });

  it('lọc theo trạng thái và chuyên mục; không khớp thì báo "không khớp bộ lọc"', async () => {
    const user = userEvent.setup();
    renderAt();
    await screen.findByText(T.royal);

    await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), 'Nháp');
    await waitFor(() => expect(bodyRows()).toHaveLength(1));
    expect(bodyRows()[0]).toHaveTextContent('Mèo cào sofa');

    await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), 'Tất cả trạng thái');
    // user-event so tên option bằng innerHTML (& thành &amp;) nên chọn theo value; tên hiển thị kiểm riêng ở test form
    await user.selectOptions(screen.getByLabelText('Lọc chuyên mục'), '4');
    expect(within(screen.getByLabelText('Lọc chuyên mục')).getByRole('option', { name: 'Lưu trú & dịch vụ (ẩn)' })).toBeInTheDocument();
    await waitFor(() => expect(bodyRows()).toHaveLength(1));
    expect(bodyRows()[0]).toHaveTextContent('Cách chọn khách sạn thú cưng');

    await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), 'Nháp');
    expect(await screen.findByText('Không có bài viết nào khớp bộ lọc.')).toBeInTheDocument();
  });

  it('tìm theo tiêu đề (ô tối đa 100 ký tự, chờ ~300 ms rồi mới gọi)', async () => {
    const user = userEvent.setup();
    const list = vi.spyOn(contentApi, 'listArticles');
    renderAt();
    await screen.findByText(T.royal);
    const search = screen.getByLabelText('Tìm theo tiêu đề');
    expect(search).toHaveAttribute('maxlength', '100');

    await user.type(search, 'vaccine');
    await waitFor(() => expect(bodyRows()).toHaveLength(1));
    expect(bodyRows()[0]).toHaveTextContent(T.vaccine);
    expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'vaccine', page: 0 }));
    // gõ từng chữ không bắn từng request: chỉ một lần cho "vaccine" (cộng lần tải đầu)
    expect(list.mock.calls.filter(([query]) => query?.q !== undefined)).toHaveLength(1);
  });

  it('phân trang 20 bài/trang; đổi bộ lọc thì về trang đầu', async () => {
    const user = userEvent.setup();
    withManyArticles();
    renderAt();
    await screen.findByText(T.royal);
    expect(bodyRows()).toHaveLength(20);
    expect(screen.getByText('Trang 1/2 · 25 bài viết')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Trước' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Sau' }));
    expect(await screen.findByText('Trang 2/2 · 25 bài viết')).toBeInTheDocument();
    expect(bodyRows()).toHaveLength(5);
    expect(screen.getByRole('button', { name: 'Sau' })).toBeDisabled();

    await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), 'Nháp'); // chỉ 1 bài: nếu còn ở trang 2 sẽ rỗng
    expect(await screen.findByText(T.draft)).toBeInTheDocument();
    expect(bodyRows()).toHaveLength(1);
  });
});

describe('soạn và sửa bài', () => {
  it('soạn bài mới: slug tự gợi ý từ tiêu đề (đ → d), tác giả mặc định là người đang đăng nhập; lưu thành bản nháp', async () => {
    const user = userEvent.setup();
    renderAt();
    const form = await openNewArticle(user);
    expect(within(form).getByLabelText('Tác giả hiển thị')).toHaveValue('Đỗ Thanh Hà');

    await fillNewArticle(user, form, { title: 'Đường đi an toàn cho thú cưng', content: '<p>Xin chào</p>' });
    expect(within(form).getByLabelText('Đường dẫn (slug)')).toHaveValue('duong-di-an-toan-cho-thu-cung');
    await submit(user, form, 'Lưu nháp');

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByRole('status')).toHaveTextContent(/Đã lưu bản nháp/);
    const row = await rowOf('Đường đi an toàn cho thú cưng');
    expect(cellsOf(row)[2]).toHaveTextContent('Nháp');
    expect(cellsOf(row)[0]).toHaveTextContent('duong-di-an-toan-cho-thu-cung');
    expect(cellsOf(row)[1]).toHaveTextContent('Sức khỏe thú y');
    expect(await contentApi.getArticle(101)).toMatchObject({ authorDisplayName: 'Đỗ Thanh Hà', status: 'DRAFT', content: '<p>Xin chào</p>' });
  });

  it('slug theo tiêu đề cho tới khi người dùng tự sửa; xóa trắng ô slug thì gợi ý chạy lại', async () => {
    const user = userEvent.setup();
    renderAt();
    const form = await openNewArticle(user);
    const title = within(form).getByLabelText('Tiêu đề');
    const slug = within(form).getByLabelText('Đường dẫn (slug)');

    await user.type(title, 'Đường đi');
    expect(slug).toHaveValue('duong-di');
    await user.type(title, ' mới');
    expect(slug).toHaveValue('duong-di-moi');

    await user.clear(slug);
    await user.type(slug, 'tu-dat');
    await user.type(title, ' nữa');
    expect(slug).toHaveValue('tu-dat');

    await user.clear(slug); // xóa trắng: ô để trống để gõ lại từ đầu, gợi ý chạy lại khi tiêu đề đổi
    expect(slug).toHaveValue('');
    await user.type(title, '!');
    expect(slug).toHaveValue('duong-di-moi-nua');
  });

  it('thiếu trường bắt buộc: báo ngay dưới từng ô, không gửi request, form vẫn mở', async () => {
    const user = userEvent.setup();
    const create = vi.spyOn(contentApi, 'createArticle');
    renderAt();
    const form = await openNewArticle(user);
    await user.clear(within(form).getByLabelText('Tác giả hiển thị'));
    await submit(user, form, 'Lưu nháp');

    for (const message of ['Chọn chuyên mục.', 'Nhập tiêu đề.', 'Nhập đường dẫn.', 'Nhập nội dung bài viết.', 'Nhập tên tác giả hiển thị.']) {
      expect(within(form).getByText(message)).toBeInTheDocument();
    }
    expect(create).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('slug sai mẫu và ảnh bìa không phải https bị báo trước khi gửi', async () => {
    const user = userEvent.setup();
    const create = vi.spyOn(contentApi, 'createArticle');
    renderAt();
    const form = await openNewArticle(user);
    await fillNewArticle(user, form, { title: 'Bài thử', content: '<p>x</p>' });
    const slug = within(form).getByLabelText('Đường dẫn (slug)');
    await user.clear(slug);
    await user.type(slug, 'Sai Slug');
    await user.type(within(form).getByLabelText('Ảnh bìa (URL https)'), 'http://e.com/a.png');
    await submit(user, form, 'Lưu nháp');

    expect(within(form).getByText('Đường dẫn chỉ gồm chữ thường không dấu, số và dấu gạch ngang.')).toBeInTheDocument();
    expect(within(form).getByText('Ảnh bìa phải là địa chỉ https.')).toBeInTheDocument();
    expect(create).not.toHaveBeenCalled();
  });

  it('tóm tắt có bộ đếm n/500 và giới hạn 500 ký tự', async () => {
    const user = userEvent.setup();
    renderAt();
    const form = await openNewArticle(user);
    const summary = within(form).getByLabelText('Tóm tắt');
    expect(summary).toHaveAttribute('maxlength', '500');
    expect(within(form).getByText('0/500')).toBeInTheDocument();
    await user.type(summary, 'Xin chào');
    expect(within(form).getByText('8/500')).toBeInTheDocument();
  });

  it('lỗi 400 của BE hiện ngay trong form và không đóng form (nội dung chỉ có script bị lọc hết → BR-BV-01)', async () => {
    const user = userEvent.setup();
    renderAt();
    const form = await openNewArticle(user);
    await fillNewArticle(user, form, { title: 'Bài chỉ có script', content: '<script>alert(1)</script>' });
    await submit(user, form, 'Lưu nháp');

    expect(await within(form).findByRole('alert')).toHaveTextContent('Nội dung bài viết trống sau khi lọc');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(within(form).getByLabelText('Tiêu đề')).toHaveValue('Bài chỉ có script'); // dữ liệu đã nhập còn nguyên
    expect(await contentApi.listArticles({})).toMatchObject({ totalElements: 10 });
  });

  it('slug trùng bài khác → lỗi BR-BV-01 trong form', async () => {
    const user = userEvent.setup();
    renderAt();
    const form = await openNewArticle(user);
    await fillNewArticle(user, form, { title: 'Bài trùng', content: '<p>x</p>' });
    const slug = within(form).getByLabelText('Đường dẫn (slug)');
    await user.clear(slug);
    await user.type(slug, 'royal-canin-vs-pedigree-chon-hang-nao-cho-cho-cung');
    await submit(user, form, 'Lưu nháp');
    expect(await within(form).findByRole('alert')).toHaveTextContent('Đường dẫn bài viết đã tồn tại');
  });

  it('lỗi VALIDATION_FAILED theo trường của BE hiện ngay dưới ô đó', async () => {
    const user = userEvent.setup();
    vi.spyOn(contentApi, 'createArticle').mockRejectedValueOnce(
      new ApiError('VALIDATION_FAILED', 'coverImageUrl: Ảnh bìa phải là địa chỉ https; summary: Tóm tắt tối đa 500 ký tự', 400),
    );
    renderAt();
    const form = await openNewArticle(user);
    await fillNewArticle(user, form, { title: 'Bài thử', content: '<p>x</p>' });
    await submit(user, form, 'Lưu nháp');

    expect(await within(form).findByText('Ảnh bìa phải là địa chỉ https')).toBeInTheDocument();
    expect(within(form).getByText('Tóm tắt tối đa 500 ký tự')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('chuyên mục ẩn ghi "(ẩn)" trong ô chọn và vẫn chọn được', async () => {
    const user = userEvent.setup();
    renderAt();
    const form = await openNewArticle(user);
    const select = within(form).getByLabelText('Chuyên mục');
    expect(within(select).getByRole('option', { name: 'Lưu trú & dịch vụ (ẩn)' })).toBeInTheDocument();
    expect(within(select).getByRole('option', { name: 'Sức khỏe thú y' })).toBeInTheDocument();
    await user.selectOptions(select, '4');
    expect(select).toHaveValue('4');
  });

  it('Sửa lấy chi tiết (có content) rồi điền sẵn form; gửi PATCH chỉ trường đã đổi', async () => {
    const user = userEvent.setup();
    const update = vi.spyOn(contentApi, 'updateArticle');
    renderAt();
    const form = await openEdit(user, T.draft);
    expect(within(form).getByLabelText('Tiêu đề')).toHaveValue('Mèo cào sofa: 5 cách ngăn chặn mà không cần "bỏ rơi" cả căn phòng');
    expect((within(form).getByLabelText('Nội dung (HTML)') as HTMLTextAreaElement).value).toContain('<h2>Tại sao mèo cào</h2>');
    expect(within(form).getByLabelText('Tác giả hiển thị')).toHaveValue('Phương Linh');
    expect(within(form).getByLabelText('Chuyên mục')).toHaveValue('1');

    const title = within(form).getByLabelText('Tiêu đề');
    await user.clear(title);
    await user.type(title, 'Mèo cào sofa: 5 cách xử lý');
    await submit(user, form, 'Lưu thay đổi');

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(update).toHaveBeenCalledTimes(1);
    expect(update).toHaveBeenCalledWith(6, { title: 'Mèo cào sofa: 5 cách xử lý' });
    expect(screen.getByRole('status')).toHaveTextContent(/Đã lưu/);
    expect(await screen.findByText('Mèo cào sofa: 5 cách xử lý')).toBeInTheDocument();
  });

  it('mở lại sau khi lưu thì thấy giá trị đã lưu (chi tiết luôn lấy mới)', async () => {
    const user = userEvent.setup();
    renderAt();
    let form = await openEdit(user, T.draft);
    const summary = within(form).getByLabelText('Tóm tắt');
    await user.clear(summary);
    await user.type(summary, 'Tóm tắt đã sửa');
    await submit(user, form, 'Lưu thay đổi');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    form = await openEdit(user, T.draft);
    expect(within(form).getByLabelText('Tóm tắt')).toHaveValue('Tóm tắt đã sửa');
  });

  it('đóng form rồi người khác sửa bài: mở lại thấy bản mới nhất, không phải bản đã lưu trong cache', async () => {
    const user = userEvent.setup();
    renderAt();
    let form = await openEdit(user, T.draft);
    await user.click(within(form).getByRole('button', { name: 'Hủy' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    await contentApi.updateArticle(6, { summary: 'Người khác vừa sửa tóm tắt' });
    form = await openEdit(user, T.draft);
    expect(within(form).getByLabelText('Tóm tắt')).toHaveValue('Người khác vừa sửa tóm tắt');
  });

  it('xóa ảnh bìa và tóm tắt gửi chuỗi rỗng; ảnh bìa cũ không phải https (bài mẫu) không bị chặn khi không động tới', async () => {
    const user = userEvent.setup();
    const update = vi.spyOn(contentApi, 'updateArticle');
    renderAt();
    // bài #1 có ảnh bìa là đường dẫn nội bộ /imgs/… của mock
    let form = await openEdit(user, T.royal);
    const title = within(form).getByLabelText('Tiêu đề');
    await user.type(title, ' (cập nhật)');
    await submit(user, form, 'Lưu thay đổi');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(update).toHaveBeenLastCalledWith(1, { title: `${T.royal} (cập nhật)` });

    form = await openEdit(user, /\(cập nhật\)$/);
    await user.clear(within(form).getByLabelText('Ảnh bìa (URL https)'));
    await user.clear(within(form).getByLabelText('Tóm tắt'));
    await submit(user, form, 'Lưu thay đổi');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(update).toHaveBeenLastCalledWith(1, { coverImageUrl: '', summary: '' });
    expect(await contentApi.getArticle(1)).toMatchObject({ coverImageUrl: null, summary: null });
  });

  it('không đổi gì thì không gửi request', async () => {
    const user = userEvent.setup();
    const update = vi.spyOn(contentApi, 'updateArticle');
    renderAt();
    const form = await openEdit(user, T.draft);
    await submit(user, form, 'Lưu thay đổi');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(update).not.toHaveBeenCalled();
    expect(screen.getByRole('status')).toHaveTextContent('Không có thay đổi nào để lưu.');
  });

  it('bài nháp sửa được slug, đổi slug trùng thì BE từ chối và form vẫn mở', async () => {
    const user = userEvent.setup();
    renderAt();
    const form = await openEdit(user, T.draft);
    const slug = within(form).getByLabelText('Đường dẫn (slug)');
    expect(slug).toBeEnabled();
    await user.clear(slug);
    await user.type(slug, 'royal-canin-vs-pedigree-chon-hang-nao-cho-cho-cung');
    await submit(user, form, 'Lưu thay đổi');
    expect(await within(form).findByRole('alert')).toHaveTextContent('Đường dẫn bài viết đã tồn tại');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('bài đã từng xuất bản khóa slug (kể cả bài đang ẩn) kèm lời giải thích; vẫn sửa được tiêu đề và không gửi slug', async () => {
    const user = userEvent.setup();
    const update = vi.spyOn(contentApi, 'updateArticle');
    renderAt();
    let form = await openEdit(user, T.hidden);
    expect(within(form).getByLabelText('Đường dẫn (slug)')).toBeDisabled();
    expect(within(form).getByText(/Đã khóa vì bài từng được xuất bản/)).toBeInTheDocument();
    await user.click(within(form).getByRole('button', { name: 'Hủy' }));

    form = await openEdit(user, T.royal);
    expect(within(form).getByLabelText('Đường dẫn (slug)')).toBeDisabled();
    const title = within(form).getByLabelText('Tiêu đề');
    await user.type(title, '!');
    await submit(user, form, 'Lưu thay đổi');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(update).toHaveBeenCalledWith(1, { title: `${T.royal}!` });
  });

  it('chi tiết không tải được: báo lỗi trong khung, có Thử lại và Đóng', async () => {
    const user = userEvent.setup();
    vi.spyOn(contentApi, 'getArticle').mockRejectedValueOnce(new ApiError('RESOURCE_NOT_FOUND', 'Không tìm thấy bài viết #6', 404));
    renderAt();
    await user.click(within(await draftRow()).getByRole('button', { name: /^Sửa / }));
    expect(await screen.findByText('Không tìm thấy bài viết #6')).toBeInTheDocument();
    expect(within(screen.getByRole('dialog')).getByRole('button', { name: 'Đóng' })).toBeInTheDocument();
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByLabelText('Tiêu đề')).toBeInTheDocument();
  });
});

describe('xem trước nội dung', () => {
  it('hiện trong iframe sandbox="" (không allow-scripts, không allow-same-origin); quay lại soạn thì nội dung còn nguyên', async () => {
    const user = userEvent.setup();
    renderAt();
    const form = await openNewArticle(user);
    const html = '<h2>Xin chào</h2><p onclick="x()">Đoạn <b>đậm</b></p><script>alert(1)</script>';
    await user.click(within(form).getByLabelText('Nội dung (HTML)'));
    await user.paste(html);
    expect(within(form).queryByTitle('Xem trước bài viết')).not.toBeInTheDocument();

    await user.click(within(form).getByRole('button', { name: 'Xem trước' }));
    const frame = within(form).getByTitle('Xem trước bài viết') as HTMLIFrameElement;
    expect(frame.tagName).toBe('IFRAME');
    expect(frame.getAttribute('sandbox')).toBe('');
    expect(frame.getAttribute('srcdoc')).toContain(html);
    expect(frame.getAttribute('srcdoc')).toContain('Content-Security-Policy');
    expect(within(form).getByLabelText('Nội dung (HTML)')).not.toBeVisible();

    await user.click(within(form).getByRole('button', { name: 'Tiếp tục soạn' }));
    expect(within(form).queryByTitle('Xem trước bài viết')).not.toBeInTheDocument();
    expect(within(form).getByLabelText('Nội dung (HTML)')).toHaveValue(html);
  });

  it('hiện gợi ý thẻ được phép và "link, ảnh chỉ nhận https"', async () => {
    const user = userEvent.setup();
    renderAt();
    const form = await openNewArticle(user);
    expect(within(form).getByText(/Link và ảnh chỉ nhận https/)).toBeInTheDocument();
    const tags = within(form).getByText(/strong/);
    for (const tag of ['blockquote', 'caption', 'h1–h6', 'table', 'ul']) expect(tags).toHaveTextContent(tag);
  });
});

describe('đăng, ẩn, xóa', () => {
  it('Đăng bài nháp → Đang đăng, rồi Ẩn → Đã ẩn, rồi Đăng lại; báo kết quả trên trang', async () => {
    const user = userEvent.setup();
    renderAt();
    await user.click(within(await draftRow()).getByRole('button', { name: /^Đăng Mèo cào sofa/ }));
    expect(await screen.findByText(/Đã đăng .* lên trang tin tức/)).toBeInTheDocument();
    await waitFor(async () => expect(cellsOf(await draftRow())[2]).toHaveTextContent('Đang đăng'));
    expect(cellsOf(await draftRow())[3]).toHaveTextContent('Đăng lần đầu');

    await user.click(within(await draftRow()).getByRole('button', { name: /^Ẩn Mèo cào sofa/ }));
    expect(await screen.findByText(/Đã ẩn .* khỏi trang tin tức/)).toBeInTheDocument();
    await waitFor(async () => expect(cellsOf(await draftRow())[2]).toHaveTextContent('Đã ẩn'));

    await user.click(within(await draftRow()).getByRole('button', { name: /^Đăng lại Mèo cào sofa/ }));
    await waitFor(async () => expect(cellsOf(await draftRow())[2]).toHaveTextContent('Đang đăng'));
  });

  it('bắt đầu thao tác mới thì thông báo của thao tác trước biến mất ngay, không đợi kết quả', async () => {
    const user = userEvent.setup();
    renderAt();
    await user.click(within(await draftRow()).getByRole('button', { name: /^Đăng Mèo cào sofa/ }));
    expect(await screen.findByRole('status')).toHaveTextContent(/Đã đăng .* lên trang tin tức/);

    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const hide = contentApi.hideArticle;
    vi.spyOn(contentApi, 'hideArticle').mockImplementationOnce(async (id) => {
      await gate;
      return hide(id);
    });
    await user.click(await within(await draftRow()).findByRole('button', { name: /^Ẩn Mèo cào sofa/ }));

    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument()); // thao tác mới còn đang chạy
    release();
    expect(await screen.findByRole('status')).toHaveTextContent(/Đã ẩn .* khỏi trang tin tức/);
  });

  it('bài đã ẩn có nút "Đăng lại", bài đang đăng có nút "Ẩn", bài nháp có nút "Đăng"', async () => {
    renderAt();
    expect(within(await rowOf(T.hidden)).getByRole('button', { name: /^Đăng lại Chó sợ pháo hoa/ })).toBeInTheDocument();
    expect(within(await rowOf(T.royal)).getByRole('button', { name: /^Ẩn Royal Canin/ })).toBeInTheDocument();
    expect(within(await draftRow()).getByRole('button', { name: /^Đăng Mèo cào sofa/ })).toBeInTheDocument();
  });

  it('409 hiện ngay trên trang: bài đã được đăng ở nơi khác rồi mới bấm Đăng', async () => {
    const user = userEvent.setup();
    renderAt();
    const button = within(await draftRow()).getByRole('button', { name: /^Đăng Mèo cào sofa/ });
    await contentApi.publishArticle(6); // người khác đăng trước, bảng chưa kịp làm mới
    await user.click(button);

    expect(await screen.findByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái Article từ PUBLISHED sang PUBLISHED');
    await waitFor(async () => expect(cellsOf(await draftRow())[2]).toHaveTextContent('Đang đăng')); // bảng tự làm mới theo thực tế
  });

  it('409 khi Ẩn bài đã bị ẩn ở nơi khác', async () => {
    const user = userEvent.setup();
    renderAt();
    const button = within(await rowOf(T.royal)).getByRole('button', { name: /^Ẩn Royal Canin/ });
    await contentApi.hideArticle(1);
    await user.click(button);
    expect(await screen.findByRole('alert')).toHaveTextContent('Không thể chuyển trạng thái Article từ HIDDEN sang HIDDEN');
  });

  it('xóa bài nháp: hỏi xác nhận rồi xóa; Hủy thì không xóa', async () => {
    const user = userEvent.setup();
    renderAt();
    await user.click(within(await draftRow()).getByRole('button', { name: /^Xóa Mèo cào sofa/ }));
    let dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('Xóa bài');
    await user.click(within(dialog).getByRole('button', { name: 'Hủy' }));
    expect(bodyRows()).toHaveLength(10);

    await user.click(within(await draftRow()).getByRole('button', { name: /^Xóa Mèo cào sofa/ }));
    dialog = screen.getByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Xóa' }));
    expect(await screen.findByText(/Đã xóa bài/)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText(T.draft)).not.toBeInTheDocument());
    expect(bodyRows()).toHaveLength(9);
  });

  it('bài đã từng xuất bản (đang đăng hoặc đã ẩn) không xóa được: nút tắt kèm lý do', async () => {
    renderAt();
    for (const title of [T.royal, T.hidden]) {
      const row = await rowOf(title);
      const remove = within(row).getByRole('button', { name: /^Xóa .*đã từng xuất bản, chỉ ẩn được/ });
      expect(remove).toBeDisabled();
      expect(remove.closest('span')).toHaveAttribute('title', 'Đã từng xuất bản, chỉ ẩn được');
    }
    // bài nháp chưa từng xuất bản thì nút bật
    expect(within(await draftRow()).getByRole('button', { name: /^Xóa Mèo cào sofa/ })).toBeEnabled();
  });

  it('BR-BV-02 từ BE khi bài vừa được đăng ở nơi khác rồi mới xác nhận xóa', async () => {
    const user = userEvent.setup();
    renderAt();
    await user.click(within(await draftRow()).getByRole('button', { name: /^Xóa Mèo cào sofa/ }));
    await contentApi.publishArticle(6);
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Xóa' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Bài viết đã từng xuất bản, chỉ ẩn được');
    expect(screen.queryByText(/Đã xóa bài/)).not.toBeInTheDocument();
  });
});

describe('tab Chuyên mục', () => {
  const categoryRow = async (name: string) => (await screen.findByText(name)).closest('tr')!;

  it('liệt kê theo tên với đường dẫn, số bài và trạng thái hiện / ẩn', async () => {
    renderAt('/admin/articles/categories');
    await screen.findByText('Kiến thức nuôi thú cưng');
    const rows = screen.getAllByRole('row').slice(1);
    expect(rows.map((r) => within(r).getAllByRole('cell')[0].textContent)).toEqual([
      expect.stringContaining('Chăm sóc hằng ngày'),
      expect.stringContaining('Kiến thức nuôi thú cưng'),
      expect.stringContaining('Lưu trú & dịch vụ'),
      expect.stringContaining('Sức khỏe thú y'),
    ]);
    const hidden = cellsOf(await categoryRow('Lưu trú & dịch vụ'));
    expect(hidden[0]).toHaveTextContent('luu-tru-dich-vu');
    expect(hidden[1]).toHaveTextContent('1');
    expect(hidden[2]).toHaveTextContent('Đã ẩn');
    expect(cellsOf(await categoryRow('Kiến thức nuôi thú cưng'))[1]).toHaveTextContent('4');
    expect(cellsOf(await categoryRow('Kiến thức nuôi thú cưng'))[2]).toHaveTextContent('Đang hiện');
  });

  it('đang tải thì có chữ chờ; lỗi thì có thông báo và nút Thử lại', async () => {
    const user = userEvent.setup();
    vi.spyOn(contentApi, 'listArticleCategories').mockRejectedValueOnce(new ApiError('ACCESS_DENIED', 'Không có quyền thực hiện thao tác này', 403));
    renderAt('/admin/articles/categories');
    expect(screen.getByText('Đang tải…')).toBeInTheDocument();
    expect(await screen.findByRole('alert')).toHaveTextContent('Không có quyền thực hiện thao tác này');
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByText('Kiến thức nuôi thú cưng')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('chưa có chuyên mục nào: câu rỗng có nghĩa, không phải lỗi, vẫn còn nút Thêm chuyên mục', async () => {
    withState((state) => {
      state.articles = [];
      state.categories = [];
    });
    renderAt('/admin/articles/categories');
    expect(await screen.findByText('Chưa có chuyên mục nào. Tạo chuyên mục trước khi soạn bài.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText('Đang tải…')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Thêm chuyên mục' })).toBeInTheDocument();
  });

  it('tạo chuyên mục: slug tự gợi ý từ tên; xuất hiện trong danh sách với 0 bài', async () => {
    const user = userEvent.setup();
    renderAt('/admin/articles/categories');
    await user.click(await screen.findByRole('button', { name: 'Thêm chuyên mục' }));
    const form = screen.getByRole('dialog');
    await user.type(within(form).getByLabelText('Tên chuyên mục'), 'Dinh dưỡng đặc biệt');
    expect(within(form).getByLabelText('Đường dẫn (slug)')).toHaveValue('dinh-duong-dac-biet');
    await user.click(within(form).getByRole('button', { name: 'Tạo chuyên mục' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByRole('status')).toHaveTextContent('Đã thêm chuyên mục Dinh dưỡng đặc biệt');
    const row = cellsOf(await categoryRow('Dinh dưỡng đặc biệt'));
    expect(row[0]).toHaveTextContent('dinh-duong-dac-biet');
    expect(row[1]).toHaveTextContent('0');
  });

  it('tên trùng → BE từ chối, hiện trong form và không đóng form', async () => {
    const user = userEvent.setup();
    renderAt('/admin/articles/categories');
    await user.click(await screen.findByRole('button', { name: 'Thêm chuyên mục' }));
    const form = screen.getByRole('dialog');
    await user.type(within(form).getByLabelText('Tên chuyên mục'), 'Sức khỏe thú y');
    const slug = within(form).getByLabelText('Đường dẫn (slug)');
    await user.clear(slug);
    await user.type(slug, 'khac');
    await user.click(within(form).getByRole('button', { name: 'Tạo chuyên mục' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent('Tên chuyên mục đã tồn tại');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('thiếu tên hoặc slug sai mẫu thì báo ngay, không gửi request', async () => {
    const user = userEvent.setup();
    const create = vi.spyOn(contentApi, 'createArticleCategory');
    renderAt('/admin/articles/categories');
    await user.click(await screen.findByRole('button', { name: 'Thêm chuyên mục' }));
    const form = screen.getByRole('dialog');
    await user.click(within(form).getByRole('button', { name: 'Tạo chuyên mục' }));
    expect(within(form).getByRole('alert')).toHaveTextContent('Nhập tên chuyên mục.');

    await user.type(within(form).getByLabelText('Tên chuyên mục'), 'Mới');
    await user.clear(within(form).getByLabelText('Đường dẫn (slug)'));
    await user.type(within(form).getByLabelText('Đường dẫn (slug)'), 'Sai Slug');
    await user.click(within(form).getByRole('button', { name: 'Tạo chuyên mục' }));
    expect(within(form).getByRole('alert')).toHaveTextContent('Đường dẫn chỉ gồm chữ thường không dấu, số và dấu gạch ngang.');
    expect(create).not.toHaveBeenCalled();
  });

  it('đổi tên: đường dẫn hiện nhưng không sửa được, chỉ gửi tên', async () => {
    const user = userEvent.setup();
    const update = vi.spyOn(contentApi, 'updateArticleCategory');
    renderAt('/admin/articles/categories');
    await user.click(within(await categoryRow('Sức khỏe thú y')).getByRole('button', { name: 'Đổi tên Sức khỏe thú y' }));
    const form = screen.getByRole('dialog');
    expect(within(form).queryByLabelText('Đường dẫn (slug)')).not.toBeInTheDocument();
    expect(form).toHaveTextContent('suc-khoe-thu-y');
    const name = within(form).getByLabelText('Tên chuyên mục');
    await user.clear(name);
    await user.type(name, 'Sức khỏe & thú y');
    await user.click(within(form).getByRole('button', { name: 'Lưu thay đổi' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(update).toHaveBeenCalledWith(2, { name: 'Sức khỏe & thú y' });
    expect(screen.getByRole('status')).toHaveTextContent('Đã đổi tên thành Sức khỏe & thú y');
    expect(cellsOf(await categoryRow('Sức khỏe & thú y'))[0]).toHaveTextContent('suc-khoe-thu-y');
  });

  it('ẩn rồi hiện lại chuyên mục', async () => {
    const user = userEvent.setup();
    renderAt('/admin/articles/categories');
    await user.click(within(await categoryRow('Chăm sóc hằng ngày')).getByRole('button', { name: 'Ẩn Chăm sóc hằng ngày' }));
    expect(await screen.findByRole('status')).toHaveTextContent(/Đã ẩn chuyên mục Chăm sóc hằng ngày/);
    await waitFor(async () => expect(cellsOf(await categoryRow('Chăm sóc hằng ngày'))[2]).toHaveTextContent('Đã ẩn'));

    await user.click(within(await categoryRow('Chăm sóc hằng ngày')).getByRole('button', { name: 'Hiện Chăm sóc hằng ngày' }));
    await waitFor(async () => expect(cellsOf(await categoryRow('Chăm sóc hằng ngày'))[2]).toHaveTextContent('Đang hiện'));
  });

  it('bắt đầu thao tác mới thì thông báo của thao tác trước biến mất ngay, không đợi kết quả', async () => {
    const user = userEvent.setup();
    renderAt('/admin/articles/categories');
    await user.click(within(await categoryRow('Chăm sóc hằng ngày')).getByRole('button', { name: 'Ẩn Chăm sóc hằng ngày' }));
    expect(await screen.findByRole('status')).toHaveTextContent(/Đã ẩn chuyên mục Chăm sóc hằng ngày/);

    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const update = contentApi.updateArticleCategory;
    vi.spyOn(contentApi, 'updateArticleCategory').mockImplementationOnce(async (id, body) => {
      await gate;
      return update(id, body);
    });
    await user.click(await within(await categoryRow('Chăm sóc hằng ngày')).findByRole('button', { name: 'Hiện Chăm sóc hằng ngày' }));

    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument()); // thao tác mới còn đang chạy
    release();
    expect(await screen.findByRole('status')).toHaveTextContent('Đã hiện lại chuyên mục Chăm sóc hằng ngày');
  });

  it('chuyên mục còn bài: nút xóa tắt kèm lý do; chuyên mục rỗng: xóa được sau khi xác nhận', async () => {
    const user = userEvent.setup();
    await contentApi.createArticleCategory({ name: 'Tạm', slug: 'tam' });
    renderAt('/admin/articles/categories');
    const full = within(await categoryRow('Kiến thức nuôi thú cưng')).getByRole('button', { name: /^Xóa Kiến thức nuôi thú cưng.*còn 4 bài viết, chỉ ẩn được/ });
    expect(full).toBeDisabled();
    expect(full.closest('span')).toHaveAttribute('title', 'Còn 4 bài viết, chỉ ẩn được');

    await user.click(within(await categoryRow('Tạm')).getByRole('button', { name: 'Xóa Tạm' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Xóa' }));
    expect(await screen.findByText('Đã xóa chuyên mục Tạm')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('Tạm')).not.toBeInTheDocument());
  });

  it('BR-BV-04 từ BE khi chuyên mục vừa có bài ở nơi khác rồi mới xác nhận xóa', async () => {
    const user = userEvent.setup();
    const category = await contentApi.createArticleCategory({ name: 'Tạm', slug: 'tam' });
    renderAt('/admin/articles/categories');
    await user.click(within(await categoryRow('Tạm')).getByRole('button', { name: 'Xóa Tạm' }));
    await contentApi.createArticle({ categoryId: category.categoryId, title: 'Bài', slug: 'bai', content: '<p>x</p>', authorDisplayName: 'A' });
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Xóa' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Chuyên mục còn 1 bài viết, chỉ ẩn được');
  });
});

describe('hai tab dùng chung dữ liệu', () => {
  it('soạn bài ở tab Bài viết thì số bài ở tab Chuyên mục tăng ngay, không cần tải lại', async () => {
    const user = userEvent.setup();
    renderAt();
    await screen.findByText(T.royal);
    await user.click(screen.getByRole('tab', { name: 'Chuyên mục' }));
    const before = cellsOf((await screen.findByText('Sức khỏe thú y')).closest('tr')!)[1];
    expect(before).toHaveTextContent('3');

    await user.click(screen.getByRole('tab', { name: 'Bài viết' }));
    const form = await openNewArticle(user);
    await fillNewArticle(user, form, { title: 'Bài thêm', content: '<p>x</p>' });
    await submit(user, form, 'Lưu nháp');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    await user.click(screen.getByRole('tab', { name: 'Chuyên mục' }));
    await waitFor(async () => expect(cellsOf((await screen.findByText('Sức khỏe thú y')).closest('tr')!)[1]).toHaveTextContent('4'));
  });
});
