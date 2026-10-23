import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../../shared/api/axios';
import { inventoryApi } from '../../../shared/api/inventory.api';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { salesApi } from '../../../shared/api/sales.api';
import type { OrderSummary } from '../../../shared/types/sales';
import { usePosStore } from '../../../shared/stores/pos.store';
import { api, asAccount, pinClock, signInAs } from '../../../test/cashier-harness';
import { CheckoutPanel } from './CheckoutPanel';

// Mock (sales.mock.ts, cashier.mock.ts, inventory.mock.ts), chi nhánh 1, đồng hồ ghim thứ Năm 22/10/2026 10:00 (+07:00).
// Chờ thu: Order #2 (khách #1 Nguyễn Thu Trang: khám 150.000, Amoxicillin ×10, Pate ×2 = 266.000) · #3 (khách #4 Lê Thị Hoa: tiêm phòng
// 50.000 + vaccine 150.000 = 200.000) · #4 (khách #2 Lê Hoàng Long: Royal Canin ×1 = 450.000). Tồn: Royal Canin 12, Pate 3, Rabisin 15 + 2 hết hạn.
// Lễ tân #4 (reception@store1.vn) chưa có ca OPEN.

beforeAll(() => installMockApi(apiClient));

beforeEach(() => {
  localStorage.clear();
  pinClock();
  usePosStore.getState().clear();
});

afterEach(() => vi.useRealTimers());

const RECEPTION = 'reception@store1.vn';
const money = (text: string) => new RegExp(text.replace('.', '\\.') + '\\s*₫');

/** Lễ tân đăng nhập và mở ca thường bằng API (phần mở ca đã có test ở ShiftControls.test). */
async function withOpenShift() {
  await signInAs(RECEPTION);
  await salesApi.openShift({ afterHours: false });
}

/** Order bán lẻ đã chốt (PENDING) như luồng T34, bằng API. */
async function closedRetailOrder(customerId: number, productId: number, quantity: number) {
  const order = await salesApi.createRetailOrder(customerId);
  await salesApi.addLine(order.orderId, { lineType: 'GOODS', productId, quantity });
  return salesApi.closeOrder(order.orderId);
}

function renderPanel(hotkeysEnabled = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <CheckoutPanel hotkeysEnabled={hotkeysEnabled} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

async function pickCustomer(user: ReturnType<typeof userEvent.setup>, term: string, name: RegExp) {
  await user.type(await screen.findByPlaceholderText(/Tìm khách cần thu/), term);
  await user.click(await screen.findByRole('button', { name }));
}

const orderBox = (code: RegExp) => screen.findByRole('checkbox', { name: code });

describe('thu tiền gộp theo khách (UC70; BR-TG-02, BR-TG-03)', () => {
  it('chọn khách → liệt kê Order chờ thu (cả Order bán lẻ vừa chốt ở T34), tick sẵn tất cả và hiện tổng xem trước', async () => {
    await withOpenShift();
    const retail = await closedRetailOrder(1, 1, 1);
    const user = renderPanel();
    await pickCustomer(user, 'Trang', /Nguyễn Thu Trang/);

    const visit = await orderBox(/DH-\d{4}-00002/);
    const retailBox = await orderBox(new RegExp(retail.code));
    expect(visit).toBeChecked();
    expect(retailBox).toBeChecked();
    const list = screen.getByRole('list', { name: 'Order chờ thu của khách' });
    expect(within(list).getByText('Lượt tiếp nhận')).toBeInTheDocument();
    expect(within(list).getByText('Bán lẻ')).toBeInTheDocument();
    expect(within(list).getByText(money('266.000'))).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /^Thu 716\.000\s*₫/ })).toBeEnabled();
  });

  it('bỏ tick một Order thì tổng xem trước tính lại; bỏ hết thì không thu được', async () => {
    await withOpenShift();
    const retail = await closedRetailOrder(1, 1, 1);
    const user = renderPanel();
    await pickCustomer(user, 'Trang', /Nguyễn Thu Trang/);
    await screen.findByRole('button', { name: /^Thu 716\.000/ });

    await user.click(await orderBox(new RegExp(retail.code)));
    expect(await screen.findByRole('button', { name: /^Thu 266\.000/ })).toBeEnabled();

    await user.click(await orderBox(/DH-\d{4}-00002/));
    expect(await screen.findByText('Chọn ít nhất một Order để thu.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Thu / })).toBeDisabled();
  });

  it('một phiếu tối đa 20 Order: đủ 20 thì khóa các ô còn lại và nói lý do', async () => {
    await withOpenShift();
    const many: OrderSummary[] = Array.from({ length: 21 }, (_, i) => ({
      orderId: 100 + i,
      code: `DH-2610-${String(100 + i).padStart(5, '0')}`,
      source: 'RETAIL',
      status: 'PENDING',
      customerId: 1,
      customerName: 'Nguyễn Thu Trang',
      branchId: 1,
      totalAmount: 1_000,
      createdAt: '2026-10-22T03:00:00Z',
      pendingAt: '2026-10-22T03:05:00Z',
    }));
    vi.spyOn(salesApi, 'listPayableOrders').mockResolvedValue(many);
    vi.spyOn(salesApi, 'previewPayment').mockImplementation(async (ids) => ({ customerId: 1, branchId: 1, totalAmount: ids.length * 1_000, shortages: [] }));
    const user = renderPanel();
    await pickCustomer(user, 'Trang', /Nguyễn Thu Trang/);

    await screen.findByRole('button', { name: /^Thu 20\.000/ });
    const boxes = screen.getAllByRole('checkbox');
    expect(boxes.filter((b) => (b as HTMLInputElement).checked)).toHaveLength(20);
    expect(boxes[20]).toBeDisabled();
    expect(screen.getByText('Một phiếu thu tối đa 20 Order.')).toBeInTheDocument();
    await user.click(boxes[0]);
    await waitFor(() => expect(boxes[20]).toBeEnabled());
  });

  it('Order do tab Chờ thu đưa sang: chỉ tick Order đó, các Order khác của khách để lễ tân tự thu gộp', async () => {
    await withOpenShift();
    const retail = await closedRetailOrder(1, 1, 1);
    usePosStore.getState().attachOrder(2, 1);
    renderPanel();

    expect(await orderBox(/DH-\d{4}-00002/)).toBeChecked();
    expect(await orderBox(new RegExp(retail.code))).not.toBeChecked();
    expect(await screen.findByRole('button', { name: /^Thu 266\.000/ })).toBeEnabled();
    expect(await screen.findByText('Nguyễn Thu Trang')).toBeInTheDocument();
  });

  it('Order đã thu ở nơi khác thì rời khỏi phiếu (không giữ id cũ)', async () => {
    await withOpenShift();
    usePosStore.getState().attachOrder(99, 1);
    renderPanel();

    await orderBox(/DH-\d{4}-00002/);
    await waitFor(() => expect(usePosStore.getState().orderIds).toEqual([]));
  });

  it('"Đổi khách" xóa phiếu và quay về ô tìm khách', async () => {
    await withOpenShift();
    const user = renderPanel();
    await pickCustomer(user, 'Trang', /Nguyễn Thu Trang/);
    await orderBox(/DH-\d{4}-00002/);

    await user.click(screen.getByRole('button', { name: 'Đổi khách' }));
    expect(await screen.findByPlaceholderText(/Tìm khách cần thu/)).toBeInTheDocument();
    expect(usePosStore.getState()).toMatchObject({ customerId: null, orderIds: [] });
  });
});

const payButton = (amount: string) => screen.findByRole('button', { name: new RegExp(`^Thu ${amount.replace('.', '\\.')}`) });
const methodRadio = (name: string) => screen.getByRole('radio', { name });
const receipt = () => screen.findByRole('dialog');

describe('thu tiền mặt (UC70; BR-TG-02, BR-TG-04, BR-BH-04)', () => {
  it('thu gộp 2 Order: phiếu thu có mã, thu ngân, từng Order, tiền thối; Order hết chờ thu, kho bị trừ, phiếu xóa sạch', async () => {
    await withOpenShift();
    const retail = await closedRetailOrder(1, 1, 1);
    const createPayment = vi.spyOn(salesApi, 'createPayment');
    const user = renderPanel();
    await pickCustomer(user, 'Trang', /Nguyễn Thu Trang/);
    await payButton('716.000');

    await user.type(screen.getByLabelText('Khách đưa'), '1000000');
    expect(await screen.findByText(money('284.000'), { selector: '[data-testid="cash-change"]' })).toHaveTextContent('Thối');
    await user.click(await payButton('716.000'));

    const dialog = await receipt();
    expect(createPayment).toHaveBeenCalledWith({ orderIds: [2, retail.orderId], method: 'CASH', amount: 716_000 });
    expect(within(dialog).getByText(money('Đã thu 716.000'))).toBeInTheDocument();
    expect(within(dialog).getByText(/^PT-2610-\d{5}$/)).toBeInTheDocument();
    expect(within(dialog).getByText('Lan Chi')).toBeInTheDocument();
    const lines = within(dialog).getByRole('list', { name: 'Order đã thu' });
    expect(within(lines).getByText(/DH-\d{4}-00002/)).toBeInTheDocument();
    expect(within(lines).getByText(retail.code)).toBeInTheDocument();
    expect(within(dialog).getByText('Tiền thối').parentElement).toHaveTextContent(money('284.000'));
    expect(within(dialog).getByText('Khách đưa').parentElement).toHaveTextContent(money('1.000.000'));

    // Phiếu thu sạch, Order không còn chờ thu, kho bị trừ (Pate 3 → 1, Royal Canin 12 → 11), ca đếm 1 phiếu.
    expect(usePosStore.getState()).toMatchObject({ customerId: null, orderIds: [] });
    expect(await salesApi.listPayableOrders(1)).toEqual([]);
    expect((await inventoryApi.getInventoryItem(2)).availableQuantity).toBe(1);
    expect((await inventoryApi.getInventoryItem(1)).availableQuantity).toBe(11);
    expect((await salesApi.getMyShift()).shift?.paymentCount).toBe(1);

    await user.click(within(dialog).getByRole('button', { name: 'Xong' }));
    expect(await screen.findByPlaceholderText(/Tìm khách cần thu/)).toBeInTheDocument();
  });

  it('số tiền gửi lên luôn bằng tổng xem trước, không phải số "Khách đưa" (đưa dư thì chỉ tính tiền thối)', async () => {
    await withOpenShift();
    const createPayment = vi.spyOn(salesApi, 'createPayment');
    usePosStore.getState().attachOrder(2, 1);
    const user = renderPanel();
    await user.type(await screen.findByLabelText('Khách đưa'), '300000');

    expect(await screen.findByText(money('34.000'), { selector: '[data-testid="cash-change"]' })).toHaveTextContent('Thối');
    await user.click(await payButton('266.000'));
    await receipt();
    expect(createPayment).toHaveBeenCalledWith({ orderIds: [2], method: 'CASH', amount: 266_000 });
  });

  // BR-TG-02: "Không cho xác nhận khi số tiền thu khác tổng tiền phải trả." Khách đưa thiếu thì thu ngân chưa thu đủ, không được xác nhận.
  describe('"Khách đưa" thiếu thì không được xác nhận thu (BR-TG-02, tiền mặt)', () => {
    it('đưa thiếu: nút Thu tắt, nói rõ còn thiếu bao nhiêu, Ctrl+Enter cũng không thu', async () => {
      await withOpenShift();
      const createPayment = vi.spyOn(salesApi, 'createPayment');
      usePosStore.getState().attachOrder(2, 1);
      const user = renderPanel();
      const pay = await payButton('266.000');
      await user.type(screen.getByLabelText('Khách đưa'), '100000');

      expect(await screen.findByText(/Khách đưa chưa đủ: còn thiếu 166\.000\s*₫/)).toBeInTheDocument();
      expect(pay).toBeDisabled();
      expect(screen.getByLabelText('Khách đưa')).toHaveAttribute('aria-invalid', 'true');
      expect(screen.queryByTestId('cash-change')).not.toBeInTheDocument();

      await user.keyboard('{Control>}{Enter}{/Control}');
      await new Promise((r) => setTimeout(r, 100));
      expect(createPayment).not.toHaveBeenCalled();
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect((await salesApi.getOrder(2)).status).toBe('PENDING');
    });

    it('nhập 0 cũng là đã nhập (thiếu cả tổng): nút Thu tắt', async () => {
      await withOpenShift();
      usePosStore.getState().attachOrder(2, 1);
      const user = renderPanel();
      const pay = await payButton('266.000');
      await user.type(screen.getByLabelText('Khách đưa'), '0');

      expect(await screen.findByText(/Khách đưa chưa đủ: còn thiếu 266\.000\s*₫/)).toBeInTheDocument();
      expect(pay).toBeDisabled();
    });

    it('để trống: vẫn thu được (ô này tùy chọn, không nằm trong request)', async () => {
      await withOpenShift();
      const createPayment = vi.spyOn(salesApi, 'createPayment');
      usePosStore.getState().attachOrder(2, 1);
      const user = renderPanel();
      const pay = await payButton('266.000');

      expect(pay).toBeEnabled();
      expect(screen.queryByText(/Khách đưa chưa đủ/)).not.toBeInTheDocument();
      await user.click(pay);
      await receipt();
      expect(createPayment).toHaveBeenCalledWith({ orderIds: [2], method: 'CASH', amount: 266_000 });
    });

    it('đưa đúng tổng thì thu được; đưa dư thì thu được và phiếu thu ghi tiền thối', async () => {
      await withOpenShift();
      usePosStore.getState().attachOrder(2, 1);
      const user = renderPanel();
      const pay = await payButton('266.000');

      await user.type(screen.getByLabelText('Khách đưa'), '266000');
      await waitFor(() => expect(pay).toBeEnabled());
      expect(screen.queryByText(/Khách đưa chưa đủ/)).not.toBeInTheDocument();

      await user.clear(screen.getByLabelText('Khách đưa'));
      await user.type(screen.getByLabelText('Khách đưa'), '300000');
      await waitFor(() => expect(pay).toBeEnabled());
      expect(screen.getByTestId('cash-change')).toHaveTextContent(money('34.000'));
      await user.click(pay);

      const dialog = await receipt();
      expect(within(dialog).getByText('Tiền thối').parentElement).toHaveTextContent(money('34.000'));
    });

    it('đổi tổng bằng cách bỏ / tick Order thì điều kiện tính lại ngay', async () => {
      await withOpenShift();
      const retail = await closedRetailOrder(1, 1, 1);
      const user = renderPanel();
      await pickCustomer(user, 'Trang', /Nguyễn Thu Trang/);
      const pay = await payButton('716.000');
      await user.type(screen.getByLabelText('Khách đưa'), '300000');

      // 300.000 < 716.000 (hai Order): chưa đủ.
      expect(await screen.findByText(/Khách đưa chưa đủ: còn thiếu 416\.000\s*₫/)).toBeInTheDocument();
      expect(pay).toBeDisabled();

      // Bỏ Order bán lẻ: tổng 266.000, 300.000 đủ và có tiền thối.
      await user.click(await orderBox(new RegExp(retail.code)));
      const smaller = await payButton('266.000');
      await waitFor(() => expect(smaller).toBeEnabled());
      expect(screen.queryByText(/Khách đưa chưa đủ/)).not.toBeInTheDocument();
      expect(screen.getByTestId('cash-change')).toHaveTextContent(money('34.000'));

      // Tick lại: tổng 716.000, lại thiếu.
      await user.click(await orderBox(new RegExp(retail.code)));
      expect(await screen.findByText(/Khách đưa chưa đủ: còn thiếu 416\.000\s*₫/)).toBeInTheDocument();
      expect(await payButton('716.000')).toBeDisabled();
    });

    it('chuyển khoản không bị ràng buộc bởi "Khách đưa" (vẫn chỉ cần tick đã nhận đủ tiền)', async () => {
      await withOpenShift();
      usePosStore.getState().attachOrder(2, 1);
      const user = renderPanel();
      await payButton('266.000');
      await user.type(screen.getByLabelText('Khách đưa'), '100000');
      await screen.findByText(/Khách đưa chưa đủ/);

      await user.click(methodRadio('Chuyển khoản'));
      expect(screen.queryByText(/Khách đưa chưa đủ/)).not.toBeInTheDocument();
      await user.click(screen.getByLabelText('Đã nhận đủ tiền chuyển khoản'));
      await waitFor(async () => expect(await payButton('266.000')).toBeEnabled());
    });
  });

  it('thu xong không có chữ lỗi nào hiện ra: không xem trước lại Order vừa thu (tránh nháy lỗi BR-TG-01)', async () => {
    await withOpenShift();
    const preview = vi.spyOn(salesApi, 'previewPayment');
    usePosStore.getState().attachOrder(2, 1);
    const user = renderPanel();
    const pay = await payButton('266.000');
    const before = preview.mock.calls.length;
    await user.click(pay);
    const dialog = await receipt();
    await new Promise((r) => setTimeout(r, 150));

    expect(preview.mock.calls.slice(before).filter(([ids]) => ids.includes(2))).toEqual([]);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText(/không còn ở trạng thái chờ thu|Chỉ thu được Order đang chờ thu tiền/)).not.toBeInTheDocument();
    expect(within(dialog).getByText(/^PT-/)).toBeInTheDocument();
  });

  it('Ctrl+Enter thu tiền khi phiếu hiện trên màn hình và đủ điều kiện; tắt khi bàn giấu phiếu', async () => {
    await withOpenShift();
    usePosStore.getState().attachOrder(2, 1);
    const user = renderPanel(false);
    await payButton('266.000');
    await user.keyboard('{Control>}{Enter}{/Control}');
    await new Promise((r) => setTimeout(r, 100));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect((await salesApi.getOrder(2)).status).toBe('PENDING');
  });

  it('Ctrl+Enter thu đúng một lần', async () => {
    await withOpenShift();
    const createPayment = vi.spyOn(salesApi, 'createPayment');
    usePosStore.getState().attachOrder(2, 1);
    const user = renderPanel(true);
    await payButton('266.000');
    await user.keyboard('{Control>}{Enter}{/Control}');
    await receipt();
    expect(createPayment).toHaveBeenCalledTimes(1);
  });

  it('Order bị thu ở nơi khác trong lúc đang xem: 409 báo bằng lời thường và danh sách tải lại, Order biến mất', async () => {
    await withOpenShift();
    usePosStore.getState().attachOrder(2, 1);
    const user = renderPanel();
    const pay = await payButton('266.000');
    await salesApi.createPayment({ orderIds: [2], method: 'CASH', amount: 266_000 });
    await user.click(pay);

    expect(await screen.findByText('Khách không có Order chờ thu')).toBeInTheDocument();
    // Lỗi đi qua paymentErrorMessage và vẫn đọc được dù danh sách đã rỗng (phần thu tiền ẩn đi).
    expect(await screen.findByRole('alert')).toHaveTextContent('Có Order không còn chờ thu (đã thu hoặc đã hủy ở nơi khác).');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('ca bị chốt ở nơi khác ngay lúc thu: lỗi BR-TG-01 vẫn hiện (phần thu tiền ẩn đi vì không còn ca)', async () => {
    await signInAs(RECEPTION);
    const shift = await salesApi.openShift({ afterHours: false });
    usePosStore.getState().attachOrder(2, 1);
    const user = renderPanel();
    const pay = await payButton('266.000');
    await salesApi.closeShift(shift.shiftId, { countedCash: 0 });
    await user.click(pay);

    expect(await screen.findByRole('button', { name: 'Mở ca thường' })).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Bạn chưa mở ca thu ngân');
  });
});

describe('chuyển khoản (BR-TG-02: lễ tân xác nhận thủ công đã nhận tiền)', () => {
  it('phải đánh dấu "Đã nhận đủ tiền chuyển khoản" mới thu được; phiếu thu ghi chuyển khoản, không có tiền thối', async () => {
    await withOpenShift();
    const createPayment = vi.spyOn(salesApi, 'createPayment');
    usePosStore.getState().attachOrder(2, 1);
    const user = renderPanel();
    await payButton('266.000');

    await user.click(methodRadio('Chuyển khoản'));
    expect(screen.queryByLabelText('Khách đưa')).not.toBeInTheDocument();
    const pay = await payButton('266.000');
    expect(pay).toBeDisabled();
    expect(screen.getByText('Kiểm tra tài khoản đã nhận chuyển khoản rồi đánh dấu ô xác nhận.')).toBeInTheDocument();

    await user.click(screen.getByLabelText('Đã nhận đủ tiền chuyển khoản'));
    await waitFor(() => expect(pay).toBeEnabled());
    await user.click(pay);

    const dialog = await receipt();
    expect(createPayment).toHaveBeenCalledWith({ orderIds: [2], method: 'TRANSFER', amount: 266_000 });
    expect(within(dialog).getByText(/chuyển khoản/)).toBeInTheDocument();
    expect(within(dialog).queryByText('Tiền thối')).not.toBeInTheDocument();
  });
});

describe('thiếu tồn kho (BR-BH-04) và Order không kiểm kho', () => {
  it('có dòng thiếu thì từ chối thu, chỉ rõ dòng thiếu; bỏ Order thiếu khỏi phiếu thì thu được; không có "mua ngoài"', async () => {
    await withOpenShift();
    // Vitamin tổng hợp PetCare (sản phẩm #4) tồn 0: Order bán lẻ của khách #4 thiếu hàng. Order #3 của khách này chỉ có dịch vụ + vaccine.
    const vitamin = await closedRetailOrder(4, 4, 1);
    const user = renderPanel();
    await pickCustomer(user, 'Hoa', /Lê Thị Hoa/);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(vitamin.code);
    expect(alert).toHaveTextContent(/Vitamin tổng hợp PetCare: cần 1, còn 0/);
    expect(await screen.findByText(/Chưa thu được vì thiếu tồn kho/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Thu / })).toBeDisabled();
    expect(screen.queryByText(/mua ngoài/i)).not.toBeInTheDocument();

    await user.click(await orderBox(new RegExp(vitamin.code)));
    expect(await payButton('200.000')).toBeEnabled();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('Order chỉ có dịch vụ và vaccine vẫn thu được dù tồn vaccine về 0 (vaccine đã trừ khi tiêm)', async () => {
    await asAccount('manager@store1.vn', RECEPTION, () =>
      api.post('/api/stock-adjustments', { reason: 'DAMAGED', lines: [{ stockLotId: 15, quantityDelta: -15 }] }),
    );
    await withOpenShift();
    const user = renderPanel();
    await pickCustomer(user, 'Hoa', /Lê Thị Hoa/);

    await user.click(await payButton('200.000'));
    const dialog = await receipt();
    expect(within(dialog).getByText(money('Đã thu 200.000'))).toBeInTheDocument();
    expect((await salesApi.getOrder(3)).status).toBe('PAID');
  });
});
