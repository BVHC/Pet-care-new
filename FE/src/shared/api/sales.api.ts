// Order tại quầy theo docs/api/sales-v1.md #1–8, #10 (UC66, UC67). Chi nhánh lấy từ người gọi.
// Xem: A05–A08 của chi nhánh; tạo / chốt / hủy bán lẻ: lễ tân; hủy Order khách không thanh toán: BRANCH_MANAGER.
// Thu tiền, ca thu ngân, payable-orders (#12–21) thêm ở T35.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type { AddOrderLineRequest, AddOrderLineResult, Order, OrderQuery, OrderSummary, UpdateOrderLineRequest } from '../types/sales';

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
};
