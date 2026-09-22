import type { ButtonHTMLAttributes } from 'react';

type Variant = 'primary' | 'secondary' | 'tertiary';
type Size = 'small' | 'medium' | 'large';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  fullWidth?: boolean;
}

const SIZE_CLASSES: Record<Size, string> = {
  small: 'px-3.5 py-2 text-sm',
  medium: 'px-5 py-3 text-base',
  large: 'px-7 py-3.5 text-base',
};

const VARIANT_CLASSES: Record<Variant, string> = {
  primary:
    'bg-(--color-brand-primary) text-(--color-text-on-brand) border-(--color-brand-primary) hover:bg-(--color-brand-primary-hover) active:bg-(--color-brand-primary-active)',
  secondary:
    'bg-(--color-surface-card) text-(--color-text-primary) border-(--color-border-strong) hover:bg-(--color-surface-sunken)',
  tertiary:
    'bg-transparent text-(--color-teal) border-transparent hover:underline px-2',
};

export function Button({
  variant = 'primary',
  size = 'medium',
  fullWidth = false,
  disabled,
  className = '',
  children,
  ...rest
}: ButtonProps) {
  return (
    <button
      disabled={disabled}
      className={[
        'inline-flex items-center justify-center gap-2 rounded-(--radius-rounded) border font-semibold font-professional transition-colors',
        SIZE_CLASSES[size],
        VARIANT_CLASSES[variant],
        fullWidth ? 'w-full' : '',
        disabled ? 'cursor-not-allowed opacity-40' : 'cursor-pointer',
        className,
      ].join(' ')}
      {...rest}
    >
      {children}
    </button>
  );
}
