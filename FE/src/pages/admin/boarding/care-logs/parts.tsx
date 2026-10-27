// Phần giao diện nhỏ dùng chung trong hộp thoại Nhật ký chăm sóc (cùng phong cách Callout / StayModal của stay/, nhưng không import từ WS khác).
import { CircleCheck, Info, TriangleAlert } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/utils';

export const textareaClass =
  'w-full rounded-lg border border-(--color-border-default) px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent';

/** Hàng nút cuối biểu mẫu: xếp dọc trên điện thoại (mục tiêu chạm ≥ 40 px), ngang từ sm. */
export const footerClass = 'flex flex-col-reverse gap-2 sm:flex-row sm:justify-end';

const TONES = {
  warning: { box: 'border-amber-200 bg-amber-50 text-amber-900', Icon: TriangleAlert },
  info: { box: 'border-blue-200 bg-blue-50 text-blue-900', Icon: Info },
  success: { box: 'border-green-200 bg-green-50 text-green-800', Icon: CircleCheck },
} as const;

/** Dải thông tin: luôn có biểu tượng + chữ (không chỉ màu). */
export function Notice({ tone, title, children }: { tone: keyof typeof TONES; title: string; children?: ReactNode }) {
  const { box, Icon } = TONES[tone];
  return (
    <div role="status" className={cn('flex gap-2 rounded-lg border px-3 py-2.5 text-sm', box)}>
      <Icon aria-hidden className="mt-0.5 size-4 shrink-0" />
      <div className="min-w-0">
        <p className="font-semibold break-words">{title}</p>
        {children && <p className="mt-0.5 break-words">{children}</p>}
      </div>
    </div>
  );
}
