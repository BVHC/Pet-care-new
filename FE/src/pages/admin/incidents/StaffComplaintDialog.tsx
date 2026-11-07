import { useState } from 'react';
import { NoticeBar, type Notice } from '@/components/admin/form-ui';
import { ComplaintBody } from '@/components/complaint/ComplaintBody';
import { MessageComposer } from '@/components/complaint/MessageComposer';
import { QueryError, RowsSkeleton } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { complaintApi } from '@/shared/api/complaint.api';
import { useComplaint, useComplaintMutation } from '@/shared/hooks/useComplaints';

interface Props {
  complaintId: number;
  /** SUPER_MANAGER / BRANCH_MANAGER: nhận và kết luận (lễ tân chỉ trả lời). */
  canHandle: boolean;
  branchName: (branchId: number) => string | undefined;
  onClose: () => void;
}

type TextArgs = { id: number; body: string };

/**
 * Chi tiết + xử lý một khiếu nại (complaint-v1 #9–12). Nút theo trạng thái và vai: OPEN → Nhận xử lý; IN_PROGRESS → Kết luận;
 * chưa đóng → trả lời khách. Nhận / kết luận lệch trạng thái (người khác làm trước) là 409: lỗi hiện ngay, chi tiết tự tải lại.
 */
export function StaffComplaintDialog({ complaintId, canHandle, branchName, onClose }: Props) {
  const detail = useComplaint(complaintId);
  const c = detail.data;
  const [notice, setNotice] = useState<Notice | null>(null);
  const [concluding, setConcluding] = useState(false);
  const take = useComplaintMutation(complaintApi.takeComplaint);
  const reply = useComplaintMutation(({ id, body }: TextArgs) => complaintApi.postComplaintMessage(id, { body }));
  const resolve = useComplaintMutation(({ id, body }: TextArgs) => complaintApi.resolveComplaint(id, { resolution: body }));

  const takeIt = async () => {
    try {
      await take.mutateAsync(complaintId);
      setNotice({ kind: 'success', text: 'Bạn đã nhận xử lý khiếu nại này.' });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto bg-white sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{c ? `Khiếu nại ${c.code}` : 'Chi tiết khiếu nại'}</DialogTitle>
          <DialogDescription>Nhận xử lý, trao đổi với khách và đưa ra kết luận.</DialogDescription>
        </DialogHeader>
        {!c && detail.isError && <QueryError error={toApiError(detail.error)} onRetry={() => detail.refetch()} what="khiếu nại" />}
        {!c && !detail.isError && <RowsSkeleton rows={4} />}
        {c && (
          <div className="space-y-4">
            <NoticeBar notice={notice} />
            <ComplaintBody staffView complaint={c} branchName={c.branchId == null ? undefined : branchName(c.branchId)} />
            {c.status !== 'CLOSED' && (
              <MessageComposer
                label="Trả lời khách"
                placeholder="Nội dung gửi tới khách…"
                submitText="Gửi trả lời"
                onSend={(body) => reply.mutateAsync({ id: complaintId, body })}
              />
            )}
            {concluding && c.status === 'IN_PROGRESS' && (
              <MessageComposer
                label="Kết luận xử lý"
                placeholder="Kết quả xử lý, hướng giải quyết dành cho khách…"
                submitText="Lưu kết luận"
                onSend={(body) => resolve.mutateAsync({ id: complaintId, body })}
                onSent={() => setConcluding(false)}
                onCancel={() => setConcluding(false)}
              />
            )}
            <div className="flex flex-wrap justify-end gap-2">
              {canHandle && c.status === 'OPEN' && (
                <Button disabled={take.isPending} onClick={takeIt}>
                  Nhận xử lý
                </Button>
              )}
              {canHandle && c.status === 'IN_PROGRESS' && !concluding && (
                <Button onClick={() => setConcluding(true)}>Kết luận</Button>
              )}
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
