import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { catalogApi } from '../../shared/api/catalog.api';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { isUploadConfigured, uploadImage } from '../../shared/utils/cloudinary';
import { AdminCatalogPage } from './AdminCatalogPage';

// Tải ảnh bìa / ảnh phụ của sản phẩm lên Cloudinary từ form UC29 (ADR-0156). Chỉ thay lớp mạng của Cloudinary; form, mock BE
// và luật PATCH (imageUrl vắng / null = giữ nguyên, "" = xóa) chạy thật. Mock: sản phẩm 1 "Hạt Royal Canin" có ảnh bìa
// /imgs/prod1.jpg và 2 ảnh phụ (/imgs/prod2.jpg, /imgs/prod3.jpg).
vi.mock('../../shared/utils/cloudinary', () => ({ isUploadConfigured: vi.fn(), uploadImage: vi.fn() }));

const COVER = 'https://res.cloudinary.com/demo/image/upload/v1/petcare/bia.jpg';
const A = 'https://res.cloudinary.com/demo/image/upload/v1/petcare/a.jpg';
const B = 'https://res.cloudinary.com/demo/image/upload/v1/petcare/b.jpg';
const HAT = 'Hạt Royal Canin Mini Adult 2kg';

beforeAll(() => installMockApi(apiClient));

beforeEach(async () => {
  vi.mocked(isUploadConfigured).mockReturnValue(true);
  vi.mocked(uploadImage).mockReset();
  useSession.getState().clear();
  await useSession.getState().login({ email: 'chuoi@petcare.vn', password: MOCK_PASSWORD });
});

afterEach(() => vi.restoreAllMocks());

function renderTab() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/catalog/products']}>
        <Routes>
          <Route path="/admin/catalog/:tab?" element={<AdminCatalogPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

type User = ReturnType<typeof userEvent.setup>;

// Thân gửi đi thật (JSON bỏ trường undefined): để thấy "vắng" khác "".
const wire = (body: unknown) => JSON.parse(JSON.stringify(body)) as Record<string, unknown>;
const img = (name: string) => new File(['x'], name, { type: 'image/png' });

async function openEdit(user: User, name = HAT) {
  await user.click(await screen.findByRole('button', { name: `Sửa ${name}` }));
  return screen.getByRole('dialog');
}
async function openCreate(user: User, sku: string) {
  await user.click(await screen.findByRole('button', { name: 'Thêm sản phẩm' }));
  const form = screen.getByRole('dialog');
  await user.selectOptions(within(form).getByLabelText('Danh mục'), 'Phụ kiện');
  await user.type(within(form).getByLabelText('SKU'), sku);
  await user.type(within(form).getByLabelText('Tên sản phẩm'), `Dây ${sku}`);
  await user.type(within(form).getByLabelText('Đơn vị tính'), 'cái');
  await user.type(within(form).getByLabelText('Giá (đ)'), '75000');
  return form;
}
const save = (user: User, form: HTMLElement, name = 'Lưu thay đổi') => user.click(within(form).getByRole('button', { name }));
const cover = (form: HTMLElement) => within(form).getByLabelText('Ảnh bìa') as HTMLInputElement;
const coverFile = (form: HTMLElement) => within(form).getByLabelText('Tải ảnh bìa lên (chọn tệp)');
const galleryFile = (form: HTMLElement) => within(form).getByLabelText('Tải ảnh phụ lên (chọn tệp)');
const galleryUrls = (form: HTMLElement) =>
  within(within(form).getByRole('group', { name: 'Ảnh phụ' })).queryAllByRole('textbox').map((i) => (i as HTMLInputElement).value);
const stored = async (id: number) => (await apiClient.get(`/api/products/${id}`)).data.data as { imageUrl: string; galleryUrls: string[] };

describe('ảnh bìa: tải lên Cloudinary', () => {
  it('tạo sản phẩm: chọn tệp thì ô "Ảnh bìa" hiện URL Cloudinary, lưu gửi đúng imageUrl và BE lưu đúng', async () => {
    vi.mocked(uploadImage).mockResolvedValueOnce(COVER);
    const create = vi.spyOn(catalogApi, 'createProduct');
    const user = renderTab();
    const form = await openCreate(user, 'UP-COVER');

    const file = img('bia.png');
    await user.upload(coverFile(form), file);

    await waitFor(() => expect(cover(form)).toHaveValue(COVER));
    expect(uploadImage).toHaveBeenCalledWith(file);
    await save(user, form, 'Tạo sản phẩm');
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(wire(create.mock.calls[0][0]).imageUrl).toBe(COVER);
    expect((await create.mock.results[0].value).imageUrl).toBe(COVER);
  });

  it('tạo sản phẩm không có ảnh bìa: không gửi imageUrl', async () => {
    const create = vi.spyOn(catalogApi, 'createProduct');
    const user = renderTab();
    const form = await openCreate(user, 'UP-NONE');
    await save(user, form, 'Tạo sản phẩm');
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(wire(create.mock.calls[0][0])).not.toHaveProperty('imageUrl');
  });

  it('có thể dán URL tay như trước (không bắt buộc tải lên)', async () => {
    const create = vi.spyOn(catalogApi, 'createProduct');
    const user = renderTab();
    const form = await openCreate(user, 'UP-PASTE');
    await user.type(cover(form), '  /imgs/prod9.jpg ');
    await save(user, form, 'Tạo sản phẩm');
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(wire(create.mock.calls[0][0]).imageUrl).toBe('/imgs/prod9.jpg');
  });

  it('sửa: ô "Ảnh bìa" hiện ảnh đang có; không đụng tới thì KHÔNG gửi imageUrl (PATCH giữ nguyên)', async () => {
    const update = vi.spyOn(catalogApi, 'updateProduct');
    const user = renderTab();
    const form = await openEdit(user);
    expect(cover(form)).toHaveValue('/imgs/prod1.jpg');
    await user.type(within(form).getByLabelText('Tên sản phẩm'), ' mới');
    await save(user, form);
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1));
    expect(wire(update.mock.calls[0][1])).not.toHaveProperty('imageUrl');
    expect((await stored(1)).imageUrl).toBe('/imgs/prod1.jpg');
  });

  it('sửa: tải ảnh mới thay ảnh bìa cũ, PATCH gửi imageUrl mới và BE lưu', async () => {
    vi.mocked(uploadImage).mockResolvedValueOnce(COVER);
    const update = vi.spyOn(catalogApi, 'updateProduct');
    const user = renderTab();
    const form = await openEdit(user);
    await user.upload(coverFile(form), img('bia.png'));
    await waitFor(() => expect(cover(form)).toHaveValue(COVER));
    await save(user, form);
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1));
    expect(wire(update.mock.calls[0][1]).imageUrl).toBe(COVER);
    await waitFor(async () => expect((await stored(1)).imageUrl).toBe(COVER));
  });

  it('sửa: xóa trắng ô thì gửi imageUrl: "" (vắng sẽ là "giữ nguyên")', async () => {
    const update = vi.spyOn(catalogApi, 'updateProduct');
    const user = renderTab();
    const form = await openEdit(user);
    await user.clear(cover(form));
    await save(user, form);
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1));
    expect(wire(update.mock.calls[0][1])).toHaveProperty('imageUrl', '');
    await waitFor(async () => expect((await stored(1)).imageUrl).toBe(''));
  });

  it('tải lỗi: ô giữ nguyên, hiện câu lỗi của lớp upload', async () => {
    vi.mocked(uploadImage).mockRejectedValueOnce(new Error('Ảnh quá lớn, tối đa 5 MB'));
    const user = renderTab();
    const form = await openEdit(user);
    await user.upload(coverFile(form), img('to.png'));
    expect(await within(form).findByRole('alert')).toHaveTextContent('Ảnh quá lớn, tối đa 5 MB');
    expect(cover(form)).toHaveValue('/imgs/prod1.jpg');
  });

  it('bấm nút "Tải ảnh bìa lên" không gửi form', async () => {
    const update = vi.spyOn(catalogApi, 'updateProduct');
    const user = renderTab();
    const form = await openEdit(user);
    await user.click(within(form).getByRole('button', { name: 'Tải ảnh bìa lên' }));
    expect(update).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('đang tải dở mà bấm Lưu: chặn và nhắc đợi (ảnh không bị mất âm thầm), tải xong thì lưu được', async () => {
    let finish!: (url: string) => void;
    vi.mocked(uploadImage).mockReturnValueOnce(new Promise<string>((resolve) => (finish = resolve)));
    const create = vi.spyOn(catalogApi, 'createProduct');
    const user = renderTab();
    const form = await openCreate(user, 'UP-WAIT');
    await user.upload(coverFile(form), img('bia.png'));
    await save(user, form, 'Tạo sản phẩm');
    expect(await within(form).findByRole('alert')).toHaveTextContent('Đợi ảnh tải lên xong');
    expect(create).not.toHaveBeenCalled();

    finish(COVER);
    await waitFor(() => expect(cover(form)).toHaveValue(COVER));
    await save(user, form, 'Tạo sản phẩm');
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(wire(create.mock.calls[0][0]).imageUrl).toBe(COVER);
  });
});

describe('ảnh phụ: tải lên Cloudinary', () => {
  it('chọn nhiều tệp: thêm đủ theo thứ tự chọn, sau ảnh cũ; PATCH gửi mảng ĐẦY ĐỦ', async () => {
    vi.mocked(uploadImage).mockResolvedValueOnce(A).mockResolvedValueOnce(B);
    const update = vi.spyOn(catalogApi, 'updateProduct');
    const user = renderTab();
    const form = await openEdit(user);
    await user.upload(galleryFile(form), [img('a.png'), img('b.png')]);
    await waitFor(() => expect(galleryUrls(form)).toEqual(['/imgs/prod2.jpg', '/imgs/prod3.jpg', A, B]));
    expect(within(form).getByText('4/8')).toBeInTheDocument();
    await save(user, form);
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1));
    expect(wire(update.mock.calls[0][1]).galleryUrls).toEqual(['/imgs/prod2.jpg', '/imgs/prod3.jpg', A, B]);
    await waitFor(async () => expect((await stored(1)).galleryUrls).toEqual(['/imgs/prod2.jpg', '/imgs/prod3.jpg', A, B]));
  });

  it('chỉ còn chỗ cho 6 ảnh mà chọn 7 tệp: tải đúng 6, báo ảnh thừa bị bỏ, đủ "8/8"', async () => {
    vi.mocked(uploadImage).mockImplementation(async (f) => `https://res.cloudinary.com/demo/${f.name}`);
    const user = renderTab();
    const form = await openEdit(user);
    await user.upload(galleryFile(form), Array.from({ length: 7 }, (_, i) => img(`p${i}.png`)));
    await waitFor(() => expect(within(form).getByText('8/8')).toBeInTheDocument());
    expect(uploadImage).toHaveBeenCalledTimes(6);
    expect(await within(form).findByRole('alert')).toHaveTextContent('Chỉ còn chỗ cho 6 ảnh');
    expect(within(form).getByRole('button', { name: 'Tải ảnh phụ lên' })).toBeDisabled();
  });

  it('tệp thứ hai lỗi: giữ ảnh đã tải được của tệp đầu và báo lỗi', async () => {
    vi.mocked(uploadImage).mockResolvedValueOnce(A).mockRejectedValueOnce(new Error('Chỉ nhận tệp ảnh (JPG, PNG, WebP…)'));
    const user = renderTab();
    const form = await openEdit(user);
    await user.upload(galleryFile(form), [img('a.png'), img('b.png')]);
    expect(await within(form).findByRole('alert')).toHaveTextContent('Chỉ nhận tệp ảnh');
    expect(galleryUrls(form)).toEqual(['/imgs/prod2.jpg', '/imgs/prod3.jpg', A]);
  });
});

describe('chưa cấu hình Cloudinary', () => {
  // Nút tắt có `pointer-events-none` nên tooltip `title` không bao giờ hiện: lý do phải là chữ nhìn thấy được.
  it('hai nút tải lên bị tắt kèm chữ giải thích hiện sẵn (mỗi nút một dòng); dán URL tay vẫn dùng được', async () => {
    vi.mocked(isUploadConfigured).mockReturnValue(false);
    const user = renderTab();
    const form = await openEdit(user);
    for (const name of ['Tải ảnh bìa lên', 'Tải ảnh phụ lên']) expect(within(form).getByRole('button', { name })).toBeDisabled();
    expect(within(form).getAllByText('Chưa cấu hình Cloudinary (xem FE/.env.example).')).toHaveLength(2);
    expect(cover(form)).toBeEnabled();
    expect(within(form).getByRole('button', { name: 'Thêm ảnh phụ' })).toBeEnabled();
  });
});
