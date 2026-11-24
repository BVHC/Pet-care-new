import { useState } from 'react';
import { Users } from 'lucide-react';
import { QueryError, RowsSkeleton, StatePanel } from '@/components/staff/ui';
import { Card, CardContent } from '@/components/ui/card';
import { StatusBadge } from '@/components/ui/modal-templates';
import { cn } from '@/lib/utils';
import { toApiError } from '@/shared/api/api-error';
import { useShiftWeeklySummaries } from '@/shared/hooks/useWorkforce';
import { ROLE_LABELS } from '@/shared/types/auth';
import { nextWeekStart } from './registration';
import { WeekPicker } from './WeekPicker';

/**
 * Thống kê số ca trong tuần của nhân viên chi nhánh (UC34, workforce-v1 #16): mặc định tuần kế tiếp, mọi lễ tân / bác sĩ / nhân viên chăm sóc đang hoạt động kể cả người chưa có ca.
 * Số ca gồm cả ca nhân viên tự đăng ký và ca quản lý xếp. Chỉ để theo dõi định mức (BR-NS-12), không chặn gì.
 */
export function WeeklySummaryTab() {
  const [weekStart, setWeekStart] = useState(nextWeekStart);
  const list = useShiftWeeklySummaries({ weekStart });
  const data = list.data;
  const stale = list.isPlaceholderData;

  let body;
  if (!data && list.isError) body = <QueryError error={toApiError(list.error)} onRetry={() => list.refetch()} what="thống kê số ca" />;
  else if (!data) body = <RowsSkeleton rows={5} />;
  else if (data.length === 0) body = <StatePanel icon={Users} title="Chưa có nhân viên để thống kê" body="Chi nhánh chưa có lễ tân, bác sĩ hoặc nhân viên chăm sóc đang hoạt động." />;
  else {
    body = (
      <table className={cn('table min-w-[640px]', stale && 'opacity-60')} aria-label="Thống kê số ca theo tuần" aria-busy={stale}>
        <thead>
          <tr>
            <th>Nhân viên</th>
            <th>Vai trò</th>
            <th>Số ca</th>
            <th>Định mức</th>
            <th>Kết quả</th>
          </tr>
        </thead>
        <tbody>
          {data.map((r) => (
            <tr key={r.staffId}>
              <td className="font-medium text-(--text-primary)">{r.staffName}</td>
              <td>{ROLE_LABELS[r.role]}</td>
              <td className="tabular-nums">{r.shiftCount}</td>
              <td className="tabular-nums">{r.requiredShifts}</td>
              <td>
                <StatusBadge status={r.meetsRequirement ? 'Đạt' : 'Chưa đạt'} variant={r.meetsRequirement ? 'success' : 'warning'} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    );
  }

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="flex flex-wrap items-center justify-between gap-3 p-4">
          <WeekPicker weekStart={weekStart} onChange={setWeekStart} />
          {data && data.length > 0 && (
            <p className="text-sm text-(--text-secondary)">{`${data.filter((r) => r.meetsRequirement).length} / ${data.length} nhân viên đạt định mức`}</p>
          )}
        </CardContent>
      </Card>
      <Card className="card">
        <CardContent className="overflow-x-auto p-0">{body}</CardContent>
      </Card>
      <p className="text-xs text-(--text-secondary)">Số ca gồm cả ca nhân viên tự đăng ký và ca do quản lý xếp. Thống kê chỉ để theo dõi, không chặn thao tác nào.</p>
    </div>
  );
}
