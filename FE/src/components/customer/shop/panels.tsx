import { RotateCw, WifiOff } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { BTN_PRIMARY, CARD } from './styles';

/** Lỗi tải dữ liệu: hiện đúng `ApiError.message` của BE (hoặc mock), có nút thử lại. */
export function ErrorPanel({ title, error, onRetry, retrying }: { title: string; error: unknown; onRetry?: () => void; retrying?: boolean }) {
  return (
    <div role="alert" className={`${CARD} mx-auto max-w-[560px] px-6 py-12 text-center`}>
      <span className="mx-auto grid h-12 w-12 place-items-center rounded-full bg-(--color-accent-soft) text-accent">
        <WifiOff size={22} aria-hidden />
      </span>
      <p className="mt-4 font-friendly text-[20px] font-extrabold">{title}</p>
      <p className="mt-2 text-[14px] text-(--color-text-secondary)">{plainMessage(toApiError(error))}</p>
      {onRetry && (
        <button type="button" onClick={onRetry} disabled={retrying} className={`${BTN_PRIMARY} mt-6`}>
          <RotateCw size={15} aria-hidden className={retrying ? 'animate-spin motion-reduce:animate-none' : ''} />
          Thử lại
        </button>
      )}
    </div>
  );
}

/** Khung trống / không tìm thấy, có một nút dẫn đi tiếp. */
export function EmptyPanel({ icon, title, children, action }: { icon: ReactNode; title: string; children?: ReactNode; action?: { to: string; label: string } }) {
  return (
    <div className={`${CARD} mx-auto max-w-[560px] px-6 py-14 text-center`}>
      <span className="mx-auto grid h-14 w-14 place-items-center rounded-full bg-(--color-accent-soft) text-accent">{icon}</span>
      <h2 className="mt-4 font-friendly text-[22px] font-extrabold">{title}</h2>
      {children && <div className="mt-2 text-[14px] text-(--color-text-secondary)">{children}</div>}
      {action && (
        <Link to={action.to} className={`${BTN_PRIMARY} mt-6`}>
          {action.label}
        </Link>
      )}
    </div>
  );
}

/** Khung chờ có trạng thái đọc được. */
export function LoadingBlock({ label }: { label: string }) {
  return (
    <div role="status" aria-label={label} className="space-y-3">
      {[0, 1, 2].map((i) => (
        <div key={i} aria-hidden className="h-24 animate-pulse rounded-2xl bg-(--color-surface-2) motion-reduce:animate-none" />
      ))}
    </div>
  );
}

/** Lỗi thao tác (409 / 400 BR) hiện ngay chỗ bấm, ngoài toast. */
export function InlineError({ message }: { message: string }) {
  if (!message) return null;
  return (
    <p role="alert" className="rounded-xl bg-red-50 px-3 py-2 text-[13px] font-medium text-red-700">
      {message}
    </p>
  );
}
