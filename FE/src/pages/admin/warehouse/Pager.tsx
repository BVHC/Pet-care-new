import { Button } from '../../../components/ui/button';
import type { PageResponse } from '../../../shared/types';

/** Thanh phân trang dùng chung cho các tab kho tầng 3 (BE đánh trang từ 0). */
export function Pager({ data, noun, onPage }: { data: PageResponse<unknown>; noun: string; onPage: (page: number) => void }) {
  if (data.totalPages <= 1) return null;
  return (
    <div className="flex items-center justify-end gap-3 border-t border-(--border-color) px-4 py-3 text-sm text-(--text-secondary)">
      <span>
        Trang {data.page + 1}/{data.totalPages} · {data.totalElements} {noun}
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
