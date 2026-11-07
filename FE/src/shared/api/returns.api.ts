// Trả hàng và hoàn tiền (tầng 3) — docs/api/returns-v1.md #1–9 (UC68, UC69, ST21). Lễ tân (A06) tạo / rút / xác nhận hoàn tiền tại quầy;
// quản lý chi nhánh (A05) duyệt / từ chối / thử lại; cả hai xem danh sách của chi nhánh mình. Khách không có endpoint trả hàng.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type {
  ApproveReturnRequestRequest,
  ConfirmRefundRequest,
  CreateReturnRequestRequest,
  Refund,
  RefundQuery,
  RejectReturnRequestRequest,
  ReturnRequest,
  ReturnRequestQuery,
  ReturnRequestSummary,
} from '../types/returns';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;

export const returnsApi = {
  /** #1: 201. `orderId` = `orders.id` (đơn online: `salesOrderId`). 400 BR-BH-13; 404 Order / dòng. */
  create: (body: CreateReturnRequestRequest) => post<ReturnRequest>('/api/return-requests', body),
  /** #2: chi nhánh của người gọi; `status` vắng = PENDING; cũ nhất trước. */
  list: (query: ReturnRequestQuery = {}) => get<PageResponse<ReturnRequestSummary>>('/api/return-requests', query),
  /** #3 */
  get: (requestId: number) => get<ReturnRequest>(`/api/return-requests/${requestId}`),
  /** #4: PENDING → CANCELLED, chỉ người tạo; 409 trạng thái khác. */
  cancel: (requestId: number) => post<ReturnRequest>(`/api/return-requests/${requestId}/cancel`),
  /** #5: PENDING → APPROVED, nhập lại lô gốc hoặc loại bỏ, sinh hoàn tiền. 409 trạng thái khác. */
  approve: (requestId: number, body: ApproveReturnRequestRequest) => post<ReturnRequest>(`/api/return-requests/${requestId}/approve`, body),
  /** #6: PENDING → REJECTED. 400 BR-BH-14 thiếu lý do; 409 trạng thái khác. */
  reject: (requestId: number, body: RejectReturnRequestRequest) => post<ReturnRequest>(`/api/return-requests/${requestId}/reject`, body),
  /** #7: chi nhánh của người gọi; `status` vắng = REQUESTED + FAILED; cũ nhất trước. */
  listRefunds: (query: RefundQuery = {}) => get<PageResponse<Refund>>('/api/refunds', query),
  /** #8: REQUESTED → SUCCEEDED. 400 BR-TG-01 chưa có ca OPEN; hoàn tiền GATEWAY không xác nhận tay; 409 trạng thái khác. */
  confirmRefund: (refundId: number, body: ConfirmRefundRequest = {}) => post<Refund>(`/api/refunds/${refundId}/confirm`, body),
  /** #9: FAILED → REQUESTED, chỉ hoàn tiền GATEWAY (400 nếu hình thức khác); 409 trạng thái khác. */
  retryRefund: (refundId: number) => post<Refund>(`/api/refunds/${refundId}/retry`),
};
