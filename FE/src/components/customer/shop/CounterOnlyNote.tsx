// Thay nút "Thêm vào giỏ" khi `PublicProduct.purchasableOnline === false` (thuốc, vaccine: giỏ online chỉ nhận hàng thường, BR-BH-07).
// Là thông tin chứ không phải thao tác nên mọi vai trò đều thấy; hết hàng thì chỗ gọi không hiện (đã có "Tạm hết hàng").
import { Store } from 'lucide-react';

export function CounterOnlyNote({ className }: { className?: string }) {
  return (
    <p className={className ?? 'inline-flex items-center gap-1.5 text-[12.5px] font-semibold text-(--color-text-secondary)'}>
      <Store size={14} aria-hidden />
      Mua trực tiếp tại quầy
    </p>
  );
}
