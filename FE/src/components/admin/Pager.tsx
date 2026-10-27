import { Button } from '../ui/button';
import type { PageResponse } from '../../shared/types';

/**
 * Chân bảng "Trang X/Y · N <đơn vị>" cho `PageResponse` của BE (`page` bắt đầu từ 0). Chỉ hiện khi có từ 2 trang.
 * `data.page` là trang BE đã trả, nên nút Trước / Sau luôn đi từ trang đang hiện.
 */
export function Pager({ data, unit, onPage }: { data: Pick<PageResponse<unknown>, 'page' | 'totalPages' | 'totalElements'>; unit: string; onPage: (page: number) => void }) {
  if (data.totalPages <= 1) return null;
  return (
    <div className="flex items-center justify-end gap-3 border-t border-(--border-color) px-4 py-3 text-sm text-(--text-secondary)">
      <span>
        Trang {data.page + 1}/{data.totalPages} · {data.totalElements} {unit}
      </span>
      <Button size="sm" variant="outline" disabled={data.page === 0} onClick={() => onPage(data.page - 1)}>
        Trước
      </Button>
      <Button size="sm" variant="outline" disabled={data.page + 1 >= data.totalPages} onClick={() => onPage(data.page + 1)}>
        Sau
      </Button>
    </div>
  );
}
