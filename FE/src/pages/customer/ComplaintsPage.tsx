/**
 * Khiếu nại của tôi (UC85) — complaint-v1 #1–6. Danh sách (lọc theo trạng thái) → bấm một khiếu nại để xem, nhắn thêm, đóng / rút / mở lại;
 * "Gửi khiếu nại" mở biểu mẫu. Chi tiết là hộp thoại vì route chỉ có /complaints.
 */
import { useState } from 'react';
import { MessageSquareWarning, Plus } from 'lucide-react';
import { AccountSidebar } from '@/components/customer/AccountSidebar';
import { formatVnDateTime } from '@/components/appointments/format';
import { NoticeBar, type Notice } from '@/components/admin/form-ui';
import { Pager } from '@/components/admin/Pager';
import { COMPLAINT_STATUS_LABELS, COMPLAINT_STATUS_TONE, COMPLAINT_TOPIC_LABELS } from '@/components/complaint/labels';
import { QueryError, RowsSkeleton } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { StatusBadge } from '@/components/ui/modal-templates';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { toApiError } from '@/shared/api/api-error';
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero';
import { usePublicBranches } from '@/shared/hooks/useAppointments';
import { useMyComplaints } from '@/shared/hooks/useComplaints';
import { COMPLAINT_STATUSES, type ComplaintStatus } from '@/shared/types/complaint';
import { MyComplaintDialog } from './complaints/MyComplaintDialog';
import { NewComplaintDialog } from './complaints/NewComplaintDialog';

const PAGE_SIZE = 10;

export function ComplaintsPage() {
  const [status, setStatus] = useState<ComplaintStatus | 'ALL'>('ALL');
  const [page, setPage] = useState(0);
  const [openId, setOpenId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const list = useMyComplaints({ status: status === 'ALL' ? undefined : status, page, size: PAGE_SIZE });
  const branches = usePublicBranches();
  const branchName = (branchId: number) => branches.data?.find((b) => b.branchId === branchId)?.name;
  const data = list.data;

  let body;
  if (!data && list.isError) body = <QueryError error={toApiError(list.error)} onRetry={() => list.refetch()} what="khiếu nại" />;
  else if (!data) body = <RowsSkeleton rows={3} />;
  else if (data.content.length === 0) {
    body = (
      <div className="rounded-3xl border border-dashed border-(--color-border-default) bg-white p-12 text-center">
        <MessageSquareWarning size={44} className="mx-auto mb-3 text-(--color-text-secondary) opacity-30" aria-hidden />
        <p className="font-bold text-(--color-text-secondary)">{status !== 'ALL' ? 'Không có khiếu nại ở trạng thái này' : 'Bạn chưa gửi khiếu nại nào'}</p>
        {status === 'ALL' && <p className="mt-1.5 text-sm text-(--color-text-secondary)">Chọn “Gửi khiếu nại” khi bạn chưa hài lòng với dịch vụ.</p>}
      </div>
    );
  } else {
    body = (
      <>
        <ul aria-label="Danh sách khiếu nại" className="space-y-3">
          {data.content.map((c) => (
            <li key={c.complaintId}>
              <button
                type="button"
                onClick={() => setOpenId(c.complaintId)}
                aria-label={`Xem khiếu nại ${c.code}`}
                className="flex w-full flex-wrap items-center justify-between gap-3 rounded-2xl border border-(--color-border-default) bg-white p-4 text-left transition-colors hover:border-accent"
              >
                <span className="min-w-0">
                  <span className="block text-xs font-semibold tabular-nums text-(--color-text-secondary)">{c.code}</span>
                  <span className="block font-bold text-(--color-text-primary)">{COMPLAINT_TOPIC_LABELS[c.topic]}</span>
                  <span className="block text-xs text-(--color-text-secondary)">
                    {c.branchId == null ? 'Không gắn chi nhánh' : (branchName(c.branchId) ?? `Chi nhánh #${c.branchId}`)} · cập nhật {formatVnDateTime(c.lastActivityAt)}
                  </span>
                </span>
                <StatusBadge status={COMPLAINT_STATUS_LABELS[c.status]} variant={COMPLAINT_STATUS_TONE[c.status]} />
              </button>
            </li>
          ))}
        </ul>
        <Pager data={data} unit="khiếu nại" onPage={setPage} />
      </>
    );
  }

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero eyebrow="Hỗ trợ" title="Khiếu nại của tôi" subtitle="Gửi và theo dõi khiếu nại về dịch vụ, đơn hàng, lưu trú." breadcrumbs={[{ label: 'Khiếu nại' }]} />
      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className="grid gap-8 lg:grid-cols-[260px_minmax(0,1fr)] xl:grid-cols-[280px_minmax(0,1fr)]">
          <div className="min-w-0">
            <AccountSidebar active="complaints" />
          </div>
          <div className="min-w-0 space-y-5">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <Tabs
                value={status}
                onValueChange={(v) => {
                  setStatus(v as ComplaintStatus | 'ALL');
                  setPage(0);
                }}
              >
                <TabsList aria-label="Lọc trạng thái">
                  <TabsTrigger value="ALL">Tất cả</TabsTrigger>
                  {COMPLAINT_STATUSES.map((s) => (
                    <TabsTrigger key={s} value={s}>
                      {COMPLAINT_STATUS_LABELS[s]}
                    </TabsTrigger>
                  ))}
                </TabsList>
              </Tabs>
              <Button onClick={() => setCreating(true)}>
                <Plus className="h-4 w-4" aria-hidden /> Gửi khiếu nại
              </Button>
            </div>
            <NoticeBar notice={notice} />
            {body}
          </div>
        </div>
      </div>
      {creating && (
        <NewComplaintDialog
          branches={branches.data ?? []}
          onClose={() => setCreating(false)}
          onCreated={(c) => {
            setCreating(false);
            setNotice({ kind: 'success', text: `Đã gửi khiếu nại ${c.code}. Chi nhánh sẽ phản hồi bạn sớm.` });
          }}
        />
      )}
      {openId != null && <MyComplaintDialog complaintId={openId} branchName={branchName} onClose={() => setOpenId(null)} />}
    </div>
  );
}
