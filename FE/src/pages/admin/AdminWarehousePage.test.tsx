import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeAll, describe, expect, it } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { addDays, localIsoDate } from '../../shared/utils/branch-schedule';
import { AdminWarehousePage } from './AdminWarehousePage';

// Kho chi nhánh 1 trên mock (inventory.mock.ts): Rabisin còn 15 / 17 (lô RB2309 hết hạn), Pate dưới tồn tối thiểu,
// phiếu #1 đã nhập (lô RB2401 không đủ để hủy), phiếu #2 nháp có dòng Vanguard thiếu số lô / hạn dùng.

beforeAll(() => installMockApi(apiClient));

const signIn = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function Where() {
  const { pathname, search } = useLocation();
  return <p data-testid="where">{pathname + search}</p>;
}

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route
            path="/admin/warehouse/:tab?"
            element={
              <>
                <AdminWarehousePage />
                <Where />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const rowOf = async (text: string) => (await screen.findByText(text)).closest('tr')!;

describe('AdminWarehousePage (T24 — UC73–76)', () => {
  it('lễ tân chỉ thấy tab Tồn kho, mở thẳng tab phiếu nhập bị đưa về tồn kho, không sửa tồn tối thiểu', async () => {
    await signIn('reception@store1.vn');
    renderAt('/admin/warehouse/receipts');
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/admin/warehouse/stock'));
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Tồn kho']);
    const row = await rowOf('Vaccine Dại Rabisin');
    expect(row).toHaveTextContent('15 / 17 liều');
    expect(within(row).getByText('Có lô hết hạn')).toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: /Tồn tối thiểu/ })).not.toBeInTheDocument();
  });

  it('quản lý: 4 tab, lọc dưới tồn tối thiểu, xem lô có cờ hết hạn, đặt tồn tối thiểu', async () => {
    await signIn('manager@store1.vn');
    const user = renderAt('/admin/warehouse');
    await rowOf('Hạt Royal Canin Mini Adult 2kg');
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Tồn kho', 'Phiếu nhập', 'Điều chỉnh tồn', 'Nhà cung cấp']);

    await user.click(screen.getByLabelText('Chỉ hàng dưới tồn tối thiểu'));
    await waitFor(() => expect(screen.queryByText('Hạt Royal Canin Mini Adult 2kg')).not.toBeInTheDocument());
    expect(screen.getByText('Pate Whiskas 85g')).toBeInTheDocument();
    await user.click(screen.getByLabelText('Chỉ hàng dưới tồn tối thiểu'));

    await user.click(within(await rowOf('Vaccine Dại Rabisin')).getByRole('button', { name: 'Xem lô Vaccine Dại Rabisin' }));
    const lots = screen.getByRole('dialog');
    expect(await within(lots).findByText('RB2309')).toBeInTheDocument();
    expect(within(lots).getByText('Đã hết hạn')).toBeInTheDocument();
    await user.click(within(lots).getByRole('button', { name: 'Đóng' }));

    await user.click(within(await rowOf('Pate Whiskas 85g')).getByRole('button', { name: 'Tồn tối thiểu Pate Whiskas 85g' }));
    const form = screen.getByRole('dialog');
    const input = within(form).getByLabelText('Tồn tối thiểu (gói)');
    await user.clear(input);
    await user.type(input, '2');
    await user.click(within(form).getByRole('button', { name: 'Lưu' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Đã đặt tồn tối thiểu của Pate Whiskas 85g là 2 gói');
  });

  it('nhà cung cấp: xóa nhà cung cấp đã có phiếu báo BR-KO-02; ngừng hợp tác', async () => {
    await signIn('manager@store1.vn');
    const user = renderAt('/admin/warehouse/suppliers');
    await user.click(within(await rowOf('Công ty Royal Canin Việt Nam')).getByRole('button', { name: 'Xóa Công ty Royal Canin Việt Nam' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Xóa' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('chỉ chuyển ngừng hợp tác được');

    await user.click(within(await rowOf('Dược phẩm Thú y Hanvet')).getByRole('button', { name: 'Ngừng hợp tác' }));
    expect(await within(await rowOf('Dược phẩm Thú y Hanvet')).findByRole('button', { name: 'Hợp tác lại' })).toBeInTheDocument();
  });

  it('phiếu nhập: xác nhận nháp thiếu lô báo BR-KO-03; bổ sung lô rồi nhập kho; hủy phiếu thiếu tồn dẫn sang điều chỉnh', async () => {
    await signIn('manager@store1.vn');
    const user = renderAt('/admin/warehouse/receipts');
    await user.click(await screen.findByRole('button', { name: /^Mở phiếu PN-\d{6}-0002$/ }));
    let detail = screen.getByRole('dialog');
    await user.click(await within(detail).findByRole('button', { name: 'Xác nhận nhập kho' }));
    expect(await within(detail).findByRole('alert')).toHaveTextContent('Vaccine 5 bệnh Vanguard: sản phẩm quản lý hạn dùng phải có số lô và hạn dùng');

    await user.click(within(detail).getByRole('button', { name: 'Sửa' }));
    const form = screen.getByRole('dialog');
    await user.type(await within(form).findByLabelText('Dòng 2 — số lô'), 'VG2402');
    fireEvent.change(within(form).getByLabelText('Dòng 2 — hạn dùng'), { target: { value: addDays(localIsoDate(), 160) } });
    await user.click(within(form).getByRole('button', { name: 'Lưu nháp' }));
    detail = await screen.findByRole('dialog');
    await user.click(await within(detail).findByRole('button', { name: 'Xác nhận nhập kho' }));
    expect(await within(detail).findByText('Đã nhập kho')).toBeInTheDocument();
    await user.click(within(detail).getByRole('button', { name: 'Đóng' }));

    await user.click(await screen.findByRole('button', { name: /^Mở phiếu PN-\d{6}-0001$/ }));
    detail = screen.getByRole('dialog');
    await user.click(await within(detail).findByRole('button', { name: 'Hủy phiếu' }));
    await user.click(within(detail).getByRole('button', { name: 'Xác nhận hủy phiếu' }));
    expect(await within(detail).findByRole('alert')).toHaveTextContent('phiếu điều chỉnh');
    await user.click(within(detail).getByRole('button', { name: 'Lập phiếu điều chỉnh' }));
    expect(screen.getByTestId('where')).toHaveTextContent('/admin/warehouse/adjustments');
  });

  it('điều chỉnh tồn: "Khác" bắt ghi chú; lô mặc định chọn sẵn; điều chỉnh lô hết hạn thành công', async () => {
    await signIn('manager@store1.vn');
    const user = renderAt('/admin/warehouse/adjustments');
    await user.click(await screen.findByRole('button', { name: 'Lập phiếu điều chỉnh' }));
    const form = screen.getByRole('dialog');
    await user.selectOptions(within(form).getByLabelText('Lý do'), 'OTHER');
    await user.click(within(form).getByRole('button', { name: 'Lưu phiếu điều chỉnh' }));
    expect(within(form).getByRole('alert')).toHaveTextContent('Lý do "Khác" bắt buộc ghi chú');

    // Sản phẩm không quản lý hạn dùng: lô mặc định được chọn sẵn (A4).
    await waitFor(() => expect(within(form).getAllByRole('option', { name: 'Hạt Royal Canin Mini Adult 2kg' }).length).toBeGreaterThan(0));
    await user.selectOptions(within(form).getByLabelText('Dòng 1 — sản phẩm'), '1');
    await waitFor(() => expect(within(form).getByLabelText('Dòng 1 — lô')).toHaveValue('11'));

    await user.selectOptions(within(form).getByLabelText('Lý do'), 'EXPIRED');
    await user.selectOptions(within(form).getByLabelText('Dòng 1 — sản phẩm'), '5');
    await waitFor(() => expect(within(form).getAllByRole('option', { name: /RB2309/ }).length).toBe(1));
    await user.selectOptions(within(form).getByLabelText('Dòng 1 — lô'), '16');
    // Ô số trong jsdom bỏ dấu "-" khi gõ từng ký tự: đặt giá trị trực tiếp.
    fireEvent.change(within(form).getByLabelText('Dòng 1 — số điều chỉnh'), { target: { value: '-2' } });
    await user.click(within(form).getByRole('button', { name: 'Lưu phiếu điều chỉnh' }));
    expect(await screen.findByRole('status')).toHaveTextContent(/Đã lập phiếu điều chỉnh DC-\d{6}-0002/);
  });
});
