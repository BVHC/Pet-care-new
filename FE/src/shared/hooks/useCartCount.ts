// Số lượng trên huy hiệu giỏ hàng ở header (UC61): tổng số lượng các dòng của `GET /me/cart`. Chỉ khách đã đăng nhập mới có giỏ trên BE;
// khách vãng lai gọi sẽ bị 401, nhân viên bị 403, nên các trường hợp đó không gọi API và trả 0. Cùng khóa với `useCart` nên dùng chung bộ nhớ đệm.
import { useQuery } from '@tanstack/react-query';
import { shopApi } from '../api/shop.api';
import { useAccount } from '../stores/session.store';
import { commerceQueryOptions } from './useShop';

export function useCartCount(): number {
  const isCustomer = useAccount()?.role === 'CUSTOMER';
  const cart = useQuery({ queryKey: ['shop', 'cart'], queryFn: shopApi.getCart, enabled: isCustomer, ...commerceQueryOptions });
  return isCustomer ? (cart.data?.items.reduce((sum, item) => sum + item.quantity, 0) ?? 0) : 0;
}
