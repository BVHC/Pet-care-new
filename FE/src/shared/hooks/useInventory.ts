// React Query cho kho (UC73–76): khóa gốc ['inventory']; lệnh ghi làm mới cả gốc (như useCatalog.ts),
// vì xác nhận / hủy phiếu nhập và điều chỉnh đều đổi tồn, lô và danh sách phiếu cùng lúc.
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { inventoryApi } from '../api/inventory.api';
import type { AdjustmentQuery, InventoryQuery, ReceiptQuery, SupplierQuery } from '../types/inventory';

export const useSuppliers = (query: SupplierQuery = {}) =>
  useQuery({ queryKey: ['inventory', 'suppliers', query], queryFn: () => inventoryApi.listSuppliers(query) });

export const useReceipts = (query: ReceiptQuery) =>
  useQuery({ queryKey: ['inventory', 'receipts', query], queryFn: () => inventoryApi.listReceipts(query), placeholderData: keepPreviousData });

export const useReceipt = (receiptId: number | null) =>
  useQuery({ queryKey: ['inventory', 'receipt', receiptId], queryFn: () => inventoryApi.getReceipt(receiptId!), enabled: receiptId != null });

export const useInventoryItems = (query: InventoryQuery) =>
  useQuery({ queryKey: ['inventory', 'items', query], queryFn: () => inventoryApi.listInventory(query), placeholderData: keepPreviousData });

export const useStockLots = (productId: number | null) =>
  useQuery({ queryKey: ['inventory', 'lots', productId], queryFn: () => inventoryApi.listLots(productId!), enabled: productId != null });

export const useAdjustments = (query: AdjustmentQuery) =>
  useQuery({
    queryKey: ['inventory', 'adjustments', query],
    queryFn: () => inventoryApi.listAdjustments(query),
    placeholderData: keepPreviousData,
  });

export const useAdjustment = (adjustmentId: number | null) =>
  useQuery({
    queryKey: ['inventory', 'adjustment', adjustmentId],
    queryFn: () => inventoryApi.getAdjustment(adjustmentId!),
    enabled: adjustmentId != null,
  });

export function useInventoryMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => queryClient.invalidateQueries({ queryKey: ['inventory'] }) });
}
