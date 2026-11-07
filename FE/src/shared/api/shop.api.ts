// Cửa hàng online (tầng 3) — docs/api/shop-v1.md #1–19 (UC61, 62, 64, 65). Giỏ và đơn của tôi: chỉ CUSTOMER (đơn của người khác = 404);
// xử lý đơn: lễ tân chi nhánh xử lý (A06), quản lý chi nhánh (A05) chỉ xem. Thanh toán ở payment.api.ts, trả hàng / hoàn tiền ở returns.api.ts.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type {
  Cart,
  FulfillmentBranch,
  OnlineOrder,
  OnlineOrderQuery,
  OnlineOrderQuote,
  OnlineOrderSummary,
  PlaceOnlineOrderRequest,
  QuoteQuery,
  ReasonRequest,
  SetCartItemRequest,
  ShipRequest,
} from '../types/shop';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;
const put = async <T>(url: string, body: object): Promise<T> => (await apiClient.put(url, body)).data.data;
const del = async <T>(url: string): Promise<T> => (await apiClient.delete(url)).data.data;

export const shopApi = {
  /** #1: sản phẩm ngừng kinh doanh tự bị loại. */
  getCart: () => get<Cart>('/api/me/cart'),
  /** #2: đặt số lượng 1–99 (thêm nếu chưa có). 400 BR-BH-07 (không phải GOODS / thuốc kê đơn / quá 30 dòng / số lượng ngoài 1–99); 404 sản phẩm. */
  setCartItem: (productId: number, body: SetCartItemRequest) => put<Cart>(`/api/me/cart/items/${productId}`, body),
  /** #3: sản phẩm không có trong giỏ vẫn trả giỏ hiện tại. */
  removeCartItem: (productId: number) => del<Cart>(`/api/me/cart/items/${productId}`),
  /** #4: 204. */
  clearCart: async (): Promise<void> => {
    await apiClient.delete('/api/me/cart');
  },
  /** #5: chi nhánh ACTIVE còn đủ hàng cho mọi dòng của giỏ (đã trừ phần giữ). */
  listFulfillmentBranches: () => get<FulfillmentBranch[]>('/api/me/online-orders/fulfillment-branches'),
  /** #6 (ST09): không giữ hàng. 400 BR-BH-08 giỏ rỗng / thiếu hàng / địa chỉ không phải của khách; 404 địa chỉ / chi nhánh. */
  quote: (query: QuoteQuery) => get<OnlineOrderQuote>('/api/me/online-orders/quote', query),
  /** #7: giữ hàng, chốt phí, dọn giỏ. 400 BR-BH-08; 201. Bước tiếp: paymentApi.pay. */
  placeOrder: (body: PlaceOnlineOrderRequest) => post<OnlineOrder>('/api/me/online-orders', body),
  /** #8: mới nhất trước. */
  listMyOrders: (query: OnlineOrderQuery = {}) => get<PageResponse<OnlineOrderSummary>>('/api/me/online-orders', query),
  /** #9: đơn của người khác = 404. */
  getMyOrder: (onlineOrderId: number) => get<OnlineOrder>(`/api/me/online-orders/${onlineOrderId}`),
  /** #10: PENDING_PAYMENT / PAID / PROCESSING → CANCELLED; 409 từ trạng thái khác (từ SHIPPED khách không tự hủy). */
  cancelMyOrder: (onlineOrderId: number, body: ReasonRequest) => post<OnlineOrder>(`/api/me/online-orders/${onlineOrderId}/cancel`, body),
  /** #11: DELIVERED → COMPLETED; 409 trạng thái khác. */
  confirmReceived: (onlineOrderId: number) => post<OnlineOrder>(`/api/me/online-orders/${onlineOrderId}/confirm-received`),

  /** #12: chi nhánh của người gọi; `status` vắng = PAID + PROCESSING + DELIVERY_FAILED; cũ nhất trước. */
  listOrders: (query: OnlineOrderQuery = {}) => get<PageResponse<OnlineOrderSummary>>('/api/online-orders', query),
  /** #13 */
  getOrder: (onlineOrderId: number) => get<OnlineOrder>(`/api/online-orders/${onlineOrderId}`),
  /** #14: PAID → PROCESSING. */
  startProcessing: (onlineOrderId: number) => post<OnlineOrder>(`/api/online-orders/${onlineOrderId}/start-processing`),
  /** #15: PROCESSING → SHIPPED. 400 BR-BH-11 thiếu đơn vị vận chuyển / mã vận đơn. */
  ship: (onlineOrderId: number, body: ShipRequest) => post<OnlineOrder>(`/api/online-orders/${onlineOrderId}/ship`, body),
  /** #16: SHIPPED → DELIVERED (bắt đầu đếm hạn trả hàng 7 ngày). */
  markDelivered: (onlineOrderId: number) => post<OnlineOrder>(`/api/online-orders/${onlineOrderId}/mark-delivered`),
  /** #17: SHIPPED → DELIVERY_FAILED. 400 BR-BH-11 thiếu lý do. */
  failDelivery: (onlineOrderId: number, body: ReasonRequest) => post<OnlineOrder>(`/api/online-orders/${onlineOrderId}/fail-delivery`, body),
  /** #18: DELIVERY_FAILED → SHIPPED. 400 BR-BH-11 quá số lần giao lại tối đa [CFG] 2 / thiếu mã vận đơn. */
  redeliver: (onlineOrderId: number, body: ShipRequest) => post<OnlineOrder>(`/api/online-orders/${onlineOrderId}/redeliver`, body),
  /** #19: DELIVERY_FAILED → CANCELLED, hoàn kho và sinh hoàn tiền qua cổng. 400 BR-BH-11 thiếu lý do. */
  cancelFailed: (onlineOrderId: number, body: ReasonRequest) => post<OnlineOrder>(`/api/online-orders/${onlineOrderId}/cancel-failed`, body),
};
