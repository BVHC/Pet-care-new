// Chuyển kho, kiểm kê, truy vết lô (UC77–79) theo docs/api/stockops-v1.md #1–13. Chuyển kho và kiểm kê: BRANCH_MANAGER (nguồn tạo, đích nhận,
// cả hai hủy); truy vết lô: BRANCH_MANAGER và VET (VET chỉ lô thuốc, vaccine). Chi nhánh lấy từ người gọi, không có tham số `branchId`.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type {
  CreateStockTransferRequest,
  LotSearchQuery,
  LotSummary,
  LotTrace,
  OpenStockCountRequest,
  ReasonRequest,
  SaveStockCountLinesRequest,
  StockCount,
  StockCountQuery,
  StockCountSummary,
  StockTransfer,
  StockTransferQuery,
  StockTransferSummary,
} from '../types/stockops';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;
const put = async <T>(url: string, body: object): Promise<T> => (await apiClient.put(url, body)).data.data;

export const stockopsApi = {
  // ─── Chuyển kho (UC77) ───
  /** #1: 400 BR-KO-08 (đích trùng nguồn / không ACTIVE, trùng sản phẩm, vượt tồn khả dụng); 404 chi nhánh đích hoặc sản phẩm. */
  createTransfer: (body: CreateStockTransferRequest) => post<StockTransfer>('/api/stock-transfers', body),
  /** #2: mới nhất trước; `direction` vắng = cả hai chiều. */
  listTransfers: (query: StockTransferQuery = {}) => get<PageResponse<StockTransferSummary>>('/api/stock-transfers', query),
  /** #3 */
  getTransfer: (transferId: number) => get<StockTransfer>(`/api/stock-transfers/${transferId}`),
  /** #4: chỉ chi nhánh đích; 409 khi phiếu không còn IN_TRANSIT. */
  receiveTransfer: (transferId: number) => post<StockTransfer>(`/api/stock-transfers/${transferId}/receive`),
  /** #5: nguồn hoặc đích; 400 BR-KO-10 thiếu lý do; 409 khi không còn IN_TRANSIT. YAML: body bắt buộc. */
  cancelTransfer: (transferId: number, body: ReasonRequest) => post<StockTransfer>(`/api/stock-transfers/${transferId}/cancel`, body),

  // ─── Kiểm kê (UC78) ───
  /** #6: 400 BR-KO-12 thiếu `categoryId` khi scope = CATEGORY; 409 khi chi nhánh đã có phiên OPEN. */
  openCount: (body: OpenStockCountRequest) => post<StockCount>('/api/stock-counts', body),
  /** #7: mới nhất trước. */
  listCounts: (query: StockCountQuery = {}) => get<PageResponse<StockCountSummary>>('/api/stock-counts', query),
  /** #8 */
  getCount: (countId: number) => get<StockCount>(`/api/stock-counts/${countId}`),
  /** #9: ghi đè số đếm của các lô gửi lên, lô không gửi giữ nguyên; 400 lô ngoài phạm vi phiên; 409 khi phiên không còn OPEN. */
  saveCountLines: (countId: number, body: SaveStockCountLinesRequest) => put<StockCount>(`/api/stock-counts/${countId}/lines`, body),
  /** #10: tạo điều chỉnh COUNT_DIFF cho lô lệch; 400 BR-KO-12 khi chưa nhập số đếm lô nào; 409 khi không còn OPEN. */
  confirmCount: (countId: number) => post<StockCount>(`/api/stock-counts/${countId}/confirm`),
  /** #11: 400 BR-KO-12 thiếu lý do; 409 khi không còn OPEN. YAML: body bắt buộc. */
  cancelCount: (countId: number, body: ReasonRequest) => post<StockCount>(`/api/stock-counts/${countId}/cancel`, body),

  // ─── Truy vết lô (UC79) ───
  /** #12: phải có `lotNumber` hoặc `productId`, mới nhập trước; lô đã hết hàng vẫn tìm được. */
  searchLots: (query: LotSearchQuery) => get<PageResponse<LotSummary>>('/api/stock-lots', query),
  /** #13: sự kiện cũ nhất trước; tên thú / khách chỉ có với lô thuốc, vaccine. */
  traceLot: (lotId: number) => get<LotTrace>(`/api/stock-lots/${lotId}/trace`),
};
