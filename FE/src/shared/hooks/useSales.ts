// React Query cho sales-v1 (Order tại quầy #1–8, #10; thu tiền và ca thu ngân #12–21): khóa gốc ['sales']. Lệnh ghi đặt ngay Order trả về vào cache
// rồi làm mới cả gốc (danh sách, Order đang mở của khách…); lỗi cũng làm mới vì 409 nghĩa là Order đã đổi ở nơi khác.
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { plainMessage, toApiError } from '../api/api-error';
import { salesApi } from '../api/sales.api';
import type { CreatePaymentRequest, Order, OrderQuery, ReconcileShiftRequest, ShiftQuery } from '../types/sales';

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

// ─── Thu tiền và ca thu ngân (T35) ───

/** #12: Order PENDING của khách tại chi nhánh của lễ tân. Chỉ RECEPTIONIST gọi được: truyền `enabled = false` cho vai trò khác (403). */
export const usePayableOrders = (customerId: number | null, enabled = true) =>
  useQuery({
    queryKey: ['sales', 'payable', customerId],
    queryFn: () => salesApi.listPayableOrders(customerId!),
    enabled: enabled && customerId != null,
  });

/** #13: chạy lại mỗi khi tập Order được chọn đổi; không thử lại 4xx (400 BR-TG-03 / 403 / 404 là kết luận). */
export const usePaymentPreview = (orderIds: number[]) => {
  const ids = [...orderIds].sort((a, b) => a - b);
  return useQuery({
    queryKey: ['sales', 'preview', ids],
    queryFn: () => salesApi.previewPayment(ids),
    enabled: ids.length > 0,
    retry: false,
    staleTime: 0,
  });
};

/** #14: thu xong Order thành PAID và kho bị trừ (FEFO) nên làm mới cả ['sales'] lẫn ['inventory']; lỗi cũng làm mới vì 409 = Order đã đổi. */
export function usePay() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreatePaymentRequest) => salesApi.createPayment(body),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['sales'] });
      queryClient.invalidateQueries({ queryKey: ['inventory'] });
    },
  });
}

export const usePayment = (paymentId: number | null) =>
  useQuery({ queryKey: ['sales', 'payment', paymentId], queryFn: () => salesApi.getPayment(paymentId!), enabled: paymentId != null });

/** #17: ca đang mở của tôi; chưa có ca thì { open: false }. Chỉ RECEPTIONIST gọi được: truyền `enabled = false` cho vai trò khác (403). */
export const useMyShift = (enabled = true) => useQuery({ queryKey: ['sales', 'my-shift'], queryFn: salesApi.getMyShift, enabled });

export const useOpenCashierShift = () => useSalesMutation((afterHours: boolean) => salesApi.openShift({ afterHours }));

export const useCloseCashierShift = () =>
  useSalesMutation((args: { shiftId: number; countedCash: number }) => salesApi.closeShift(args.shiftId, { countedCash: args.countedCash }));

/** #19 (BRANCH_MANAGER). */
export const useShifts = (query: ShiftQuery, enabled = true) =>
  useQuery({ queryKey: ['sales', 'shifts', query], queryFn: () => salesApi.listShifts(query), placeholderData: keepPreviousData, enabled });

export const useShift = (shiftId: number | null) =>
  useQuery({ queryKey: ['sales', 'shift', shiftId], queryFn: () => salesApi.getShift(shiftId!), enabled: shiftId != null });

export const useReconcileShift = () =>
  useSalesMutation((args: { shiftId: number } & ReconcileShiftRequest) =>
    salesApi.reconcileShift(args.shiftId, { countedCash: args.countedCash, note: args.note }),
  );

/** Câu báo lỗi khi thu tiền; thiếu tồn (BR-BH-04) giữ nguyên message của BE vì nó nêu sản phẩm thiếu. */
export function paymentErrorMessage(e: unknown): string {
  const error = toApiError(e);
  if (error.errorCode === 'INVALID_STATE_TRANSITION') {
    return 'Có Order không còn chờ thu (đã thu hoặc đã hủy ở nơi khác). Đã tải lại danh sách.';
  }
  switch (error.ruleId) {
    case 'BR-TG-01':
      return 'Bạn chưa mở ca thu ngân, hoặc có Order không còn ở trạng thái chờ thu. Mở ca rồi tải lại danh sách.';
    case 'BR-TG-02':
      return 'Số tiền thu phải bằng đúng tổng các Order, không thu một phần.';
    case 'BR-TG-03':
      return 'Một phiếu thu chỉ gộp Order của cùng một khách.';
    default:
      return plainMessage(error);
  }
}
