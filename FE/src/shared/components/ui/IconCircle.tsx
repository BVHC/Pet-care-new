import type { ReactNode } from 'react';

interface IconCircleProps {
  icon?: ReactNode | null;
  image?: string;
  size?: 'small' | 'medium' | 'large';
  variant?: 'primary' | 'secondary' | 'brand';
  className?: string;
}

const sizeClasses = {
  small: 'w-8 h-8',
  medium: 'w-10 h-10',
  large: 'w-14 h-14',
};

const variantClasses = {
  primary: 'bg-[var(--color-surface-card)] text-[var(--color-text-primary)]',
  secondary: 'bg-[var(--color-surface-sunken)] text-[var(--color-text-secondary)]',
  brand: 'bg-[var(--color-brand-primary)] text-[var(--color-text-on-brand)]',
};

export function IconCircle({ icon, image, size = 'medium', variant = 'primary', className = '' }: IconCircleProps) {
  return (
    <div
      className={`inline-flex items-center justify-center rounded-full ${sizeClasses[size]} ${variantClasses[variant]} ${className}`}
    >
      {icon || (image && <img src={image} alt="" className="h-5 w-5 object-contain" />)}
    </div>
  );
}
