/**
 * Việc gọi điện của lễ tân (UC87) — care-v1 #1–4. Chỉ RECEPTIONIST (BR-TB-05): danh sách theo trạng thái (Đang chờ / Đã xong /
 * Đã hủy, hợp đồng không có "tất cả"), loại việc và khoảng hạn; "Đã gọi" ghi kết quả + ghi chú, "Hủy" ghi lý do. Chỉ việc
 * đang chờ mới có nút. Việc do hệ thống sinh (ST18) và hủy (ST19, BR-TB-03, BR-TB-06) nên trang không có nút tạo.
 */
import { useEffect, useState, type ComponentType } from 'react';
import { differenceInCalendarDays, parseISO } from 'date-fns';
import { CircleCheck, Inbox, Phone, PhoneCall, X } from 'lucide-react';
import { formatShortDate, formatVnDateTime, todayVn } from '@/components/appointments/format';
import { NoticeBar, selectClass, type Notice } from '@/components/admin/form-ui';
import { QueryError, RowsSkeleton, StatePanel } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { StatusBadge } from '@/components/ui/modal-templates';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { cn } from '@/lib/utils';
import { useCareTasks } from '@/shared/hooks/useCareTasks';
import { useSession } from '@/shared/stores/session.store';
import {
  CARE_TASK_RESULT_LABELS,
  CARE_TASK_STATUS_LABELS,
  CARE_TASK_STATUSES,
  CARE_TASK_TYPE_LABELS,
  CARE_TASK_TYPES,
  type CareTask,
  type CareTaskStatus,
  type CareTaskType,
} from '@/shared/types/care';
import { CancelCareTaskDialog, CompleteCareTaskDialog } from './care-tasks/CareTaskDialogs';

const PAGE_SIZE = 20;

type Tone = 'success' | 'warning' | 'danger' | 'info' | 'neutral';
/** Luôn kèm chữ, không dùng màu đơn lẻ. Việc quá hạn (sinh sau hạn) nổi hơn việc nhắc trước hạn. */
const TYPE_TONE: Record<CareTaskType, Tone> = { VACCINE_DUE: 'neutral', FOLLOW_UP_DUE: 'neutral', VACCINE_OVERDUE: 'warning', PICKUP_OVERDUE: 'warning' };
const STATUS_TONE: Record<CareTaskStatus, Tone> = { OPEN: 'info', DONE: 'success', CANCELLED: 'neutral' };

const EMPTY: Record<CareTaskStatus, { icon: ComponentType<{ className?: string }>; title: string; body: string }> = {
  OPEN: { icon: CircleCheck, title: 'Không còn việc nào cần gọi', body: 'Việc mới xuất hiện khi khách đến hạn tái chủng, tái khám hoặc thú quá hạn đến đón.' },
  DONE: { icon: Inbox, title: 'Chưa có việc nào đã xong', body: 'Việc đã gọi xong và ghi kết quả sẽ nằm ở đây.' },
  CANCELLED: { icon: Inbox, title: 'Chưa có việc nào bị hủy', body: 'Việc do lễ tân hủy hoặc hệ thống tự hủy sẽ nằm ở đây.' },
};

type Acting = { kind: 'complete' | 'cancel'; task: CareTask };

export function AdminCareTasksPage() {
  const viewerId = useSession((s) => s.account?.id);
  const [status, setStatus] = useState<CareTaskStatus>('OPEN');
  const [type, setType] = useState<CareTaskType | ''>('');
  const [dueFrom, setDueFrom] = useState('');
  const [dueTo, setDueTo] = useState('');
  const [page, setPage] = useState(0);
  const [acting, setActing] = useState<Acting | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const list = useCareTasks({
    status,
    type: type || undefined,
    dueFrom: dueFrom || undefined,
    dueTo: dueTo || undefined,
    page,
    size: PAGE_SIZE,
  });
  const data = list.data;
  // Đang chờ dữ liệu của bộ lọc mới thì bảng còn là của bộ lọc cũ: không cho bấm vào hàng cũ.
  const stale = list.isPlaceholderData;
  const filtered = type !== '' || dueFrom !== '' || dueTo !== '';
  const today = todayVn();
  // Đổi bộ lọc hoặc trang: thông báo của thao tác trước không còn thuộc danh sách đang xem.
  const goTo = (p: number) => {
    setPage(p);
    setNotice(null);
  };
  const reset = <T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    goTo(0);
  };

  // Xử lý nốt việc cuối của trang cuối thì trang đó trống: lùi về trang cuối còn việc.
  useEffect(() => {
    if (data && data.page > 0 && data.content.length === 0) setPage(Math.max(data.totalPages - 1, 0));
  }, [data]);

  const done = (text: string) => {
    setActing(null);
    setNotice({ kind: 'success', text });
  };

  let body;
  if (!data && list.isError) {
    body = <QueryError error={list.error} onRetry={() => list.refetch()} what="việc gọi điện" />;
  } else if (!data) {
    body = <RowsSkeleton rows={5} />;
  } else if (data.content.length === 0) {
    const empty = filtered ? { icon: Inbox, title: 'Không có việc nào khớp bộ lọc', body: 'Thử đổi loại việc hoặc khoảng hạn.' } : EMPTY[status];
    body = <StatePanel icon={empty.icon} title={empty.title} body={empty.body} />;
  } else {
    // Chỉ việc đang chờ có nút: bảng toàn việc đã xử lý thì bỏ cột Thao tác.
    const showActions = data.content.some((t) => t.status === 'OPEN');
    body = (
      <>
        <p role="status" className="border-b border-(--border-color) px-4 py-3 text-sm text-(--text-secondary)">
          {stale ? 'Đang tải…' : `${data.totalElements} việc ${CARE_TASK_STATUS_LABELS[status].toLowerCase()}`}
        </p>
        <table className={cn('table min-w-[880px]', stale && 'opacity-60')} aria-label="Danh sách việc gọi điện" aria-busy={stale}>
          <thead>
            <tr className="[&>th]:whitespace-nowrap">
              <th>Hạn</th>
              <th>Khách hàng</th>
              <th>Việc cần gọi</th>
              <th>Trạng thái</th>
              {showActions && <th className="text-right!">Thao tác</th>}
            </tr>
          </thead>
          <tbody>
            {data.content.map((t) => {
              const late = t.status === 'OPEN' ? differenceInCalendarDays(parseISO(today), parseISO(t.dueDate)) : 0;
              return (
                <tr key={t.careTaskId}>
                  <td className="whitespace-nowrap align-top">
                    <time dateTime={t.dueDate} className="font-semibold tabular-nums text-(--text-primary)">
                      {formatShortDate(t.dueDate)}
                    </time>
                    {late > 0 && (
                      <span className="mt-1 block">
                        <StatusBadge status={`Quá hạn ${late} ngày`} variant="danger" />
                      </span>
                    )}
                  </td>
                  <td className="min-w-44 align-top">
                    <span className="block whitespace-nowrap font-medium text-(--text-primary)">{t.customerName}</span>
                    {t.phoneToCall ? (
                      <a
                        href={`tel:${t.phoneToCall}`}
                        className="mt-0.5 inline-flex items-center gap-1.5 text-sm font-semibold tabular-nums text-(--ws-focus) hover:underline"
                      >
                        <Phone className="h-3.5 w-3.5" aria-hidden />
                        {t.phoneToCall}
                      </a>
                    ) : (
                      <span className="mt-0.5 block whitespace-nowrap text-xs text-(--text-tertiary)">Chưa có số điện thoại</span>
                    )}
                  </td>
                  <td className="align-top">
                    <span className="flex flex-wrap items-center gap-2">
                      <StatusBadge status={CARE_TASK_TYPE_LABELS[t.type]} variant={TYPE_TONE[t.type]} />
                      <span className="font-medium text-(--text-primary)">{t.petName}</span>
                    </span>
                    <span className="mt-1 block text-sm text-(--text-secondary)">{t.context}</span>
                    {t.status !== 'OPEN' && <Outcome task={t} viewerId={viewerId} />}
                  </td>
                  <td className="whitespace-nowrap align-top">
                    <StatusBadge status={CARE_TASK_STATUS_LABELS[t.status]} variant={STATUS_TONE[t.status]} />
                  </td>
                  {showActions && (
                    <td className="align-top">
                      {t.status === 'OPEN' && (
                        <div className="flex justify-end gap-1.5">
                          <Button
                            size="sm"
                            disabled={stale}
                            onClick={() => setActing({ kind: 'complete', task: t })}
                            aria-label={`Đã gọi: ${t.customerName} (${t.petName})`}
                          >
                            <PhoneCall className="h-4 w-4" aria-hidden /> Đã gọi
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            className="border-red-200 text-red-600"
                            disabled={stale}
                            onClick={() => setActing({ kind: 'cancel', task: t })}
                            aria-label={`Hủy việc: ${t.customerName} (${t.petName})`}
                          >
                            <X className="h-4 w-4" aria-hidden /> Hủy
                          </Button>
                        </div>
                      )}
                    </td>
                  )}
                </tr>
              );
            })}
          </tbody>
        </table>
        {data.totalPages > 1 && (
          <div className="flex items-center justify-end gap-3 border-t border-(--border-color) px-4 py-3 text-sm text-(--text-secondary)">
            <span>
              Trang {data.page + 1}/{data.totalPages} · {data.totalElements} việc
            </span>
            <Button size="sm" variant="outline" disabled={data.page === 0} onClick={() => goTo(data.page - 1)}>
              Trước
            </Button>
            <Button size="sm" variant="outline" disabled={data.page + 1 >= data.totalPages} onClick={() => goTo(data.page + 1)}>
              Sau
            </Button>
          </div>
        )}
      </>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Việc gọi điện</h1>
        <p className="text-(--text-secondary)">Khách cần gọi tại chi nhánh: nhắc tái chủng, nhắc tái khám và thú quá hạn đến đón. Gọi xong thì ghi kết quả.</p>
      </div>

      <Card className="card">
        <CardContent className="flex flex-wrap items-center gap-3 p-4">
          <Tabs value={status} onValueChange={(v) => reset(setStatus)(v as CareTaskStatus)}>
            <TabsList aria-label="Lọc trạng thái">
              {CARE_TASK_STATUSES.map((s) => (
                <TabsTrigger key={s} value={s}>
                  {CARE_TASK_STATUS_LABELS[s]}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>
          <select
            aria-label="Lọc loại việc"
            className={cn(selectClass, 'w-48')}
            value={type}
            onChange={(e) => reset(setType)(e.target.value as CareTaskType | '')}
          >
            <option value="">Mọi loại</option>
            {CARE_TASK_TYPES.map((t) => (
              <option key={t} value={t}>
                {CARE_TASK_TYPE_LABELS[t]}
              </option>
            ))}
          </select>
          <label className="flex items-center gap-2 text-sm text-(--text-secondary)">
            Hạn từ
            <input
              type="date"
              className={cn(selectClass, 'w-40')}
              value={dueFrom}
              max={dueTo || undefined}
              onChange={(e) => reset(setDueFrom)(e.target.value)}
            />
          </label>
          <label className="flex items-center gap-2 text-sm text-(--text-secondary)">
            Hạn đến
            <input
              type="date"
              className={cn(selectClass, 'w-40')}
              value={dueTo}
              min={dueFrom || undefined}
              onChange={(e) => reset(setDueTo)(e.target.value)}
            />
          </label>
          {filtered && (
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                setType('');
                setDueFrom('');
                setDueTo('');
                goTo(0);
              }}
            >
              Xóa bộ lọc
            </Button>
          )}
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="overflow-x-auto p-0">{body}</CardContent>
      </Card>

      {acting?.kind === 'complete' && <CompleteCareTaskDialog task={acting.task} onClose={() => setActing(null)} onDone={done} />}
      {acting?.kind === 'cancel' && <CancelCareTaskDialog task={acting.task} onClose={() => setActing(null)} onDone={done} />}
    </div>
  );
}

/** Kết quả đã ghi của việc đã xong / đã hủy: kết quả gọi, ghi chú hoặc lý do hủy, người xử lý ("Hệ thống" khi hệ thống hủy) và giờ. */
function Outcome({ task, viewerId }: { task: CareTask; viewerId: number | undefined }) {
  const who = task.handledBy == null ? 'Hệ thống' : task.handledBy === viewerId ? 'Bạn' : `Nhân viên #${task.handledBy}`;
  return (
    <span className="mt-2 block space-y-1 border-l-2 border-(--border-color) pl-3 text-sm">
      {task.result && <StatusBadge status={CARE_TASK_RESULT_LABELS[task.result]} variant={task.result === 'REACHED' ? 'success' : 'warning'} />}
      {task.note && <span className="block whitespace-pre-line text-(--text-primary)">{task.note}</span>}
      {task.cancelReason && (
        <span className="block text-(--text-primary)">
          <span className="text-(--text-secondary)">Lý do hủy: </span>
          {task.cancelReason}
        </span>
      )}
      <span className="block text-xs text-(--text-tertiary)">
        Người xử lý: {who}
        {task.handledAt && ` · ${formatVnDateTime(task.handledAt)}`}
      </span>
    </span>
  );
}
