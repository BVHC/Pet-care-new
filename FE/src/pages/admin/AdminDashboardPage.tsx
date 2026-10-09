import { Link } from 'react-router-dom';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { useAccount } from '../../shared/stores/session.store';
import { cn, formatCurrency } from '../../lib/utils';
import {
  AlertTriangle, Calendar, Clock, ConciergeBell, DollarSign, RotateCw, Scissors, Stethoscope, Wallet,
} from 'lucide-react';
import { useDashboardSummary } from '../../shared/hooks/useClinic';
import { KIND_LABEL } from '../../shared/constants/clinic-labels';
import {
  WORKSPACE_LABELS, WORKSPACE_PATHS, getAccessibleWorkspaces, type Workspace,
} from '../../shared/constants/workspaces';
import type { ActivityKind, ServiceKind } from '../../shared/types/clinic';
import { formatClock } from '../../shared/utils/clinic-format';
import { errorMessage } from '../../shared/hooks/useClinic';

const KIND_BAR: Record<ServiceKind, string> = { EXAM: 'bg-teal-600', VACCINATION: 'bg-blue-500', GROOMING: 'bg-purple-500' };

const ACTIVITY_DOT: Record<ActivityKind, string> = {
  CHECK_IN: 'bg-blue-500',
  ASSIGN: 'bg-slate-400',
  CALL: 'bg-amber-500',
  COMPLETE: 'bg-teal-600',
  CANCEL: 'bg-red-500',
  PAY: 'bg-green-600',
  SHIFT: 'bg-slate-400',
};

const WORKSPACE_ICON: Record<Exclude<Workspace, 'admin'>, typeof ConciergeBell> = {
  reception: ConciergeBell,
  doctor: Stethoscope,
  grooming: Scissors,
};

export function AdminDashboardPage() {
  const account = useAccount();
  const summary = useDashboardSummary();
  const s = summary.data;

  const stats = [
    { label: 'Lượt tiếp nhận hôm nay', value: s?.visitsToday, icon: Calendar, color: 'blue' },
    { label: 'Đã thu hôm nay', value: s && formatCurrency(s.revenueToday), icon: DollarSign, color: 'green' },
    { label: 'Đang chờ / đang làm', value: s && `${s.waiting} / ${s.inProgress}`, icon: Clock, color: 'amber' },
    { label: 'Đơn chờ thu tiền', value: s?.pendingPayment, icon: Wallet, color: 'red' },
  ];

  const kindTotal = s ? Object.values(s.byKind).reduce((a, b) => a + b, 0) : 0;
  const workspaces = (account ? getAccessibleWorkspaces(account.role) : []).filter(
    (ws): ws is Exclude<Workspace, 'admin'> => ws !== 'admin',
  );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-(--text-primary)">Bảng điều khiển</h1>
          <p className="text-(--text-secondary)">Số liệu hôm nay của chi nhánh, tự cập nhật mỗi 5 giây.</p>
        </div>
        {summary.isFetching && !summary.isLoading && (
          <span className="flex items-center gap-1.5 text-xs text-(--text-tertiary)">
            <RotateCw className="h-3.5 w-3.5 animate-spin" aria-hidden /> Đang cập nhật
          </span>
        )}
      </div>

      {summary.isError && (
        <div role="alert" className="flex items-center gap-3 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          <AlertTriangle className="h-4 w-4 shrink-0" aria-hidden />
          <span className="flex-1">Không tải được số liệu: {errorMessage(summary.error)}</span>
          <button type="button" className="font-semibold underline" onClick={() => summary.refetch()}>
            Thử lại
          </button>
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {stats.map((stat) => (
          <Card key={stat.label} className="card-kpi">
            <CardContent className="p-5">
              <div className="flex items-center gap-4">
                <div
                  className={cn(
                    'flex h-12 w-12 items-center justify-center rounded-xl',
                    stat.color === 'blue' && 'bg-blue-100',
                    stat.color === 'green' && 'bg-green-100',
                    stat.color === 'amber' && 'bg-amber-100',
                    stat.color === 'red' && 'bg-red-100',
                  )}
                >
                  <stat.icon
                    className={cn(
                      'h-6 w-6',
                      stat.color === 'blue' && 'text-blue-600',
                      stat.color === 'green' && 'text-green-600',
                      stat.color === 'amber' && 'text-amber-600',
                      stat.color === 'red' && 'text-red-600',
                    )}
                  />
                </div>
                <div className="min-w-0 flex-1">
                  {stat.value === undefined ? (
                    <span className="ws-skel block h-7 w-16" aria-label="Đang tải" />
                  ) : (
                    <p className="text-2xl font-semibold tabular-nums text-(--text-primary)">{stat.value}</p>
                  )}
                  <p className="text-sm text-(--text-secondary)">{stat.label}</p>
                </div>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Card className="card">
          <CardHeader className="pb-3">
            <CardTitle className="text-base font-semibold">Dịch vụ hôm nay</CardTitle>
          </CardHeader>
          <CardContent>
            {s && kindTotal === 0 ? (
              <p className="text-sm text-(--text-secondary)">Chưa có lượt nào hôm nay.</p>
            ) : (
              <div className="space-y-4">
                {(Object.keys(KIND_LABEL) as ServiceKind[]).map((kind) => (
                  <div key={kind}>
                    <div className="mb-1.5 flex justify-between text-sm">
                      <span className="text-(--text-primary)">{KIND_LABEL[kind]}</span>
                      <span className="font-medium tabular-nums text-(--text-secondary)">{s ? `${s.byKind[kind]} lượt` : '…'}</span>
                    </div>
                    <div className="h-2 overflow-hidden rounded-full bg-(--bg-tertiary)">
                      <div
                        className={cn('h-full rounded-full transition-[width]', KIND_BAR[kind])}
                        style={{ width: s && kindTotal ? `${(s.byKind[kind] / kindTotal) * 100}%` : '0%' }}
                      />
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        <Card className="card">
          <CardHeader className="pb-3">
            <CardTitle className="text-base font-semibold">Hoạt động gần đây</CardTitle>
          </CardHeader>
          <CardContent>
            {s?.activity.length === 0 && <p className="text-sm text-(--text-secondary)">Chưa có hoạt động nào hôm nay.</p>}
            <ul className="space-y-1">
              {s?.activity.map((item) => (
                <li key={item.id} className="flex items-center gap-3 py-1.5">
                  <span className={cn('h-2 w-2 shrink-0 rounded-full', ACTIVITY_DOT[item.kind])} aria-hidden />
                  <p className="min-w-0 flex-1 truncate text-sm text-(--text-primary)">{item.text}</p>
                  <time className="shrink-0 text-xs tabular-nums text-(--text-tertiary)" dateTime={item.at}>
                    {formatClock(item.at)}
                  </time>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      </div>

      {workspaces.length > 0 && (
        <Card className="card">
          <CardHeader className="pb-3">
            <CardTitle className="text-base font-semibold">Mở bàn làm việc</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              {workspaces.map((ws) => {
                const Icon = WORKSPACE_ICON[ws];
                return (
                  <Link
                    key={ws}
                    to={WORKSPACE_PATHS[ws]}
                    className="flex items-center gap-3 rounded-xl bg-(--bg-secondary) p-4 transition-colors hover:bg-(--bg-tertiary)"
                  >
                    <span className="ws-swatch" data-zone={ws} aria-hidden>
                      <Icon className="h-4 w-4" />
                    </span>
                    <span className="text-sm font-medium text-(--text-primary)">{WORKSPACE_LABELS[ws]}</span>
                  </Link>
                );
              })}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
