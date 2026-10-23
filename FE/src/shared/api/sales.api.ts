// Order tại quầy theo docs/api/sales-v1.md #1–8, #10 (UC66, UC67). Chi nhánh lấy từ người gọi.
// Xem: A05–A08 của chi nhánh; tạo / chốt / hủy bán lẻ: lễ tân; hủy Order khách không thanh toán: BRANCH_MANAGER.
// Thu tiền, ca thu ngân, payable-orders (#12–21; T35): thu tiền / mở / chốt ca là lễ tân, xem danh sách ca và đối soát là
// BRANCH_MANAGER, xem phiếu thu và chi tiết ca là cả hai (chi tiết ca của lễ tân chỉ ca của mình).
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type {
  AddOrderLineRequest,
  AddOrderLineResult,
  CashierShift,
  CloseShiftRequest,
  CreatePaymentRequest,
  CurrentShift,
  OpenShiftRequest,
  Order,
  OrderQuery,
  OrderSummary,
  Payment,
  PaymentPreview,
  ReconcileShiftRequest,
  ShiftQuery,
  UpdateOrderLineRequest,
} from '../types/sales';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;
const patch = async <T>(url: string, body: object): Promise<T> => (await apiClient.patch(url, body)).data.data;

export const salesApi = {
  /** Order#2: hồ sơ khách bắt buộc (hợp đồng A1). */
  createRetailOrder: (customerId: number) => post<Order>('/api/orders', { customerId }),
  listOrders: (query: OrderQuery) => get<PageResponse<OrderSummary>>('/api/orders', query),
  getOrder: (orderId: number) => get<Order>(`/api/orders/${orderId}`),

  addLine: (orderId: number, body: AddOrderLineRequest) => post<AddOrderLineResult>(`/api/orders/${orderId}/lines`, body),
  updateLine: (orderId: number, lineId: number, body: UpdateOrderLineRequest) =>
    patch<Order>(`/api/orders/${orderId}/lines/${lineId}`, body),
  deleteLine: async (orderId: number, lineId: number): Promise<Order> =>
    (await apiClient.delete(`/api/orders/${orderId}/lines/${lineId}`)).data.data,

  /** Order#5: OPEN → PENDING, chờ thu ngân thu. */
  closeOrder: (orderId: number) => post<Order>(`/api/orders/${orderId}/close`),
  /** Order#7: hủy Order bán lẻ chưa chốt. */
  cancelOrder: (orderId: number) => post<Order>(`/api/orders/${orderId}/cancel`),
  /** Order#10: PENDING → CANCELLED, thất thu; lý do bắt buộc (BR-BH-05). */
  cancelUnpaid: (orderId: number, reason: string) => post<Order>(`/api/orders/${orderId}/cancel-unpaid`, { reason }),

  // ─── Thu tiền (UC70) ───
  /** #12: Order PENDING của khách tại chi nhánh lễ tân, cũ nhất trước — để chọn Order thu gộp. */
  listPayableOrders: (customerId: number) => get<OrderSummary[]>(`/api/customers/${customerId}/payable-orders`),
  /** #13: không ghi gì; trả tổng tiền và các dòng thiếu tồn (BR-BH-04). */
  previewPayment: (orderIds: number[]) => post<PaymentPreview>('/api/payments/preview', { orderIds }),
  /** #14: cần ca OPEN của chính mình (BR-TG-01); trừ kho FEFO cùng giao dịch. */
  createPayment: (body: CreatePaymentRequest) => post<Payment>('/api/payments', body),
  getPayment: (paymentId: number) => get<Payment>(`/api/payments/${paymentId}`),

  // ─── Ca thu ngân (UC71, UC72) ───
  /** #16: ca thường trong giờ mở cửa, ca ngoài giờ ở chi nhánh có cờ cấp cứu (BR-TG-05, BR-CN-05). */
  openShift: (body: OpenShiftRequest) => post<CashierShift>('/api/cashier-shifts', body),
  /** #17: chưa có ca → { open: false }. */
  getMyShift: () => get<CurrentShift>('/api/me/cashier-shift'),
  /** #18: chủ ca nhập số tiền mặt thực đếm. */
  closeShift: (shiftId: number, body: CloseShiftRequest) => post<CashierShift>(`/api/cashier-shifts/${shiftId}/close`, body),
  listShifts: (query: ShiftQuery) => get<PageResponse<CashierShift>>('/api/cashier-shifts', query),
  getShift: (shiftId: number) => get<CashierShift>(`/api/cashier-shifts/${shiftId}`),
  /** #21: BRANCH_MANAGER; ca tự chốt bắt buộc nhập countedCash. */
  reconcileShift: (shiftId: number, body: ReconcileShiftRequest) =>
    post<CashierShift>(`/api/cashier-shifts/${shiftId}/reconcile`, body),
};
