import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { focusManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { catalogApi } from '../../shared/api/catalog.api';
import { inventoryApi } from '../../shared/api/inventory.api';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import type { InventoryItem, StockLot } from '../../shared/types/inventory';
import { restoreTimezone, setTimezone } from '../../test/timezone';
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

/** Nhập thêm một lô còn `days` ngày hạn (phiếu nhập → xác nhận) để dựng lô sắp hết hạn trên mock; "hôm nay" theo ngày VN như mock. */
const receiveLot = async (productId: number, lotNumber: string, days: number) => {
  const today = localIsoDate();
  const receipt = await inventoryApi.createReceipt({
    supplierId: 1,
    receiptDate: today,
    lines: [{ productId, quantity: 5, unitCost: 1000, lotNumber, expiryDate: addDays(today, days) }],
  });
  await inventoryApi.confirmReceipt(receipt.receiptId);
};

describe('AdminWarehousePage (T24 — UC73–76)', () => {
  afterEach(() => {
    vi.useRealTimers();
    restoreTimezone();
  });

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

  it('quản lý: 8 tab, lọc dưới tồn tối thiểu, xem lô có cờ hết hạn, đặt tồn tối thiểu', async () => {
    await signIn('manager@store1.vn');
    const user = renderAt('/admin/warehouse');
    await rowOf('Hạt Royal Canin Mini Adult 2kg');
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Tồn kho', 'Phiếu nhập', 'Điều chỉnh tồn', 'Nhà cung cấp', 'Chuyển kho', 'Kiểm kê', 'Truy vết lô', 'Thiếu hụt vật tư']);

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

  // BE chỉ chặn ngày nhập tương lai lúc xác nhận (BR-KO-03); FE chặn sớm hơn ngay lúc lưu nháp, "hôm nay" theo giờ VN (T49 nhóm 2, hàng 5, 10).
  // Trình duyệt (và jsdom) chặn nộp form khi vượt `max` trước cả onSubmit; `fireEvent.submit` bỏ qua bước đó để thử riêng kiểm tra của FE.
  it('phiếu nhập: ô ngày nhập có max là hôm nay; ngày mai bị chặn trước khi lưu, hôm nay thì lưu được', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-27T10:00:00+07:00'));
    await signIn('manager@store1.vn');
    const create = vi.spyOn(inventoryApi, 'createReceipt');
    const user = renderAt('/admin/warehouse/receipts');
    await user.click(await screen.findByRole('button', { name: 'Tạo phiếu nhập' }));
    const form = screen.getByRole('dialog');
    await user.selectOptions(within(form).getByLabelText('Nhà cung cấp'), 'Công ty Royal Canin Việt Nam');
    await user.selectOptions(within(form).getByLabelText('Dòng 1 — sản phẩm'), 'Hạt Royal Canin Mini Adult 2kg (túi)');
    await user.type(within(form).getByLabelText('Dòng 1 — số lượng'), '5');
    await user.type(within(form).getByLabelText('Dòng 1 — giá nhập'), '380000');
    const date = within(form).getByLabelText('Ngày nhập');
    expect(date).toHaveValue('2026-10-27');
    expect(date).toHaveAttribute('max', '2026-10-27');

    fireEvent.change(date, { target: { value: '2026-10-28' } });
    await user.click(within(form).getByRole('button', { name: 'Lưu nháp' }));
    expect(date).toBeInvalid();
    expect(create).not.toHaveBeenCalled();
    fireEvent.submit(form.querySelector('form')!);
    expect(await within(form).findByRole('alert')).toHaveTextContent('Ngày nhập không được ở tương lai');
    expect(create).not.toHaveBeenCalled();

    fireEvent.change(date, { target: { value: '2026-10-27' } });
    await user.click(within(form).getByRole('button', { name: 'Lưu nháp' }));
    await waitFor(() => expect(create).toHaveBeenCalledWith(expect.objectContaining({ receiptDate: '2026-10-27' })));
  });

  it.each([
    ['Pacific/Auckland', '2026-10-27T23:59:00+07:00', '2026-10-27'], // máy đã sang 28/10
    ['America/Los_Angeles', '2026-10-28T00:01:00+07:00', '2026-10-28'], // máy còn ở 27/10
  ])('phiếu nhập: máy ở %s, lúc %s giờ VN thì ngày nhập mặc định và max là %s', async (tz, instant, expected) => {
    setTimezone(tz);
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(instant));
    await signIn('manager@store1.vn');
    const user = renderAt('/admin/warehouse/receipts');
    await user.click(await screen.findByRole('button', { name: 'Tạo phiếu nhập' }));
    const date = within(screen.getByRole('dialog')).getByLabelText('Ngày nhập');

    expect(date).toHaveValue(expected);
    expect(date).toHaveAttribute('max', expected);
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

  // BE cấm một lô xuất hiện hai lần trong phiếu (400 VALIDATION_FAILED "lotsDistinct: Mỗi lô chỉ được xuất hiện một lần trong phiếu").
  it('điều chỉnh tồn: hai dòng cùng một lô báo lỗi ở dòng sau và không gửi; đổi sang lô khác thì lưu được', async () => {
    await signIn('manager@store1.vn');
    const create = vi.spyOn(inventoryApi, 'createAdjustment');
    const user = renderAt('/admin/warehouse/adjustments');
    await user.click(await screen.findByRole('button', { name: 'Lập phiếu điều chỉnh' }));
    const form = screen.getByRole('dialog');
    await user.selectOptions(within(form).getByLabelText('Lý do'), 'EXPIRED');
    await user.click(within(form).getByRole('button', { name: 'Thêm dòng' }));
    await waitFor(() => expect(within(form).getAllByRole('option', { name: 'Vaccine Dại Rabisin' }).length).toBe(2));
    for (const row of [1, 2]) {
      await user.selectOptions(within(form).getByLabelText(`Dòng ${row} — sản phẩm`), '5');
      // Rabisin có hai lô (RB2401, RB2309): chờ đủ "Chọn lô…" + 2 lô rồi mới chọn.
      await waitFor(() => expect(within(within(form).getByLabelText(`Dòng ${row} — lô`)).getAllByRole('option').length).toBe(3));
      await user.selectOptions(within(form).getByLabelText(`Dòng ${row} — lô`), '16');
      // Ô số trong jsdom bỏ dấu "-" khi gõ từng ký tự: đặt giá trị trực tiếp.
      fireEvent.change(within(form).getByLabelText(`Dòng ${row} — số điều chỉnh`), { target: { value: '-1' } });
    }

    await user.click(within(form).getByRole('button', { name: 'Lưu phiếu điều chỉnh' }));
    expect(within(form).getByRole('alert')).toHaveTextContent('Dòng 2: lô đã chọn ở dòng 1');
    expect(create).not.toHaveBeenCalled();

    await user.selectOptions(within(form).getByLabelText('Dòng 2 — lô'), '15');
    await user.click(within(form).getByRole('button', { name: 'Lưu phiếu điều chỉnh' }));
    expect(await screen.findByRole('status')).toHaveTextContent(/Đã lập phiếu điều chỉnh DC-\d{6}-0002/);
    expect(create).toHaveBeenCalledTimes(1);
  });

  // L-15 (2): ô chọn sản phẩm chỉ lấy 100 dòng đầu (trần của BE); BE còn nhiều hơn thì form phải nói, không cắt im lặng.
  describe('ô chọn sản phẩm bị cắt ở 100 dòng', () => {
    const noteIn = (form: HTMLElement) => within(form).queryByRole('note');

    it('phiếu nhập: BE còn 130 sản phẩm thì nhắc số đang hiện; đủ sản phẩm thì không nhắc', async () => {
      await signIn('manager@store1.vn');
      const real = catalogApi.listProducts;
      const user = renderAt('/admin/warehouse/receipts');
      await user.click(await screen.findByRole('button', { name: 'Tạo phiếu nhập' }));
      const form = screen.getByRole('dialog');
      await waitFor(() => expect(within(form).getAllByRole('option', { name: /^Vaccine Dại Rabisin \(/ }).length).toBeGreaterThan(0));
      expect(noteIn(form)).not.toBeInTheDocument();
      await user.click(within(form).getByRole('button', { name: 'Hủy' }));

      vi.spyOn(catalogApi, 'listProducts').mockImplementation(async (q) => ({ ...(await real(q)), totalElements: 130 }));
      await user.click(screen.getByRole('button', { name: 'Tạo phiếu nhập' }));
      expect(await within(screen.getByRole('dialog')).findByRole('note')).toHaveTextContent(/^Chỉ hiện \d+ \/ 130 sản phẩm đầu tiên/);
    });

    it('phiếu điều chỉnh: BE còn 130 sản phẩm tồn kho thì nhắc số đang hiện', async () => {
      await signIn('manager@store1.vn');
      const real = inventoryApi.listInventory;
      vi.spyOn(inventoryApi, 'listInventory').mockImplementation(async (q) => ({ ...(await real(q)), totalElements: 130 }));
      const user = renderAt('/admin/warehouse/adjustments');
      await user.click(await screen.findByRole('button', { name: 'Lập phiếu điều chỉnh' }));
      expect(await within(screen.getByRole('dialog')).findByRole('note')).toHaveTextContent(/^Chỉ hiện \d+ \/ 130 sản phẩm đầu tiên/);
    });
  });

  // L-15 (3): `lotQuantity` từng được chụp lúc chọn lô; tồn đổi sau đó (người khác nhập / điều chỉnh) thì nhãn lô "còn N" cập nhật mà phép kiểm vượt tồn vẫn dùng số cũ.
  it('điều chỉnh tồn: tồn của lô đổi sau khi chọn lô thì phép kiểm "giảm quá tồn" dùng số mới, không phải số lúc chọn', async () => {
    await signIn('manager@store1.vn');
    const create = vi.spyOn(inventoryApi, 'createAdjustment');
    const user = renderAt('/admin/warehouse/adjustments');
    await user.click(await screen.findByRole('button', { name: 'Lập phiếu điều chỉnh' }));
    const form = screen.getByRole('dialog');
    await user.selectOptions(within(form).getByLabelText('Lý do'), 'EXPIRED');
    await user.selectOptions(within(form).getByLabelText('Dòng 1 — sản phẩm'), '5');
    await waitFor(() => expect(within(within(form).getByLabelText('Dòng 1 — lô')).getAllByRole('option').length).toBe(3));
    await user.selectOptions(within(form).getByLabelText('Dòng 1 — lô'), '15'); // RB2401 còn 15

    // Người khác nhập thêm 10 vào lô này; form lấy lại số khi cửa sổ lấy lại focus.
    await inventoryApi.createAdjustment({ reason: 'COUNT_MISMATCH', lines: [{ stockLotId: 15, quantityDelta: 10 }], note: 'Kiểm kê lại' });
    create.mockClear();
    act(() => {
      focusManager.setFocused(false);
      focusManager.setFocused(true);
    });
    await waitFor(() => expect(within(form).getByRole('option', { name: /RB2401.*còn 25/ })).toBeInTheDocument());

    // 20 vượt số cũ (15) nhưng không vượt tồn thật (25).
    fireEvent.change(within(form).getByLabelText('Dòng 1 — số điều chỉnh'), { target: { value: '-20' } });
    await user.click(within(form).getByRole('button', { name: 'Lưu phiếu điều chỉnh' }));
    expect(within(form).queryByRole('alert')).not.toBeInTheDocument();
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
  });

  // BE từ chối lưu và xác nhận nháp có nhà cung cấp ngừng hợp tác (400 BR-KO-02 "Nhà cung cấp … đã ngừng hợp tác, không chọn được ở phiếu nhập mới").
  it('phiếu nhập: nháp có nhà cung cấp ngừng hợp tác báo trước, khóa "Xác nhận nhập kho"; ở "Sửa" khóa "Lưu nháp" tới khi đổi nhà cung cấp', async () => {
    await signIn('manager@store1.vn');
    await inventoryApi.updateSupplier(1, { isActive: false }); // phiếu #2 (nháp) của Royal Canin
    await inventoryApi.updateSupplier(2, { isActive: false }); // phiếu #1 (đã nhập) của Hanvet
    const update = vi.spyOn(inventoryApi, 'updateReceipt');
    const user = renderAt('/admin/warehouse/receipts');

    // Phiếu đã nhập không còn gì để chặn: không cảnh báo.
    await user.click(await screen.findByRole('button', { name: /^Mở phiếu PN-\d{6}-0001$/ }));
    let detail = screen.getByRole('dialog');
    await within(detail).findByRole('button', { name: 'Hủy phiếu' });
    expect(within(detail).queryByRole('note')).not.toBeInTheDocument();
    await user.click(within(detail).getByRole('button', { name: 'Đóng' }));

    await user.click(await screen.findByRole('button', { name: /^Mở phiếu PN-\d{6}-0002$/ }));
    detail = screen.getByRole('dialog');
    expect(await within(detail).findByRole('note')).toHaveTextContent('Công ty Royal Canin Việt Nam đã ngừng hợp tác');
    expect(within(detail).getByRole('button', { name: 'Xác nhận nhập kho' })).toBeDisabled();
    expect(within(detail).getByRole('button', { name: 'Sửa' })).toBeEnabled();

    await user.click(within(detail).getByRole('button', { name: 'Sửa' }));
    const form = screen.getByRole('dialog');
    expect(await within(form).findByText(/đã ngừng hợp tác, không chọn được ở phiếu nhập mới/)).toBeInTheDocument();
    const saveDraft = within(form).getByRole('button', { name: 'Lưu nháp' });
    expect(saveDraft).toBeDisabled();

    await user.selectOptions(within(form).getByLabelText('Nhà cung cấp'), 'Virbac Việt Nam');
    expect(within(form).queryByText(/đã ngừng hợp tác/)).not.toBeInTheDocument();
    expect(saveDraft).toBeEnabled();
    await user.click(saveDraft);
    await waitFor(() => expect(update).toHaveBeenCalledWith(2, expect.objectContaining({ supplierId: 4 })));
  });

  // Badge "Sắp hết hạn" lấy từ cờ `expiringSoon` của BE (InventoryItem / StockLot, N = [CFG] stock.expiry_warning_days); mock tính cờ với N = 30 theo ngày VN như BE.
  it('tồn kho: lô gần nhất hạn trong 14 ngày hiện "Sắp hết hạn" ở bảng và ở danh sách lô', async () => {
    await signIn('manager@store1.vn');
    await receiveLot(4, 'VT-SOON', 14);
    const user = renderAt('/admin/warehouse');

    const row = await rowOf('Vitamin tổng hợp PetCare');
    expect(within(row).getByText('Sắp hết hạn')).toBeInTheDocument();
    expect(within(row).queryByText('Có lô hết hạn')).not.toBeInTheDocument();
    // Amoxicillin của dữ liệu mẫu có lô AMX2405 hạn +20 ngày.
    expect(within(await rowOf('Amoxicillin 250mg')).getByText('Sắp hết hạn')).toBeInTheDocument();

    await user.click(within(row).getByRole('button', { name: 'Xem lô Vitamin tổng hợp PetCare' }));
    const lots = screen.getByRole('dialog');
    expect(await within(lots).findByText('VT-SOON')).toBeInTheDocument();
    expect(within(lots).getByText('Sắp hết hạn')).toBeInTheDocument();
    expect(within(lots).queryByText('Còn hạn')).not.toBeInTheDocument();
  });

  it('tồn kho: hạn xa thì không có badge ở bảng và lô hiện "Còn hạn"; sản phẩm không có hạn dùng cũng không', async () => {
    await signIn('manager@store1.vn');
    const user = renderAt('/admin/warehouse');

    const far = await rowOf('Vaccine 5 bệnh Vanguard'); // lô VG2402 hạn +160 ngày
    expect(within(far).queryByText('Sắp hết hạn')).not.toBeInTheDocument();
    expect(within(far).queryByText('Có lô hết hạn')).not.toBeInTheDocument();
    const noExpiry = await rowOf('Hạt Royal Canin Mini Adult 2kg'); // lô mặc định, không hạn
    expect(within(noExpiry).queryByText('Sắp hết hạn')).not.toBeInTheDocument();

    await user.click(within(far).getByRole('button', { name: 'Xem lô Vaccine 5 bệnh Vanguard' }));
    const lots = screen.getByRole('dialog');
    expect(await within(lots).findByText('VG2402')).toBeInTheDocument();
    expect(within(lots).getByText('Còn hạn')).toBeInTheDocument();
    expect(within(lots).queryByText('Sắp hết hạn')).not.toBeInTheDocument();
  });

  it('tồn kho: sản phẩm có lô đã hết hạn chỉ hiện "Có lô hết hạn", dù còn lô khác sắp hết hạn', async () => {
    await signIn('manager@store1.vn');
    await receiveLot(5, 'RB-SOON', 10); // Rabisin đã có lô RB2309 hết hạn
    const user = renderAt('/admin/warehouse');

    const row = await rowOf('Vaccine Dại Rabisin');
    expect(within(row).getByText('Có lô hết hạn')).toBeInTheDocument();
    expect(within(row).queryByText('Sắp hết hạn')).not.toBeInTheDocument();

    // Danh sách lô vẫn đánh dấu từng lô: hết hạn / sắp hết hạn / còn hạn.
    await user.click(within(row).getByRole('button', { name: 'Xem lô Vaccine Dại Rabisin' }));
    const lots = screen.getByRole('dialog');
    const status = async (lotNumber: string) => (await within(lots).findByText(lotNumber)).closest('tr')!;
    expect(within(await status('RB2309')).getByText('Đã hết hạn')).toBeInTheDocument();
    expect(within(await status('RB-SOON')).getByText('Sắp hết hạn')).toBeInTheDocument();
    expect(within(await status('RB2401')).getByText('Còn hạn')).toBeInTheDocument();
  });

  // Cờ do mock tính từ ngày VN như BE: máy ở Auckland đã sang 31/10 trong khi VN còn 30/10 23:59, nên tính theo ngày máy sẽ bật cờ nhầm cho lô +31 ngày.
  it('tồn kho: hạn đúng +30 ngày (giờ VN) là sắp hết hạn, +31 ngày thì chưa — kể cả khi máy đã sang ngày mới', async () => {
    setTimezone('Pacific/Auckland');
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-30T23:59:00+07:00'));
    await signIn('manager@store1.vn');
    await receiveLot(4, 'VT-D30', 30);
    await receiveLot(6, 'VG-D31', 31); // Vanguard còn lô VG2402 hạn +160 ngày
    renderAt('/admin/warehouse');

    expect(within(await rowOf('Vitamin tổng hợp PetCare')).getByText('Sắp hết hạn')).toBeInTheDocument();
    const day31 = await rowOf('Vaccine 5 bệnh Vanguard');
    expect(within(day31).getByText('30/11/2026')).toBeInTheDocument(); // hạn gần nhất = lô +31 ngày, không phải lô +160
    expect(within(day31).queryByText('Sắp hết hạn')).not.toBeInTheDocument();
  });

  // Chứng minh FE dùng cờ của BE chứ không tự tính từ ngày: dựng dữ liệu mà cờ và ngày mâu thuẫn nhau (ADMIN đổi [CFG] thì ngưỡng khác 30).
  const stockRow = (over: Partial<InventoryItem>): InventoryItem => ({
    productId: 91,
    productName: 'Hàng thử',
    sku: 'T-91',
    unit: 'hộp',
    productType: 'GOODS',
    tracksExpiry: true,
    totalQuantity: 10,
    availableQuantity: 10,
    minQuantity: 0,
    belowMin: false,
    nearestExpiryDate: null,
    expiredQuantity: 0,
    expiringSoon: false,
    ...over,
  });

  it('tồn kho: badge "Sắp hết hạn" theo cờ expiringSoon của BE, không theo hạn gần nhất; có lô hết hạn thì chỉ đỏ', async () => {
    await signIn('manager@store1.vn');
    const today = localIsoDate();
    const content = [
      stockRow({ productId: 91, productName: 'Hàng cờ bật hạn xa', sku: 'T-91', nearestExpiryDate: addDays(today, 200), expiringSoon: true }),
      stockRow({ productId: 92, productName: 'Hàng cờ tắt hạn gần', sku: 'T-92', nearestExpiryDate: addDays(today, 10), expiringSoon: false }),
      stockRow({ productId: 93, productName: 'Hàng có lô hết hạn', sku: 'T-93', nearestExpiryDate: addDays(today, 200), expiredQuantity: 3, expiringSoon: true }),
    ];
    vi.spyOn(inventoryApi, 'listInventory').mockResolvedValue({ content, page: 0, size: 20, totalElements: 3, totalPages: 1 });
    renderAt('/admin/warehouse');

    const flagged = await rowOf('Hàng cờ bật hạn xa');
    expect(within(flagged).getByText('Sắp hết hạn')).toBeInTheDocument();
    expect(within(flagged).queryByText('Có lô hết hạn')).not.toBeInTheDocument();
    const unflagged = await rowOf('Hàng cờ tắt hạn gần');
    expect(within(unflagged).queryByText('Sắp hết hạn')).not.toBeInTheDocument();
    expect(within(unflagged).queryByText('Có lô hết hạn')).not.toBeInTheDocument();
    const expired = await rowOf('Hàng có lô hết hạn');
    expect(within(expired).getByText('Có lô hết hạn')).toBeInTheDocument();
    expect(within(expired).queryByText('Sắp hết hạn')).not.toBeInTheDocument();
  });

  it('danh sách lô: lô hết hạn đỏ, expiringSoon vàng dù hạn xa, expiringSoon tắt thì "Còn hạn" dù hạn gần', async () => {
    await signIn('manager@store1.vn');
    const today = localIsoDate();
    const lot = (stockLotId: number, lotNumber: string, days: number, over: Partial<StockLot> = {}): StockLot => ({
      stockLotId,
      lotNumber,
      expiryDate: addDays(today, days),
      quantity: 5,
      isDefault: false,
      expired: false,
      expiringSoon: false,
      ...over,
    });
    vi.spyOn(inventoryApi, 'listLots').mockResolvedValue([
      lot(1, 'L-EXPIRED', -3, { expired: true, expiringSoon: true }),
      lot(2, 'L-FLAG-ON', 200, { expiringSoon: true }),
      lot(3, 'L-FLAG-OFF', 10),
    ]);
    const user = renderAt('/admin/warehouse');

    await user.click(within(await rowOf('Vaccine Dại Rabisin')).getByRole('button', { name: 'Xem lô Vaccine Dại Rabisin' }));
    const lots = screen.getByRole('dialog');
    const status = async (lotNumber: string) => (await within(lots).findByText(lotNumber)).closest('tr')!;
    const expired = await status('L-EXPIRED');
    expect(within(expired).getByText('Đã hết hạn')).toBeInTheDocument();
    expect(within(expired).queryByText('Sắp hết hạn')).not.toBeInTheDocument();
    const on = await status('L-FLAG-ON');
    expect(within(on).getByText('Sắp hết hạn')).toBeInTheDocument();
    expect(within(on).queryByText('Còn hạn')).not.toBeInTheDocument();
    const off = await status('L-FLAG-OFF');
    expect(within(off).getByText('Còn hạn')).toBeInTheDocument();
    expect(within(off).queryByText('Sắp hết hạn')).not.toBeInTheDocument();
  });
});
