// Khung 4 trạng thái dùng chung cho mọi khối báo cáo: chưa truy vấn (kỳ chưa hợp lệ) · đang tải · lỗi · rỗng · có dữ liệu.
import type { ReactNode } from 'react';
import type { UseQueryResult } from '@tanstack/react-query';
import { AlertTriangle } from 'lucide-react';
import { Button } from '../../../components/ui/button';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import type { ReportPeriodQuery } from '../../../shared/types/report';

/** Props chung của tab báo cáo theo kỳ; `enabled=false` khi kỳ chưa hợp lệ (không gửi request). */
export interface PeriodTabProps {
  query: ReportPeriodQuery;
  enabled: boolean;
  branchName: (branchId: number) => string;
}

interface ReportBodyProps<T> {
  title: string;
  query: UseQueryResult<T, unknown>;
  enabled: boolean;
  /** Câu hiện khi chưa truy vấn. */
  idleText?: string;
  emptyText?: string;
  isEmpty: (data: T) => boolean;
  children: (data: T) => ReactNode;
}

const Note = ({ children }: { children: ReactNode }) => <p className="px-6 py-10 text-center text-sm text-(--text-secondary)">{children}</p>;

export function ReportBody<T>({
  title,
  query,
  enabled,
  idleText = 'Chọn kỳ báo cáo hợp lệ để xem số liệu.',
  emptyText = 'Không có dữ liệu trong kỳ',
  isEmpty,
  children,
}: ReportBodyProps<T>) {
  let body: ReactNode;
  if (!enabled) {
    // Kiểm `enabled` trước hết: khóa truy vấn đổi sang kỳ sai thì React Query vẫn giữ số liệu của kỳ trước làm placeholder.
    body = <Note>{idleText}</Note>;
  } else if (query.isError) {
    body = (
      <div role="alert" className="flex flex-col items-center px-6 py-10 text-center">
        <AlertTriangle className="mb-3 h-8 w-8 text-red-600" aria-hidden />
        <p className="font-semibold text-(--text-primary)">Không tải được báo cáo {title}</p>
        <p className="mt-1 text-sm text-red-700">{plainMessage(toApiError(query.error))}</p>
        <Button className="mt-4" variant="outline" onClick={() => query.refetch()}>
          Thử lại
        </Button>
      </div>
    );
  } else if (query.isPending) {
    body = (
      <div role="status" aria-label={`Đang tải báo cáo ${title}`} className="divide-y divide-(--border-subtle)">
        {[0, 1, 2].map((i) => (
          <div key={i} className="flex gap-4 px-4 py-4">
            <div className="h-4 w-32 rounded bg-(--bg-tertiary) motion-safe:animate-pulse" />
            <div className="h-4 w-24 rounded bg-(--bg-tertiary) motion-safe:animate-pulse" />
            <div className="h-4 flex-1 rounded bg-(--bg-tertiary) motion-safe:animate-pulse" />
          </div>
        ))}
      </div>
    );
  } else if (isEmpty(query.data)) {
    body = <Note>{emptyText}</Note>;
  } else {
    // Đổi kỳ / chi nhánh: số liệu cũ còn đó (keepPreviousData) cho tới khi số liệu mới về, làm mờ để khỏi nhầm.
    body = (
      <div aria-busy={query.isFetching} className={query.isPlaceholderData ? 'opacity-60' : undefined}>
        {children(query.data)}
      </div>
    );
  }

  return (
    <section aria-label={title} className="card overflow-hidden">
      <h2 className="px-4 pt-4 text-base font-semibold text-(--text-primary)">{title}</h2>
      {body}
    </section>
  );
}

/** Thẻ số liệu tổng. */
export function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="card-kpi px-4 py-3">
      <p className="text-sm text-(--text-secondary)">{label}</p>
      <p className="mt-1 text-2xl font-semibold tabular-nums text-(--text-primary)">{value}</p>
    </div>
  );
}
