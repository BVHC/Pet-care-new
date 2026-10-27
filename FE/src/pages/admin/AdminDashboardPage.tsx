import type { ElementType } from 'react';
import { Link } from 'react-router-dom';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { useAccount } from '../../shared/stores/session.store';
import { cn, formatCurrency } from '../../lib/utils';
import {
  Calendar, Clock, ConciergeBell, DollarSign, RotateCw, Scissors, Stethoscope, Wallet,
} from 'lucide-react';
import { rolesForPath } from '../../shared/constants/staff-menu';
import {
  WORKSPACE_LABELS, WORKSPACE_PATHS, getAccessibleWorkspaces, type Workspace,
} from '../../shared/constants/workspaces';
import { ORDER_READERS, usePendingSalesOrders } from '../../shared/hooks/usePendingSalesOrders';
import { useRevenueReport } from '../../shared/hooks/useReports';
import { useVisitQueue } from '../../shared/hooks/useVisits';
import { VISIT_GROUP_LABELS, visitPermissions, type Visit, type VisitServiceGroup } from '../../shared/types/visit';
import { errorMessage } from '../../shared/utils/error-message';
import { vnDate } from '../../shared/utils/vn-time';

const GROUP_BAR: Record<VisitServiceGroup, string> = { MEDICAL: 'bg-teal-600', GROOMING: 'bg-purple-500' };

const WORKSPACE_ICON: Record<Exclude<Workspace, 'admin'>, typeof ConciergeBell> = {
  reception: ConciergeBell,
  doctor: Stethoscope,
  grooming: Scissors,
};

/** Báo cáo doanh thu (report-v1 #1) mở cho ai thì "Đã thu hôm nay" hiện cho người đó: theo mục menu Báo cáo (UC89). */
const REPORTS_PATH = '/admin/reports';

interface Stat {
  label: string;
  /** `undefined` = đang tải. */
  value: string | number | undefined;
  icon: ElementType;
  color: 'blue' | 'green' | 'amber' | 'red';
  error: unknown;
  retry: () => void;
}

/**
 * Bảng điều khiển (T49): mỗi số liệu chỉ hiện cho vai trò mà endpoint hợp đồng cho phép, để tải trang không bắn request bị 403.
 * - Hàng đợi chi nhánh (visit-v1 #2): BRANCH_MANAGER, RECEPTIONIST, VET, CARETAKER → lượt tiếp nhận, đang chờ / đang làm, nhóm dịch vụ.
 * - Order PENDING (sales-v1 #2): cùng bốn vai trò → đơn chờ thu tiền.
 * - Doanh thu theo kỳ (report-v1 #1): SUPER_MANAGER (toàn chuỗi), BRANCH_MANAGER (chi nhánh mình) → đã thu hôm nay.
 * Mỗi số liệu hỏng riêng: thẻ đó hiện "–" kèm Thử lại, các thẻ khác vẫn chạy. Nhật ký hoạt động không có nguồn hợp đồng nên đã bỏ.
 */
export function AdminDashboardPage() {
  const account = useAccount();
  const role = account?.role;
  const canQueue = visitPermissions(role).queue;
  const canPending = role != null && ORDER_READERS.includes(role);
  const canRevenue = role != null && rolesForPath(REPORTS_PATH).includes(role);

  const today = vnDate();
  // `date` = ngày check-in theo giờ Việt Nam, mọi trạng thái; không `date` = lượt chưa kết thúc của mọi ngày (cùng khóa với thanh bên nên dùng chung một lần tải).
  const todayVisits = useVisitQueue({ date: today }, canQueue);
  const openVisits = useVisitQueue({}, canQueue);
  const pending = usePendingSalesOrders(canPending);
  const revenue = useRevenueReport({ from: today, to: today }, canRevenue);

  const countOpen = (status: 'WAITING' | 'IN_PROGRESS') => openVisits.data?.filter((v) => v.status === status).length ?? 0;

  const stats: Stat[] = [];
  if (canQueue) {
    stats.push({
      label: 'Lượt tiếp nhận hôm nay', value: todayVisits.data?.length, icon: Calendar, color: 'blue',
      error: todayVisits.error, retry: () => void todayVisits.refetch(),
    });
  }
  if (canRevenue) {
    stats.push({
      label: role === 'SUPER_MANAGER' ? 'Đã thu hôm nay (toàn chuỗi)' : 'Đã thu hôm nay',
      value: revenue.data && formatCurrency(revenue.data.totalAmount), icon: DollarSign, color: 'green',
      error: revenue.error, retry: () => void revenue.refetch(),
    });
  }
  if (canQueue) {
    stats.push({
      label: 'Đang chờ / đang làm', value: openVisits.data && `${countOpen('WAITING')} / ${countOpen('IN_PROGRESS')}`, icon: Clock, color: 'amber',
      error: openVisits.error, retry: () => void openVisits.refetch(),
    });
  }
  if (canPending) {
    stats.push({
      label: 'Đơn chờ thu tiền', value: pending.data?.length, icon: Wallet, color: 'red',
      error: pending.error, retry: () => void pending.refetch(),
    });
  }

  const refreshing = [todayVisits, openVisits, pending, revenue].some((q) => q.isFetching && !q.isLoading);
  const workspaces = (account ? getAccessibleWorkspaces(account.role) : []).filter(
    (ws): ws is Exclude<Workspace, 'admin'> => ws !== 'admin',
  );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-(--text-primary)">Bảng điều khiển</h1>
          <p className="text-(--text-secondary)">
            {role === 'SUPER_MANAGER'
              ? 'Doanh thu hôm nay của toàn chuỗi.'
              : 'Số liệu hôm nay của chi nhánh; hàng đợi và đơn chờ thu tự cập nhật.'}
          </p>
        </div>
        {refreshing && (
          <span className="flex items-center gap-1.5 text-xs text-(--text-tertiary)">
            <RotateCw className="h-3.5 w-3.5 animate-spin" aria-hidden /> Đang cập nhật
          </span>
        )}
      </div>

      {stats.length > 0 && (
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
                    {stat.error != null ? (
                      <p className="text-2xl font-semibold text-(--text-tertiary)" title={errorMessage(stat.error)}>–</p>
                    ) : stat.value === undefined ? (
                      <span className="ws-skel block h-7 w-16" aria-label="Đang tải" />
                    ) : (
                      <p className="text-2xl font-semibold tabular-nums text-(--text-primary)">{stat.value}</p>
                    )}
                    <p className="text-sm text-(--text-secondary)">{stat.label}</p>
                    {stat.error != null && (
                      <p role="alert" className="text-xs text-red-700">
                        Không tải được.{' '}
                        <button type="button" className="font-semibold underline" onClick={stat.retry}>
                          Thử lại
                        </button>
                      </p>
                    )}
                  </div>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {(canQueue || workspaces.length > 0) && (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          {canQueue && <ServiceGroupCard visits={todayVisits.data} error={todayVisits.error} retry={() => void todayVisits.refetch()} />}

          {workspaces.length > 0 && (
            <Card className="card">
              <CardHeader className="pb-3">
                <CardTitle className="text-base font-semibold">Mở bàn làm việc</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
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
      )}
    </div>
  );
}

/** Lượt hôm nay theo nhóm dịch vụ của Visit (MEDICAL / GROOMING); lượt đã hủy không tính vào dịch vụ đã dùng. */
function ServiceGroupCard({ visits, error, retry }: { visits: Visit[] | undefined; error: unknown; retry: () => void }) {
  const byGroup: Record<VisitServiceGroup, number> = { MEDICAL: 0, GROOMING: 0 };
  visits?.filter((v) => v.status !== 'CANCELLED').forEach((v) => { byGroup[v.serviceGroup] += 1; });
  const total = byGroup.MEDICAL + byGroup.GROOMING;

  return (
    <>
      <Card className="card">
        <CardHeader className="pb-3">
          <CardTitle className="text-base font-semibold">Dịch vụ hôm nay</CardTitle>
        </CardHeader>
        <CardContent>
          {error != null ? (
            <p role="alert" className="text-sm text-red-700" title={errorMessage(error)}>
              Không tải được.{' '}
              <button type="button" className="font-semibold underline" onClick={retry}>
                Thử lại
              </button>
            </p>
          ) : visits && total === 0 ? (
            <p className="text-sm text-(--text-secondary)">Chưa có lượt nào hôm nay.</p>
          ) : (
            <div className="space-y-4">
              {(Object.keys(VISIT_GROUP_LABELS) as VisitServiceGroup[]).map((group) => (
                <div key={group}>
                  <div className="mb-1.5 flex justify-between text-sm">
                    <span className="text-(--text-primary)">{VISIT_GROUP_LABELS[group]}</span>
                    <span className="font-medium tabular-nums text-(--text-secondary)">{visits ? `${byGroup[group]} lượt` : '…'}</span>
                  </div>
                  <div className="h-2 overflow-hidden rounded-full bg-(--bg-tertiary)">
                    <div
                      className={cn('h-full rounded-full transition-[width]', GROUP_BAR[group])}
                      style={{ width: visits && total ? `${(byGroup[group] / total) * 100}%` : '0%' }}
                    />
                  </div>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </>
  );
}
