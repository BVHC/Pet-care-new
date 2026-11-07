import { useState } from 'react';
import { NoticeBar, type Notice } from '@/components/admin/form-ui';
import { ComplaintBody } from '@/components/complaint/ComplaintBody';
import { MessageComposer } from '@/components/complaint/MessageComposer';
import { QueryError, RowsSkeleton } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { complaintApi } from '@/shared/api/complaint.api';
import { useComplaintMutation, useMyComplaint } from '@/shared/hooks/useComplaints';

interface Props {
  complaintId: number;
  branchName: (branchId: number) => string | undefined;
  onClose: () => void;
}

type TextArgs = { id: number; body: string };

/**
 * Chi tiết khiếu nại của tôi + trao đổi (complaint-v1 #3–6). Nút theo trạng thái: đang xử lý → rút; đã giải quyết → đóng / mở lại;
 * đã đóng → mở lại (trừ khi đã rút, BR-DG-07). Hạn 7 ngày và tối đa 2 lần mở lại do máy chủ kiểm.
 */
export function MyComplaintDialog({ complaintId, branchName, onClose }: Props) {
  const detail = useMyComplaint(complaintId);
  const c = detail.data;
  const [notice, setNotice] = useState<Notice | null>(null);
  const [withdrawing, setWithdrawing] = useState(false);
  const [reopening, setReopening] = useState(false);
  const post = useComplaintMutation(({ id, body }: TextArgs) => complaintApi.postMyComplaintMessage(id, { body }));
  const reopen = useComplaintMutation(({ id, body }: TextArgs) => complaintApi.reopenMyComplaint(id, { body }));
  const close = useComplaintMutation(complaintApi.closeMyComplaint);

  const closeIt = async (text: string) => {
    try {
      await close.mutateAsync(complaintId);
      setWithdrawing(false);
      setNotice({ kind: 'success', text });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  const busy = close.isPending;
  const canReopen = c && (c.status === 'RESOLVED' || (c.status === 'CLOSED' && c.closedReason !== 'WITHDRAWN'));
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto bg-white sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{c ? `Khiếu nại ${c.code}` : 'Chi tiết khiếu nại'}</DialogTitle>
          <DialogDescription>Theo dõi xử lý và trao đổi thêm với chi nhánh.</DialogDescription>
        </DialogHeader>
        {!c && detail.isError && <QueryError error={toApiError(detail.error)} onRetry={() => detail.refetch()} what="khiếu nại" />}
        {!c && !detail.isError && <RowsSkeleton rows={4} />}
        {c && (
          <div className="space-y-4">
            <NoticeBar notice={notice} />
            <ComplaintBody complaint={c} branchName={c.branchId == null ? undefined : branchName(c.branchId)} />
            {c.status !== 'CLOSED' && (
              <MessageComposer
                label="Tin nhắn cho chi nhánh"
                placeholder="Bổ sung thông tin hoặc trả lời chi nhánh…"
                submitText="Gửi tin nhắn"
                onSend={(body) => post.mutateAsync({ id: complaintId, body })}
              />
            )}
            {reopening && (
              <MessageComposer
                label="Lý do mở lại"
                placeholder="Vì sao bạn muốn mở lại khiếu nại?"
                submitText="Mở lại khiếu nại"
                onSend={(body) => reopen.mutateAsync({ id: complaintId, body })}
                onSent={() => setReopening(false)}
                onCancel={() => setReopening(false)}
              />
            )}
            {withdrawing && (
              <p role="alert" className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-900">
                Rút khiếu nại là kết thúc xử lý và không mở lại được. Bạn chắc chắn rút?
              </p>
            )}
            <div className="flex flex-wrap justify-end gap-2">
              {(c.status === 'OPEN' || c.status === 'IN_PROGRESS') && !withdrawing && (
                <Button variant="outline" onClick={() => setWithdrawing(true)}>
                  Rút khiếu nại
                </Button>
              )}
              {withdrawing && (
                <>
                  <Button variant="outline" disabled={busy} onClick={() => setWithdrawing(false)}>
                    Giữ lại
                  </Button>
                  <Button variant="destructive" disabled={busy} onClick={() => closeIt('Đã rút khiếu nại.')}>
                    Xác nhận rút
                  </Button>
                </>
              )}
              {c.status === 'RESOLVED' && (
                <Button disabled={busy} onClick={() => closeIt('Đã đóng khiếu nại. Cảm ơn bạn đã phản hồi.')}>
                  Đóng khiếu nại
                </Button>
              )}
              {canReopen && !reopening && (
                <Button variant="outline" onClick={() => setReopening(true)}>
                  Mở lại
                </Button>
              )}
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
