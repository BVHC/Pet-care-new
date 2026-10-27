import type { ReactNode } from 'react';
import { Button } from '../../../components/ui/button';

/** Một hàng cho ba trạng thái không có dữ liệu của bảng: đang tải, lỗi (có Thử lại) và rỗng (`children`). */
export function TableState({
  colSpan,
  loading,
  error,
  onRetry,
  children,
}: {
  colSpan: number;
  loading: boolean;
  error?: string;
  onRetry: () => void;
  children: ReactNode;
}) {
  return (
    <tr>
      <td colSpan={colSpan} className="px-4 py-10! text-center text-(--text-tertiary)!">
        {loading ? (
          'Đang tải…'
        ) : error ? (
          <div role="alert" className="mx-auto flex max-w-sm flex-col items-center gap-3">
            <p className="font-medium text-red-700">{error}</p>
            <Button size="sm" variant="outline" onClick={onRetry}>
              Thử lại
            </Button>
          </div>
        ) : (
          children
        )}
      </td>
    </tr>
  );
}
