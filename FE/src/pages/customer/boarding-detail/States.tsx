// Ba trạng thái của khung trang: đang tải, lỗi (có Thử lại), không tìm thấy (404 hoặc đường dẫn sai; không lộ gì thêm).
import { Link } from 'react-router-dom';
import { ArrowLeft, PawPrint } from 'lucide-react';
import { cn } from '@/lib/utils';
import { BTN_OUTLINE, BTN_PRIMARY, CARD } from './styles';

export function LoadingState() {
  return (
    <div role="status" className="space-y-6">
      <p className="text-[13.5px] text-(--color-text-secondary)">Đang tải chi tiết lưu trú…</p>
      <div aria-hidden className="h-32 animate-pulse rounded-2xl bg-(--color-surface-2) motion-reduce:animate-none" />
      <div aria-hidden className="grid gap-6 lg:grid-cols-[380px_1fr]">
        <div className="h-72 animate-pulse rounded-2xl bg-(--color-surface-2) motion-reduce:animate-none" />
        <div className="h-72 animate-pulse rounded-2xl bg-(--color-surface-2) motion-reduce:animate-none" />
      </div>
    </div>
  );
}

export function LoadError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className={cn(CARD, 'mx-auto max-w-xl space-y-4 p-8 text-center')}>
      <p role="alert" className="rounded-xl bg-red-50 px-4 py-3 text-[13.5px] text-red-800">
        {message}
      </p>
      <button type="button" onClick={onRetry} className={BTN_OUTLINE}>
        Thử lại
      </button>
    </div>
  );
}

export function NotFoundState() {
  return (
    <div className={cn(CARD, 'mx-auto max-w-xl p-10 text-center')}>
      <PawPrint size={44} className="mx-auto mb-4 text-(--color-text-secondary) opacity-30" aria-hidden />
      <h2 className="font-(family-name:--font-friendly) text-[22px] font-bold text-(--color-text-primary)">Không tìm thấy lưu trú</h2>
      <p className="mx-auto mt-2 max-w-sm text-[13.5px] leading-relaxed text-(--color-text-secondary)">
        Đặt chỗ này không tồn tại hoặc không thuộc tài khoản của bạn.
      </p>
      <Link to="/boarding-bookings" className={cn(BTN_PRIMARY, 'mt-6')}>
        <ArrowLeft size={16} aria-hidden /> Quay lại Lưu trú của tôi
      </Link>
    </div>
  );
}
