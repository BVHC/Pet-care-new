// React Query cho Order tại quầy (sales-v1 #1–8, #10): khóa gốc ['sales']. Lệnh ghi đặt ngay Order trả về vào cache
// rồi làm mới cả gốc (danh sách, Order đang mở của khách…); lỗi cũng làm mới vì 409 nghĩa là Order đã đổi ở nơi khác.
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { plainMessage, toApiError } from '../api/api-error';
import { salesApi } from '../api/sales.api';
import type { Order, OrderQuery } from '../types/sales';

export const useOrders = (query: OrderQuery, enabled = true) =>
  useQuery({ queryKey: ['sales', 'orders', query], queryFn: () => salesApi.listOrders(query), placeholderData: keepPreviousData, enabled });

export const useOrder = (orderId: number | null) =>
  useQuery({ queryKey: ['sales', 'order', orderId], queryFn: () => salesApi.getOrder(orderId!), enabled: orderId != null });

const orderOf = (result: unknown): Order | null => {
  const r = result as { order?: Order; orderId?: number } | null;
  if (r?.order) return r.order;
  return r?.orderId != null ? (r as Order) : null;
};

export function useSalesMutation<A, R>(fn: (args: A) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: (result) => {
      const order = orderOf(result);
      if (order) queryClient.setQueryData(['sales', 'order', order.orderId], order);
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['sales'] }),
  });
}

/** Câu báo lỗi cho quầy: rule hay gặp viết lại dễ hiểu; 409 = Order đã đổi trạng thái ở nơi khác. */
export function salesErrorMessage(e: unknown): string {
  const error = toApiError(e);
  if (error.errorCode === 'INVALID_STATE_TRANSITION') {
    return 'Order vừa được chốt, hủy hoặc thu tiền ở nơi khác nên không làm được thao tác này. Đã tải lại trạng thái mới.';
  }
  switch (error.ruleId) {
    case 'BR-SP-01':
      return 'Thuốc kê đơn không bán lẻ tại quầy, chỉ bán theo đơn của bác sĩ.';
    case 'BR-KB-04':
      return 'Vaccine chỉ vào Order khi bác sĩ ghi nhận mũi tiêm, không bán lẻ.';
    case 'BR-BH-01':
      return 'Order chưa có dòng nào nên chưa chốt được. Thêm hàng trước, hoặc hủy Order nếu khách không mua.';
    default:
      return plainMessage(error);
  }
}
