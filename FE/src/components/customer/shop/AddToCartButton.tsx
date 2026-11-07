// "Thêm vào giỏ" (UC61, shop-v1 #2): giỏ nằm trên BE nên khách vãng lai được chuyển sang đăng nhập, nhân viên không thấy nút.
import { ShoppingCart } from 'lucide-react';
import { useLocation, useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { shopApi } from '@/shared/api/shop.api';
import { ROUTES } from '@/shared/constants/routes';
import { useShopMutation } from '@/shared/hooks/useShop';
import { useAccount } from '@/shared/stores/session.store';
import { SHOP_LIMITS } from '@/shared/types/shop';

/** `PUT` đặt số lượng chứ không cộng dồn, nên đọc giỏ hiện tại rồi đặt `hiện có + thêm` (tối đa 99). Trả null khi dòng đã đầy. */
async function addToCart({ productId, quantity }: { productId: number; quantity: number }) {
  const cart = await shopApi.getCart();
  const current = cart.items.find((i) => i.productId === productId)?.quantity ?? 0;
  if (current >= SHOP_LIMITS.maxQuantity) return null;
  return shopApi.setCartItem(productId, { quantity: Math.min(SHOP_LIMITS.maxQuantity, current + quantity) });
}

export function AddToCartButton({ productId, quantity = 1, className }: { productId: number; quantity?: number; className?: string }) {
  const account = useAccount();
  const navigate = useNavigate();
  const location = useLocation();
  const add = useShopMutation(addToCart);

  // Chức năng ngoài vai trò thì ẩn: chỉ khách hàng (hoặc người chưa đăng nhập) mua online.
  if (account && account.role !== 'CUSTOMER') return null;

  const onClick = () => {
    if (!account) {
      navigate(ROUTES.login, { state: { from: location.pathname + location.search } });
      return;
    }
    add.mutate(
      { productId, quantity },
      {
        onSuccess: (cart) => {
          if (!cart) return void toast.info(`Giỏ hàng đã có tối đa ${SHOP_LIMITS.maxQuantity} sản phẩm này`);
          toast.success('Đã thêm vào giỏ hàng', { action: { label: 'Xem giỏ hàng', onClick: () => navigate(ROUTES.cart) } });
        },
        onError: (e) => toast.error(plainMessage(toApiError(e))),
      },
    );
  };

  return (
    <button
      type="button"
      onClick={onClick}
      disabled={add.isPending}
      className={
        className ??
        'inline-flex min-h-9 w-full items-center justify-center gap-1.5 rounded-full bg-accent px-3 text-[12.5px] font-bold text-white transition-colors hover:bg-(--color-accent-hover) focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--color-accent) disabled:opacity-60'
      }
    >
      <ShoppingCart size={14} aria-hidden />
      {add.isPending ? 'Đang thêm…' : 'Thêm vào giỏ'}
    </button>
  );
}
