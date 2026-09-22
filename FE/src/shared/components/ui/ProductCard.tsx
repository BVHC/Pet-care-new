import { Link } from 'react-router-dom';
import { Image as ImageIcon, ShoppingCart, Star } from 'lucide-react';

export interface ProductCardProps {
  id: string | number;
  image?: string;
  name: string;
  category: string;
  price: string;
  rating?: string;
  badge?: string;
  onAddToCart?: () => void;
}

export function ProductCard({ id, image, name, category, price, rating, badge, onAddToCart }: ProductCardProps) {
  return (
    <div className="block w-full overflow-hidden rounded-(--radius-rounded) bg-(--color-surface-card) font-professional shadow-(--shadow-1)">
      <Link to={`/shop/${id}`} className="block">
        <div className="relative aspect-4/3 bg-(--color-surface-sunken)">
          {image ? (
            <img src={image} alt={name} className="h-full w-full object-cover" />
          ) : (
            <div className="flex h-full w-full items-center justify-center">
              <ImageIcon size={28} className="opacity-35" />
            </div>
          )}
          {badge && (
            <span className="absolute top-2 left-2 rounded-(--radius-pill) bg-(--color-brand-secondary) px-2 py-0.5 text-[11px] font-bold text-white">
              {badge}
            </span>
          )}
        </div>
        <div className="p-3">
          <div className="text-[11px] tracking-wide text-(--color-text-secondary) uppercase">{category}</div>
          <div className="my-0.5 mb-1.5 text-[15px] font-bold text-(--color-text-primary)">{name}</div>
          <div className="flex items-center justify-between">
            <span className="text-base font-bold text-(--color-text-primary)">{price}</span>
            {rating && (
              <span className="flex items-center gap-1 text-sm text-(--color-text-secondary)">
                <Star size={13} fill="var(--color-brand-secondary)" color="var(--color-brand-secondary)" />
                {rating}
              </span>
            )}
          </div>
        </div>
      </Link>
      <div className="px-3 pb-3">
        <button
          onClick={(e) => {
            e.stopPropagation();
            onAddToCart?.();
          }}
          className="mt-2.5 flex w-full cursor-pointer items-center justify-center gap-1.5 rounded-(--radius-rounded) border border-(--color-brand-primary) bg-(--color-brand-primary) px-3 py-2 text-[13px] font-semibold text-(--color-text-on-brand) transition-colors hover:bg-(--color-brand-primary-hover) active:bg-(--color-brand-primary-active)"
        >
          <ShoppingCart size={14} />
          Thêm vào giỏ
        </button>
      </div>
    </div>
  );
}
