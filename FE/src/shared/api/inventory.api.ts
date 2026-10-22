// Kho (UC73–76) theo docs/api/inventory-v1.md #1–17. Ghi: BRANCH_MANAGER; xem tồn: A05–A08; lô: A05, A06.
// Chi nhánh lấy từ người gọi (không có tham số branchId); nhà cung cấp dùng chung toàn chuỗi.
import { apiClient } from './axios';
import type { PageResponse } from '../types';
import type {
  AdjustmentQuery,
  CancelReceiptRequest,
  InventoryItem,
  InventoryQuery,
  ReceiptQuery,
  StockAdjustment,
  StockAdjustmentRequest,
  StockLot,
  StockReceipt,
  StockReceiptRequest,
  StockReceiptSummary,
  Supplier,
  SupplierQuery,
  SupplierRequest,
  UpdateSupplierRequest,
} from '../types/inventory';

const get = async <T>(url: string, params?: object): Promise<T> => (await apiClient.get(url, { params })).data.data;
const post = async <T>(url: string, body?: object): Promise<T> => (await apiClient.post(url, body)).data.data;
const put = async <T>(url: string, body: object): Promise<T> => (await apiClient.put(url, body)).data.data;
const patch = async <T>(url: string, body: object): Promise<T> => (await apiClient.patch(url, body)).data.data;

export const inventoryApi = {
  // ─── Nhà cung cấp (UC73) ───
  listSuppliers: (query: SupplierQuery = {}) => get<Supplier[]>('/api/suppliers', query),
  createSupplier: (body: SupplierRequest) => post<Supplier>('/api/suppliers', body),
  updateSupplier: (supplierId: number, body: UpdateSupplierRequest) => patch<Supplier>(`/api/suppliers/${supplierId}`, body),
  deleteSupplier: async (supplierId: number): Promise<void> => {
    await apiClient.delete(`/api/suppliers/${supplierId}`);
  },

  // ─── Phiếu nhập kho (UC74) ───
  listReceipts: (query: ReceiptQuery) => get<PageResponse<StockReceiptSummary>>('/api/stock-receipts', query),
  createReceipt: (body: StockReceiptRequest) => post<StockReceipt>('/api/stock-receipts', body),
  getReceipt: (receiptId: number) => get<StockReceipt>(`/api/stock-receipts/${receiptId}`),
  updateReceipt: (receiptId: number, body: StockReceiptRequest) => put<StockReceipt>(`/api/stock-receipts/${receiptId}`, body),
  confirmReceipt: (receiptId: number) => post<StockReceipt>(`/api/stock-receipts/${receiptId}/confirm`),
  /** YAML: body bắt buộc dù mọi trường tùy chọn, nên luôn gửi object. */
  cancelReceipt: (receiptId: number, body: CancelReceiptRequest = {}) =>
    post<StockReceipt>(`/api/stock-receipts/${receiptId}/cancel`, body),

  // ─── Tồn kho (UC75) ───
  listInventory: (query: InventoryQuery) => get<PageResponse<InventoryItem>>('/api/inventory', query),
  getInventoryItem: (productId: number) => get<InventoryItem>(`/api/inventory/${productId}`),
  listLots: (productId: number) => get<StockLot[]>(`/api/inventory/${productId}/lots`),
  setMinQuantity: (productId: number, minQuantity: number) =>
    put<InventoryItem>(`/api/inventory/${productId}/min-quantity`, { minQuantity }),

  // ─── Điều chỉnh tồn (UC76) ───
  createAdjustment: (body: StockAdjustmentRequest) => post<StockAdjustment>('/api/stock-adjustments', body),
  listAdjustments: (query: AdjustmentQuery) => get<PageResponse<StockAdjustment>>('/api/stock-adjustments', query),
  getAdjustment: (adjustmentId: number) => get<StockAdjustment>(`/api/stock-adjustments/${adjustmentId}`),
};
