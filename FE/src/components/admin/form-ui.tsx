import { useId, type ReactNode } from 'react';
import { StatusBadge } from '../ui/modal-templates';
import { cn } from '../../lib/utils';

// Phần form và bảng dùng chung cho các màn quản trị (danh mục T21, chi nhánh T20).

export const selectClass =
  'w-full h-10 px-3 rounded-lg border border-(--color-border-default) bg-white text-sm focus:outline-none focus:ring-2 focus:ring-accent disabled:bg-(--bg-tertiary) disabled:opacity-70';

/** Nhãn gắn `htmlFor` (không bọc ô nhập: `<select>` bọc trong label sẽ dính chữ của option vào tên nhãn). */
export function Field({
  label,
  hint,
  error,
  children,
}: {
  label: string;
  hint?: string;
  /** Lỗi của riêng ô này (vd lỗi validation BE theo trường). */
  error?: string;
  children: (id: string) => ReactNode;
}) {
  const id = useId();
  return (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-(--text-primary)">
        {label}
      </label>
      {children(id)}
      {error && <p className="mt-1 text-xs font-medium text-red-600">{error}</p>}
      {hint && <p className="mt-1 text-xs text-(--text-tertiary)">{hint}</p>}
    </div>
  );
}

export function Checkbox({
  label,
  checked,
  disabled,
  onChange,
}: {
  label: string;
  checked: boolean;
  disabled?: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label className={cn('flex items-center gap-2 text-sm text-(--text-primary)', disabled && 'opacity-70')}>
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
        className="h-4 w-4 accent-[#a43324]"
      />
      {label}
    </label>
  );
}

export function FormError({ message }: { message: string }) {
  if (!message) return null;
  return (
    <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm font-medium text-red-700">
      {message}
    </p>
  );
}

export interface Notice {
  kind: 'success' | 'error';
  text: string;
}

/** Kết quả thao tác trên bảng (ẩn, xóa…) hiện ngay trên tab, không chỉ trong toast. */
export function NoticeBar({ notice }: { notice: Notice | null }) {
  if (!notice) return null;
  return (
    <p
      role={notice.kind === 'error' ? 'alert' : 'status'}
      className={cn(
        'rounded-lg px-4 py-2.5 text-sm font-medium',
        notice.kind === 'error' ? 'bg-red-50 text-red-700' : 'bg-green-50 text-green-700',
      )}
    >
      {notice.text}
    </p>
  );
}

export const ActiveBadge = ({ active }: { active: boolean }) => (
  <StatusBadge status={active ? 'Đang dùng' : 'Ngừng'} variant={active ? 'success' : 'neutral'} />
);

export function EmptyRow({ colSpan, loading }: { colSpan: number; loading: boolean }) {
  return (
    <tr>
      <td colSpan={colSpan} className="py-8 text-center text-(--text-tertiary)">
        {loading ? 'Đang tải…' : 'Chưa có dữ liệu.'}
      </td>
    </tr>
  );
}
