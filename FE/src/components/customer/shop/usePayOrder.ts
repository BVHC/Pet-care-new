// Bắt đầu thanh toán một đơn PENDING_PAYMENT (payment-v1 #1) rồi chuyển khách sang cổng. Dùng chung cho Checkout và chi tiết đơn ("Thanh toán lại").
import { useNavigate } from 'react-router-dom';
import { paymentApi } from '@/shared/api/payment.api';
import { usePaymentMutation } from '@/shared/hooks/usePayment';

// shortcut: `redirectUrl` của cổng giả lập chỉ mang `code` và `amount`, không có mã giao dịch; nhớ tạm code -> transactionId ở sessionStorage để trang cổng chuyển về
// `/payment?transactionId=`. Cổng thật (VNPay) tự quay về đúng URL do BE cấu hình nên bỏ phần này khi nối cổng thật (T65).
const KEY = 'petcare.mock-gateway.';

export function rememberMockTransaction(code: string, transactionId: number) {
  try {
    sessionStorage.setItem(KEY + code, String(transactionId));
  } catch {
    // Không ghi được (chế độ riêng tư): trang cổng sẽ đưa khách về danh sách đơn.
  }
}

export function recallMockTransaction(code: string): number | null {
  try {
    const raw = sessionStorage.getItem(KEY + code);
    return raw && /^[1-9]\d*$/.test(raw) ? Number(raw) : null;
  } catch {
    return null;
  }
}

/** `pay(id)` ném `ApiError` khi BE từ chối (409 đã có giao dịch chờ, 400 BR-TG-06 quá hạn…); nơi gọi hiện lỗi ngay chỗ bấm. */
export function usePayOrder() {
  const navigate = useNavigate();
  const mutation = usePaymentMutation(paymentApi.pay);
  const pay = async (onlineOrderId: number): Promise<void> => {
    const session = await mutation.mutateAsync(onlineOrderId);
    rememberMockTransaction(session.code, session.transactionId);
    // Đường dẫn nội bộ (cổng giả lập) đi bằng router; địa chỉ ngoài (cổng thật) rời hẳn ứng dụng.
    if (session.redirectUrl.startsWith('/')) navigate(session.redirectUrl);
    else window.location.assign(session.redirectUrl);
  };
  return { pay, isPending: mutation.isPending };
}
