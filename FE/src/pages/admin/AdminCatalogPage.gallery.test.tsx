import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { catalogApi } from '../../shared/api/catalog.api';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import type { Product } from '../../shared/types/catalog';
import { AdminCatalogPage } from './AdminCatalogPage';

// Ảnh phụ của sản phẩm (UC29, catalog-v1 galleryUrls) trong form tạo / sửa. Mock: sản phẩm 1 "Hạt Royal Canin" có 2 ảnh phụ
// (/imgs/prod2.jpg, /imgs/prod3.jpg), sản phẩm 2 "Pate Whiskas" có 1 (/imgs/prod7.jpg), sản phẩm 3 "Amoxicillin" không có.
// Luật PATCH của BE: galleryUrls vắng / null = giữ nguyên, [] = xóa hết, mảng khác = THAY cả danh sách.
beforeAll(() => installMockApi(apiClient));

beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'chuoi@petcare.vn', password: MOCK_PASSWORD });
});

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

// Thân gửi đi thật (JSON bỏ trường undefined): để thấy "vắng" khác "[]".
const wire = (body: unknown) => JSON.parse(JSON.stringify(body)) as Record<string, unknown>;

const HAT = 'Hạt Royal Canin Mini Adult 2kg';

async function openEdit(user: User, name = HAT) {
  await user.click(await screen.findByRole('button', { name: `Sửa ${name}` }));
  return screen.getByRole('dialog');
}
const gallery = (form: HTMLElement) => within(form).getByRole('group', { name: 'Ảnh phụ' });
const urlsIn = (form: HTMLElement) => within(gallery(form)).queryAllByRole('textbox').map((i) => (i as HTMLInputElement).value);
const save = (user: User, form: HTMLElement, name = 'Lưu thay đổi') => user.click(within(form).getByRole('button', { name }));
const stored = async (id: number) => (await apiClient.get(`/api/products/${id}`)).data.data.galleryUrls as string[];

describe('ảnh phụ trong form sửa sản phẩm', () => {
  it('hiện đúng các ảnh phụ đang có, theo thứ tự, kèm đếm "2/8" và ảnh xem trước', async () => {
    const user = renderTab();
    const form = await openEdit(user);
    expect(urlsIn(form)).toEqual(['/imgs/prod2.jpg', '/imgs/prod3.jpg']);
    expect(within(form).getByText('2/8')).toBeInTheDocument();
    expect(gallery(form).querySelectorAll('img')).toHaveLength(2);
    expect(within(form).getByRole('button', { name: 'Thêm ảnh phụ' })).toBeEnabled();
  });

  it('không đụng tới mục ảnh phụ thì KHÔNG gửi galleryUrls (PATCH giữ nguyên)', async () => {
    const update = vi.spyOn(catalogApi, 'updateProduct');
    const user = renderTab();
    const form = await openEdit(user);
    await user.clear(within(form).getByLabelText('Tên sản phẩm'));
    await user.type(within(form).getByLabelText('Tên sản phẩm'), 'Hạt Royal Canin mới');
    await save(user, form);
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1));
    expect(wire(update.mock.calls[0][1])).not.toHaveProperty('galleryUrls');
    expect(await stored(1)).toEqual(['/imgs/prod2.jpg', '/imgs/prod3.jpg']);
  });

  it('thêm một dòng trống rồi lưu mà không nhập gì: dòng trống bị bỏ, không gửi galleryUrls', async () => {
    const update = vi.spyOn(catalogApi, 'updateProduct');
    const user = renderTab();
    const form = await openEdit(user);
    await user.click(within(form).getByRole('button', { name: 'Thêm ảnh phụ' }));
    expect(within(form).getByText('3/8')).toBeInTheDocument();
    await save(user, form);
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1));
    expect(wire(update.mock.calls[0][1])).not.toHaveProperty('galleryUrls');
  });

  it('thêm ảnh: gửi mảng ĐẦY ĐỦ (cũ + mới, cắt khoảng trắng), BE lưu đúng thứ tự', async () => {
    const update = vi.spyOn(catalogApi, 'updateProduct');
    const user = renderTab();
    const form = await openEdit(user);
    await user.click(within(form).getByRole('button', { name: 'Thêm ảnh phụ' }));
    await user.type(within(form).getByLabelText('Đường dẫn ảnh phụ 3'), '  /imgs/prod4.jpg ');
    await save(user, form);
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1));
    expect(wire(update.mock.calls[0][1]).galleryUrls).toEqual(['/imgs/prod2.jpg', '/imgs/prod3.jpg', '/imgs/prod4.jpg']);
    await waitFor(async () => expect(await stored(1)).toEqual(['/imgs/prod2.jpg', '/imgs/prod3.jpg', '/imgs/prod4.jpg']));
  });

  it('đưa ảnh xuống / lên đổi đúng thứ tự trên màn hình VÀ trong thân gửi đi; nút đầu không đưa lên được, nút cuối không đưa xuống được', async () => {
    const update = vi.spyOn(catalogApi, 'updateProduct');
    const user = renderTab();
    const form = await openEdit(user);
    expect(within(form).getByRole('button', { name: 'Đưa ảnh phụ 1 lên' })).toBeDisabled();
    expect(within(form).getByRole('button', { name: 'Đưa ảnh phụ 2 xuống' })).toBeDisabled();
    await user.click(within(form).getByRole('button', { name: 'Đưa ảnh phụ 1 xuống' }));
    expect(urlsIn(form)).toEqual(['/imgs/prod3.jpg', '/imgs/prod2.jpg']);
    await user.click(within(form).getByRole('button', { name: 'Đưa ảnh phụ 2 lên' }));
    expect(urlsIn(form)).toEqual(['/imgs/prod2.jpg', '/imgs/prod3.jpg']);
    await user.click(within(form).getByRole('button', { name: 'Đưa ảnh phụ 2 lên' }));
    expect(urlsIn(form)).toEqual(['/imgs/prod3.jpg', '/imgs/prod2.jpg']);
    await save(user, form);
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1));
    expect(wire(update.mock.calls[0][1]).galleryUrls).toEqual(['/imgs/prod3.jpg', '/imgs/prod2.jpg']);
    await waitFor(async () => expect(await stored(1)).toEqual(['/imgs/prod3.jpg', '/imgs/prod2.jpg']));
  });

  it('xóa từng ảnh; xóa hết thì gửi galleryUrls: [] (vắng sẽ là "giữ nguyên") và BE thật sự xóa hết', async () => {
    const update = vi.spyOn(catalogApi, 'updateProduct');
    const user = renderTab();
    const form = await openEdit(user);
    await user.click(within(form).getByRole('button', { name: 'Xóa ảnh phụ 1' }));
    expect(urlsIn(form)).toEqual(['/imgs/prod3.jpg']);
    expect(within(form).getByText('1/8')).toBeInTheDocument();
    await user.click(within(form).getByRole('button', { name: 'Xóa ảnh phụ 1' }));
    expect(urlsIn(form)).toEqual([]);
    expect(within(form).getByText('0/8')).toBeInTheDocument();
    await save(user, form);
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1));
    const body = wire(update.mock.calls[0][1]);
    expect(body).toHaveProperty('galleryUrls');
    expect(body.galleryUrls).toEqual([]);
    await waitFor(async () => expect(await stored(1)).toEqual([]));
  });

  it('URL nhập trùng nhau chỉ gửi và lưu một lần (giữ lần đầu)', async () => {
    const update = vi.spyOn(catalogApi, 'updateProduct');
    const user = renderTab();
    const form = await openEdit(user, 'Pate Whiskas 85g');
    await user.click(within(form).getByRole('button', { name: 'Thêm ảnh phụ' }));
    await user.type(within(form).getByLabelText('Đường dẫn ảnh phụ 2'), '/imgs/prod7.jpg');
    await user.click(within(form).getByRole('button', { name: 'Thêm ảnh phụ' }));
    await user.type(within(form).getByLabelText('Đường dẫn ảnh phụ 3'), '/imgs/prod8.jpg');
    await save(user, form);
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1));
    expect(wire(update.mock.calls[0][1]).galleryUrls).toEqual(['/imgs/prod7.jpg', '/imgs/prod8.jpg']);
  });

  it('đủ 8 dòng: hiện "8/8" và nút thêm bị tắt; xóa bớt một dòng thì bật lại', async () => {
    const user = renderTab();
    const form = await openEdit(user, 'Amoxicillin 250mg');
    const add = within(form).getByRole('button', { name: 'Thêm ảnh phụ' });
    for (let i = 0; i < 8; i++) await user.click(add);
    expect(within(form).getByText('8/8')).toBeInTheDocument();
    expect(add).toBeDisabled();
    expect(within(gallery(form)).getAllByRole('textbox')).toHaveLength(8);
    await user.click(within(form).getByRole('button', { name: 'Xóa ảnh phụ 8' }));
    expect(within(form).getByText('7/8')).toBeInTheDocument();
    expect(add).toBeEnabled();
  });

  it('các nút thêm, xóa, đưa lên / xuống KHÔNG gửi form (chỉ nút Lưu mới gọi API)', async () => {
    const update = vi.spyOn(catalogApi, 'updateProduct');
    const user = renderTab();
    const form = await openEdit(user);
    await user.click(within(form).getByRole('button', { name: 'Thêm ảnh phụ' }));
    await user.click(within(form).getByRole('button', { name: 'Đưa ảnh phụ 1 xuống' }));
    await user.click(within(form).getByRole('button', { name: 'Đưa ảnh phụ 2 lên' }));
    await user.click(within(form).getByRole('button', { name: 'Xóa ảnh phụ 3' }));
    expect(update).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('ảnh xem trước tải lỗi thì thay bằng khung trống, không để ảnh vỡ', async () => {
    const user = renderTab();
    const form = await openEdit(user);
    const [first] = Array.from(gallery(form).querySelectorAll('img'));
    fireEvent.error(first);
    expect(gallery(form).querySelectorAll('img')).toHaveLength(1);
  });

  it('lỗi 400 của BE hiện đủ câu (lỗi cả danh sách lẫn lỗi từng phần tử), không kèm đường dẫn trường "galleryUrls[0]:"', async () => {
    vi.spyOn(catalogApi, 'updateProduct').mockRejectedValueOnce(
      new ApiError('VALIDATION_FAILED', 'galleryUrls[0]: Đường dẫn ảnh phụ không được để trống; galleryUrls: Tối đa 8 ảnh phụ', 400),
    );
    const user = renderTab();
    const form = await openEdit(user);
    await user.click(within(form).getByRole('button', { name: 'Xóa ảnh phụ 2' }));
    await save(user, form);
    const alert = await within(form).findByRole('alert');
    expect(alert).toHaveTextContent('Đường dẫn ảnh phụ không được để trống');
    expect(alert).toHaveTextContent('Tối đa 8 ảnh phụ');
    expect(alert).not.toHaveTextContent('galleryUrls');
  });

  it('BE cũ chưa có trường (galleryUrls vắng): mở form sửa không lỗi, mục ảnh phụ trống "0/8"', async () => {
    const legacy = { productId: 9, categoryId: 1, sku: 'OLD-1', name: 'Hàng cũ', productType: 'GOODS', isPrescription: false, tracksExpiry: false, unit: 'cái', price: 1000, isActive: true };
    vi.spyOn(catalogApi, 'listProducts').mockResolvedValue({ content: [legacy as unknown as Product], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    const user = renderTab();
    const form = await openEdit(user, 'Hàng cũ');
    expect(urlsIn(form)).toEqual([]);
    expect(within(form).getByText('0/8')).toBeInTheDocument();
  });
});

describe('ảnh phụ trong form tạo sản phẩm', () => {
  async function fillRequired(user: User, sku: string) {
    await user.click(await screen.findByRole('button', { name: 'Thêm sản phẩm' }));
    const form = screen.getByRole('dialog');
    await user.selectOptions(within(form).getByLabelText('Danh mục'), 'Phụ kiện');
    await user.type(within(form).getByLabelText('SKU'), sku);
    await user.type(within(form).getByLabelText('Tên sản phẩm'), `Dây ${sku}`);
    await user.type(within(form).getByLabelText('Đơn vị tính'), 'cái');
    await user.type(within(form).getByLabelText('Giá (đ)'), '75000');
    return form;
  }

  it('không thêm ảnh: vẫn gửi galleryUrls: [] và sản phẩm mới có mảng rỗng', async () => {
    const create = vi.spyOn(catalogApi, 'createProduct');
    const user = renderTab();
    const form = await fillRequired(user, 'GAL-NONE');
    expect(urlsIn(form)).toEqual([]);
    expect(within(form).getByText('0/8')).toBeInTheDocument();
    await save(user, form, 'Tạo sản phẩm');
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(wire(create.mock.calls[0][0]).galleryUrls).toEqual([]);
  });

  it('thêm hai ảnh phụ: gửi mảng theo thứ tự nhập, BE lưu đúng', async () => {
    const create = vi.spyOn(catalogApi, 'createProduct');
    const user = renderTab();
    const form = await fillRequired(user, 'GAL-TWO');
    await user.click(within(form).getByRole('button', { name: 'Thêm ảnh phụ' }));
    await user.click(within(form).getByRole('button', { name: 'Thêm ảnh phụ' }));
    await user.type(within(form).getByLabelText('Đường dẫn ảnh phụ 1'), '/imgs/prod5.jpg');
    await user.type(within(form).getByLabelText('Đường dẫn ảnh phụ 2'), 'https://cdn.example.com/b.jpg');
    await save(user, form, 'Tạo sản phẩm');
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(wire(create.mock.calls[0][0]).galleryUrls).toEqual(['/imgs/prod5.jpg', 'https://cdn.example.com/b.jpg']);
    const created = await create.mock.results[0].value;
    expect(created.galleryUrls).toEqual(['/imgs/prod5.jpg', 'https://cdn.example.com/b.jpg']);
  });
});
