/**
 * Khiếu nại (UC86) — complaint-v1 #8–12. SUPER_MANAGER và BRANCH_MANAGER nhận / trả lời / kết luận; máy chủ tự lọc phạm vi
 * (quản lý chi nhánh chỉ thấy khiếu nại của chi nhánh mình, quản lý chuỗi thấy mọi khiếu nại, kể cả ca đã leo thang). Mặc định là
 * khiếu nại cần xử lý (chờ nhận + đang xử lý), cũ nhất trước. Route không mở cho lễ tân (họ chỉ tạo thay khách ở bàn lễ tân).
 */
import { useEffect, useState } from 'react';
import { Inbox } from 'lucide-react';
import { formatVnDateTime } from '@/components/appointments/format';
import { Checkbox } from '@/components/admin/form-ui';
import { Pager } from '@/components/admin/Pager';
import { COMPLAINT_STATUS_LABELS, COMPLAINT_STATUS_TONE, COMPLAINT_TOPIC_LABELS } from '@/components/complaint/labels';
import { QueryError, RowsSkeleton, StatePanel } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { StatusBadge } from '@/components/ui/modal-templates';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { cn } from '@/lib/utils';
import { toApiError } from '@/shared/api/api-error';
import { usePublicBranches } from '@/shared/hooks/useAppointments';
import { useComplaints } from '@/shared/hooks/useComplaints';
import { useAccount } from '@/shared/stores/session.store';
import { COMPLAINT_STATUSES, type ComplaintStatus } from '@/shared/types/complaint';
import { StaffComplaintDialog } from './incidents/StaffComplaintDialog';

const PAGE_SIZE = 20;
/** Bộ lọc mặc định: vắng `status` = OPEN + IN_PROGRESS (hợp đồng #8). */
type Filter = ComplaintStatus | 'ACTIVE';

export function AdminIncidentsPage() {
  const role = useAccount()?.role;
  const canHandle = role === 'SUPER_MANAGER' || role === 'BRANCH_MANAGER';
  const [filter, setFilter] = useState<Filter>('ACTIVE');
  const [escalated, setEscalated] = useState(false);
  const [page, setPage] = useState(0);
  const [openId, setOpenId] = useState<number | null>(null);
  const list = useComplaints({ status: filter === 'ACTIVE' ? undefined : filter, escalated: escalated || undefined, page, size: PAGE_SIZE });
  const branches = usePublicBranches();
  const branchName = (branchId: number) => branches.data?.find((b) => b.branchId === branchId)?.name;
  const data = list.data;
  const stale = list.isPlaceholderData;

  // Xử lý nốt khiếu nại cuối của trang cuối thì trang đó trống: lùi về trang cuối còn dữ liệu.
  useEffect(() => {
    if (data && data.page > 0 && data.content.length === 0) setPage(Math.max(data.totalPages - 1, 0));
  }, [data]);

  let body;
  if (!data && list.isError) body = <QueryError error={toApiError(list.error)} onRetry={() => list.refetch()} what="khiếu nại" />;
  else if (!data) body = <RowsSkeleton rows={5} />;
  else if (data.content.length === 0) {
    body = <StatePanel icon={Inbox} title="Không có khiếu nại nào" body="Khiếu nại mới của khách sẽ xuất hiện ở đây; đổi bộ lọc để xem các trạng thái khác." />;
  } else {
    body = (
      <>
        <p role="status" className="border-b border-(--border-color) px-4 py-3 text-sm text-(--text-secondary)">
          {stale ? 'Đang tải…' : `${data.totalElements} khiếu nại`}
        </p>
        <table className={cn('table min-w-[820px]', stale && 'opacity-60')} aria-label="Danh sách khiếu nại" aria-busy={stale}>
          <thead>
            <tr className="[&>th]:whitespace-nowrap">
              <th>Mã</th>
              <th>Chủ đề</th>
              <th>Chi nhánh</th>
              <th>Trạng thái</th>
              <th>Hoạt động cuối</th>
              <th className="text-right!">Thao tác</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((c) => (
              <tr key={c.complaintId}>
                <td className="whitespace-nowrap font-semibold tabular-nums">{c.code}</td>
                <td>{COMPLAINT_TOPIC_LABELS[c.topic]}</td>
                <td>{c.branchId == null ? 'Không gắn chi nhánh' : (branchName(c.branchId) ?? `Chi nhánh #${c.branchId}`)}</td>
                <td className="whitespace-nowrap">
                  <span className="flex flex-wrap items-center gap-1.5">
                    <StatusBadge status={COMPLAINT_STATUS_LABELS[c.status]} variant={COMPLAINT_STATUS_TONE[c.status]} />
                    {c.escalated && <StatusBadge status="Quá hạn" variant="danger" />}
                  </span>
                </td>
                <td className="whitespace-nowrap text-sm text-(--text-secondary)">{formatVnDateTime(c.lastActivityAt)}</td>
                <td className="text-right">
                  <Button size="sm" variant="outline" disabled={stale} onClick={() => setOpenId(c.complaintId)} aria-label={`Xem khiếu nại ${c.code}`}>
                    Xem
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <Pager data={data} unit="khiếu nại" onPage={setPage} />
      </>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Khiếu nại</h1>
        <p className="text-(--text-secondary)">Khiếu nại của khách cần nhận, trả lời và kết luận. Khiếu nại quá hạn được đánh dấu để quản lý chuỗi theo dõi.</p>
      </div>
      <Card className="card">
        <CardContent className="flex flex-wrap items-center gap-4 p-4">
          <Tabs
            value={filter}
            onValueChange={(v) => {
              setFilter(v as Filter);
              setPage(0);
            }}
          >
            <TabsList aria-label="Lọc trạng thái">
              <TabsTrigger value="ACTIVE">Cần xử lý</TabsTrigger>
              {COMPLAINT_STATUSES.map((s) => (
                <TabsTrigger key={s} value={s}>
                  {COMPLAINT_STATUS_LABELS[s]}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>
          <Checkbox
            label="Chỉ khiếu nại quá hạn (đã leo thang)"
            checked={escalated}
            onChange={(v) => {
              setEscalated(v);
              setPage(0);
            }}
          />
        </CardContent>
      </Card>
      <Card className="card">
        <CardContent className="overflow-x-auto p-0">{body}</CardContent>
      </Card>
      {openId != null && <StaffComplaintDialog complaintId={openId} canHandle={canHandle} branchName={branchName} onClose={() => setOpenId(null)} />}
    </div>
  );
}
