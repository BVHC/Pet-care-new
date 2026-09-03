import React from 'react';

export type BadgeVariant = 'verified' | 'success' | 'warning' | 'brand' | 'info' | 'neutral' | 'error';
export type BadgeSize = 'small' | 'medium';

interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  variant?: BadgeVariant;
  size?: BadgeSize;
  children: React.ReactNode;
  icon?: React.ReactNode;
}

const variantStyles: Record<BadgeVariant, string> = {
  verified: 'bg-[#ecfdf5] text-[#047857] border-[#a7f3d0]', // Xanh lá cây xác thực - tương phản cao
  success: 'bg-[#ecfdf5] text-[#047857] border-[#a7f3d0]',
  warning: 'bg-[#fff3e0] text-[#c2410c] border-[#ffedd5]', // Cam ấm - tương phản cao
  brand: 'bg-[#fff3e0] text-[#c2410c] border-[#ffedd5]',
  info: 'bg-[#e0f2fe] text-[#0369a1] border-[#bae6fd]', // Xanh dương
  neutral: 'bg-[#f1f5f9] text-[#334155] border-[#e2e8f0]', // Xám đen
  error: 'bg-[#ffe4e6] text-[#be123c] border-[#fecdd3]', // Đỏ
};

const sizeStyles: Record<BadgeSize, string> = {
  small: 'px-2 py-0.5 text-[11px]',
  medium: 'px-3 py-1 text-xs',
};

export const Badge: React.FC<BadgeProps> = ({
  variant = 'brand',
  size = 'medium',
  children,
  icon,
  className = '',
  ...props
}) => {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full border font-bold shadow-2xs transition-colors ${variantStyles[variant]} ${sizeStyles[size]} ${className}`}
      {...props}
    >
      {icon && <span className="shrink-0">{icon}</span>}
      <span>{children}</span>
    </span>
  );
};
