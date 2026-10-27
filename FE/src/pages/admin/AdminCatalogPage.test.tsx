import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { catalogApi } from '../../shared/api/catalog.api';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminCatalogPage } from './AdminCatalogPage';

// Dữ liệu mẫu ở shared/api/mock/catalog.mock.ts.
beforeAll(() => installMockApi(apiClient));

beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'chuoi@petcare.vn', password: MOCK_PASSWORD });
});

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>;
}

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route
            path="/admin/catalog/:tab?"
            element={
              <>
                <AdminCatalogPage />
                <Where />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const rowOf = async (text: string) => (await screen.findByText(text)).closest('tr')!;

// Thân gửi đi thật (JSON bỏ trường undefined): để thấy "vắng" khác "".
const wire = (body: unknown) => JSON.parse(JSON.stringify(body)) as Record<string, unknown>;

async function openForm(user: ReturnType<typeof userEvent.setup>, button: string) {
  await user.click(await screen.findByRole('button', { name: button }));
  return screen.getByRole('dialog');
}

describe('tabs', () => {
  it('opens on the products tab and switches tabs through the URL', async () => {
    const user = userEvent.setup();
    renderAt('/admin/catalog');
    expect(await screen.findByText('/admin/catalog/products')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Dịch vụ & loại chuồng' }));
    expect(screen.getByTestId('where')).toHaveTextContent('/admin/catalog/services');
  });
});

describe('products (UC29)', () => {
  it('lists products with the prescription badge and the price', async () => {
    renderAt('/admin/catalog/products');
    const row = await rowOf('Amoxicillin 250mg');
    expect(within(row).getByText('Kê đơn')).toBeInTheDocument();
    expect(within(row).getByText(/8\.000/)).toBeInTheDocument();
  });

  it('creates a product that then shows in the list', async () => {
    const user = userEvent.setup();
    renderAt('/admin/catalog/products');
    const form = await openForm(user, 'Thêm sản phẩm');
    await user.selectOptions(within(form).getByLabelText('Danh mục'), 'Phụ kiện');
    await user.type(within(form).getByLabelText('SKU'), 'DAY-DAT-1M');
    await user.type(within(form).getByLabelText('Tên sản phẩm'), 'Dây dắt 1m');
    await user.type(within(form).getByLabelText('Đơn vị tính'), 'cái');
    await user.type(within(form).getByLabelText('Giá (đ)'), '75000');
    await user.click(within(form).getByRole('button', { name: 'Tạo sản phẩm' }));

    expect(await screen.findByText('Dây dắt 1m')).toBeInTheDocument();
  });

  // BE (ProductService.update): mô tả vắng / null = giữ nguyên, "" = xóa (T49 nhóm 2, hàng 4).
  it('edit: clearing the description sends "" and the product really loses it', async () => {
    await apiClient.patch('/api/products/1', { description: 'Hạt cho chó nhỏ' });
    const update = vi.spyOn(catalogApi, 'updateProduct');
    const user = userEvent.setup();
    renderAt('/admin/catalog/products');
    await user.click(await screen.findByRole('button', { name: 'Sửa Hạt Royal Canin Mini Adult 2kg' }));
    const form = screen.getByRole('dialog');
    const field = within(form).getByLabelText('Mô tả');
    expect(field).toHaveValue('Hạt cho chó nhỏ');
    await user.clear(field);
    await user.click(within(form).getByRole('button', { name: 'Lưu thay đổi' }));

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1));
    expect(wire(update.mock.calls[0][1])).toHaveProperty('description', '');
    await waitFor(async () => expect((await apiClient.get('/api/products/1')).data.data.description).toBe(''));
  });

  it('edit: leaving the description alone does not send it (a null description is not turned into "")', async () => {
    const update = vi.spyOn(catalogApi, 'updateProduct');
    const user = userEvent.setup();
    renderAt('/admin/catalog/products');
    await user.click(await screen.findByRole('button', { name: 'Sửa Pate Whiskas 85g' }));
    const form = screen.getByRole('dialog');
    await user.clear(within(form).getByLabelText('Tên sản phẩm'));
    await user.type(within(form).getByLabelText('Tên sản phẩm'), 'Pate Whiskas 85g mới');
    await user.click(within(form).getByRole('button', { name: 'Lưu thay đổi' }));

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1));
    expect(wire(update.mock.calls[0][1])).not.toHaveProperty('description');
    expect((await apiClient.get('/api/products/2')).data.data).toMatchObject({ name: 'Pate Whiskas 85g mới', description: null });
  });

  it('create: a blank description is left out of the body', async () => {
    const create = vi.spyOn(catalogApi, 'createProduct');
    const user = userEvent.setup();
    renderAt('/admin/catalog/products');
    const form = await openForm(user, 'Thêm sản phẩm');
    await user.selectOptions(within(form).getByLabelText('Danh mục'), 'Phụ kiện');
    await user.type(within(form).getByLabelText('SKU'), 'DAY-DAT-2M');
    await user.type(within(form).getByLabelText('Tên sản phẩm'), 'Dây dắt 2m');
    await user.type(within(form).getByLabelText('Đơn vị tính'), 'cái');
    await user.type(within(form).getByLabelText('Giá (đ)'), '95000');
    await user.type(within(form).getByLabelText('Mô tả'), '   ');
    await user.click(within(form).getByRole('button', { name: 'Tạo sản phẩm' }));

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(wire(create.mock.calls[0][0])).not.toHaveProperty('description');
  });

  it('turns on expiry tracking for a vaccine and does not let it be turned off (BR-SP-05)', async () => {
    const user = userEvent.setup();
    renderAt('/admin/catalog/products');
    const form = await openForm(user, 'Thêm sản phẩm');
    await user.selectOptions(within(form).getByLabelText('Loại sản phẩm'), 'Vaccine');

    const expiry = within(form).getByRole('checkbox', { name: /Quản lý hạn dùng/ });
    expect(expiry).toBeChecked();
    expect(expiry).toBeDisabled();
  });

  it('asks for a vaccine type when the product is a vaccine (BR-SP-07)', async () => {
    const user = userEvent.setup();
    renderAt('/admin/catalog/products');
    const form = await openForm(user, 'Thêm sản phẩm');
    await user.selectOptions(within(form).getByLabelText('Danh mục'), 'Vaccine');
    await user.type(within(form).getByLabelText('SKU'), 'VAC-NEW');
    await user.type(within(form).getByLabelText('Tên sản phẩm'), 'Vaccine mới');
    await user.selectOptions(within(form).getByLabelText('Loại sản phẩm'), 'Vaccine');
    await user.type(within(form).getByLabelText('Đơn vị tính'), 'liều');
    await user.type(within(form).getByLabelText('Giá (đ)'), '200000');
    await user.click(within(form).getByRole('button', { name: 'Tạo sản phẩm' }));

    expect(within(form).getByRole('alert')).toHaveTextContent('Sản phẩm vaccine phải gắn loại vaccine');
  });
});

describe('services (UC30)', () => {
  it('requires Khám or Tiêm for a Khám/Tiêm service (BR-SP-06)', async () => {
    const user = userEvent.setup();
    renderAt('/admin/catalog/services');
    const form = await openForm(user, 'Thêm dịch vụ');
    await user.type(within(form).getByLabelText('Tên dịch vụ'), 'Siêu âm');
    await user.selectOptions(within(form).getByLabelText('Nhóm dịch vụ'), 'Khám/Tiêm');
    await user.type(within(form).getByLabelText('Giá (đ)'), '300000');
    await user.click(within(form).getByRole('button', { name: 'Tạo dịch vụ' }));

    expect(within(form).getByRole('alert')).toHaveTextContent('Dịch vụ nhóm Khám/Tiêm phải chọn loại Khám hoặc Tiêm');
  });

  it('edit: clearing the description sends "" and the service really loses it (BE: absent = keep, "" = clear)', async () => {
    await apiClient.patch('/api/services/3', { description: 'Tắm bằng sữa tắm riêng' });
    const update = vi.spyOn(catalogApi, 'updateService');
    const user = userEvent.setup();
    renderAt('/admin/catalog/services');
    await user.click(await screen.findByRole('button', { name: 'Sửa Tắm & sấy' }));
    const form = screen.getByRole('dialog');
    const field = within(form).getByLabelText('Mô tả');
    expect(field).toHaveValue('Tắm bằng sữa tắm riêng');
    await user.clear(field);
    await user.click(within(form).getByRole('button', { name: 'Lưu thay đổi' }));

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1));
    expect(wire(update.mock.calls[0][1])).toHaveProperty('description', '');
    await waitFor(async () => {
      const list = (await apiClient.get('/api/services')).data.data as { serviceId: number; description: string | null }[];
      expect(list.find((s) => s.serviceId === 3)?.description).toBe('');
    });
  });

  it('edit: leaving the description alone does not send it; create with a blank one leaves it out', async () => {
    const update = vi.spyOn(catalogApi, 'updateService');
    const create = vi.spyOn(catalogApi, 'createService');
    const user = userEvent.setup();
    renderAt('/admin/catalog/services');
    await user.click(await screen.findByRole('button', { name: 'Sửa Tắm & sấy' }));
    let form = screen.getByRole('dialog');
    await user.click(within(form).getByRole('button', { name: 'Lưu thay đổi' }));
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1));
    expect(wire(update.mock.calls[0][1])).not.toHaveProperty('description');

    form = await openForm(user, 'Thêm dịch vụ');
    await user.type(within(form).getByLabelText('Tên dịch vụ'), 'Gỡ rối lông');
    await user.selectOptions(within(form).getByLabelText('Nhóm dịch vụ'), 'Thẩm mỹ');
    await user.type(within(form).getByLabelText('Giá (đ)'), '90000');
    await user.click(within(form).getByRole('button', { name: 'Tạo dịch vụ' }));
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(wire(create.mock.calls[0][0])).not.toHaveProperty('description');
  });

  it('creates a kennel type as a boarding service with species and max weight (BR-SP-04)', async () => {
    const user = userEvent.setup();
    renderAt('/admin/catalog/services');
    const form = await openForm(user, 'Thêm dịch vụ');
    await user.type(within(form).getByLabelText('Tên dịch vụ'), 'Chuồng chó lớn');
    await user.selectOptions(within(form).getByLabelText('Nhóm dịch vụ'), 'Lưu trú (loại chuồng)');
    await user.selectOptions(within(form).getByLabelText('Loài'), 'Chó');
    await user.type(within(form).getByLabelText('Cân nặng tối đa (kg)'), '40');
    await user.type(within(form).getByLabelText('Giá theo đêm (đ)'), '300000');
    await user.click(within(form).getByRole('button', { name: 'Tạo dịch vụ' }));

    const row = await rowOf('Chuồng chó lớn');
    expect(within(row).getByText('Chó · tối đa 40 kg')).toBeInTheDocument();
  });
});

describe('vaccine types (UC31)', () => {
  it('refuses to delete a vaccine type that products or protocols use (BR-SP-07)', async () => {
    const user = userEvent.setup();
    renderAt('/admin/catalog/vaccine-types');
    await screen.findByText('Ho cũi'); // dữ liệu đã tải
    const row = screen.getAllByRole('row').find((r) => r.textContent?.includes('Dại') && r.textContent.includes('Chó'))!;
    await user.click(within(row).getByRole('button', { name: 'Xóa Dại' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Xóa' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('chỉ ngừng sử dụng');
  });

  it('deletes a vaccine type nobody uses', async () => {
    const user = userEvent.setup();
    renderAt('/admin/catalog/vaccine-types');
    await user.click(within(await rowOf('Ho cũi')).getByRole('button', { name: 'Xóa Ho cũi' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Xóa' }));

    await screen.findByText('Đã xóa loại vaccine Ho cũi');
    expect(screen.queryByText('Ho cũi')).not.toBeInTheDocument();
  });
});

describe('vaccination protocols (UC31)', () => {
  it('adds a protocol row for a vaccine type of the chosen species', async () => {
    const user = userEvent.setup();
    renderAt('/admin/catalog/protocols');
    const form = await openForm(user, 'Thêm dòng phác đồ');
    await user.selectOptions(within(form).getByLabelText('Loài'), 'Chó');
    await user.selectOptions(within(form).getByLabelText('Loại vaccine'), 'Ho cũi');
    await user.type(within(form).getByLabelText('Mũi thứ'), '1');
    await user.type(within(form).getByLabelText('Khoảng cách tới mũi kế (ngày)'), '365');
    await user.type(within(form).getByLabelText('Tuổi tối thiểu (tuần)'), '12');
    await user.click(within(form).getByRole('button', { name: 'Thêm vào phác đồ' }));

    expect(await rowOf('Ho cũi')).toBeInTheDocument();
  });
});
