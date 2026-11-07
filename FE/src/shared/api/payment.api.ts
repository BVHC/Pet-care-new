// Thanh toán online (tầng 3) — docs/api/payment-v1.md #1–6 (UC63, ST11, ST14). Cổng thanh toán ở FE là GIẢ LẬP (mock); #3 là endpoint
// công khai (cổng gọi vào, kiểm chữ ký) nên chỉ trang cổng giả lập mới gọi. Đối soát (#4–#6) chỉ SUPER_MANAGER.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type {
  CallbackAck,
  GatewayCallbackRequest,
  PaymentSession,
  PaymentTransaction,
  ReconciliationItem,
  ReconciliationItemQuery,
  ReconciliationQuery,
  ReconciliationSummary,
  ResolveItemRequest,
} from '../types/payment';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;

export const paymentApi = {
  /** #1: 201 + `redirectUrl`. 400 BR-TG-06 đơn quá hạn thanh toán; 409 đơn không ở PENDING_PAYMENT / đã có giao dịch PENDING. */
  pay: (onlineOrderId: number) => post<PaymentSession>(`/api/me/online-orders/${onlineOrderId}/pay`),
  /** #2: trang kết quả sau khi quay lại từ cổng hỏi endpoint này, không tin tham số trên URL quay về (A3). Giao dịch của người khác = 404. */
  getMyTransaction: (transactionId: number) => get<PaymentTransaction>(`/api/me/payment-transactions/${transactionId}`),
  /** #3: công khai (token bỏ qua). 400 BR-TG-07 chữ ký sai / mã tham chiếu không có; idempotent theo mã tham chiếu. */
  gatewayCallback: (body: GatewayCallbackRequest) => post<CallbackAck>('/api/public/payment-gateway/callback', body),
  /** #4: mới nhất trước. */
  listReconciliations: (query: ReconciliationQuery = {}) => get<PageResponse<ReconciliationSummary>>('/api/payment-reconciliations', query),
  /** #5 */
  listReconciliationItems: (reconciliationId: number, query: ReconciliationItemQuery = {}) =>
    get<PageResponse<ReconciliationItem>>(`/api/payment-reconciliations/${reconciliationId}/items`, query),
  /** #6: chỉ ghi chú, không sửa giao dịch hay đơn. 409 mục đã xử lý. */
  resolveItem: (itemId: number, body: ResolveItemRequest) => post<ReconciliationItem>(`/api/payment-reconciliation-items/${itemId}/resolve`, body),
};
