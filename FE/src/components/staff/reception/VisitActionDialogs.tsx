// Hộp thoại thao tác trên một lượt ở hàng đợi: gán (UC45), đưa lên trước (BR-TN-04), hủy lượt khách bỏ về (BR-TN-07), gán lại lượt đã gọi (BR-TN-08).
// Lỗi hiện ngay trong hộp thoại (còn mở để sửa); thành công báo bằng toast kèm mã lượt.
import { useId, useState, type ReactNode } from 'react';
import { toast } from 'sonner';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '../../ui/dialog';
import { useAssignVisit, useCancelVisit, useMoveVisit, useReassignVisit, visitErrorMessage } from '../../../shared/hooks/useVisits';
import type { Visit } from '../../../shared/types/visit';
import { WsButton } from '../ui';
import { AssigneePicker } from './AssigneePicker';

const MAX_REASON = 300;
const field = 'w-full rounded-lg border border-(--ws-line-strong) bg-(--ws-surface) px-3 text-sm text-(--ws-ink)';

function ActionDialog({ title, description, onClose, children }: { title: string; description: ReactNode; onClose: () => void; children: ReactNode }) {
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="ws-portal max-h-[90dvh] overflow-y-auto border-(--ws-line) bg-(--ws-surface) text-(--ws-ink) sm:max-w-md">
        <div>
          <DialogTitle className="text-lg font-bold">{title}</DialogTitle>
          <DialogDescription className="text-sm text-(--ws-ink-2)">{description}</DialogDescription>
        </div>
        {children}
      </DialogContent>
    </Dialog>
  );
}

function Problem({ text }: { text: string | null }) {
  return text ? (
    <p role="alert" className="text-sm font-medium text-(--ws-urgent-ink)">
      {text}
    </p>
  ) : null;
}

function Footer({ onClose, closeLabel = 'Đóng', children }: { onClose: () => void; closeLabel?: string; children: ReactNode }) {
  return (
    <div className="flex justify-end gap-2 border-t border-(--ws-line) pt-4">
      <WsButton variant="ghost" onClick={onClose}>
        {closeLabel}
      </WsButton>
      {children}
    </div>
  );
}

const who = (v: Visit) => `${v.petName} (${v.code})`;

/** #5: lượt chưa gọi; gán lại cho người khác bất kỳ lúc nào trước khi gọi (BR-TN-08). */
export function AssignVisitDialog({ visit, onClose }: { visit: Visit; onClose: () => void }) {
  const assign = useAssignVisit();
  const [picked, setPicked] = useState<number | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const submit = () => {
    if (picked === null || assign.isPending) return;
    setProblem(null);
    assign.mutate(
      { visitId: visit.visitId, assigneeId: picked },
      {
        onSuccess: ({ visit: done, assigneeOffline }) => {
          const name = done.assigneeName ?? 'nhân viên';
          // BR-TN-06: ngoại tuyến chỉ là cảnh báo, lượt đã được gán.
          if (assigneeOffline) toast.warning(`Đã gán ${who(done)} cho ${name}, nhưng ${name} đang ngoại tuyến.`);
          else toast.success(`Đã gán ${who(done)} cho ${name}.`);
          onClose();
        },
        onError: (e) => setProblem(visitErrorMessage(e, 'assign')),
      },
    );
  };

  return (
    <ActionDialog title="Gán người phụ trách" description={`Lượt ${who(visit)}. Người được gán gọi lượt này từ hàng đợi của mình.`} onClose={onClose}>
      <AssigneePicker group={visit.serviceGroup} value={picked} onChange={setPicked} />
      <Problem text={problem} />
      <Footer onClose={onClose}>
        <WsButton variant="primary" disabled={picked === null || assign.isPending} onClick={submit}>
          {assign.isPending ? 'Đang gán…' : 'Gán'}
        </WsButton>
      </Footer>
    </ActionDialog>
  );
}

/** #9: lượt đã gọi mà người phụ trách không còn đủ điều kiện; lý do bắt buộc 1–300 ký tự (BR-TN-08). */
export function ReassignVisitDialog({ visit, onClose }: { visit: Visit; onClose: () => void }) {
  const reassign = useReassignVisit();
  const reasonId = useId();
  const [picked, setPicked] = useState<number | null>(null);
  const [reason, setReason] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const trimmed = reason.trim();

  const submit = () => {
    if (picked === null || !trimmed || reassign.isPending) return;
    setProblem(null);
    reassign.mutate(
      { visitId: visit.visitId, assigneeId: picked, reason: trimmed },
      {
        onSuccess: (done) => {
          toast.success(`Đã gán lại ${who(done)} cho ${done.assigneeName ?? 'nhân viên'}.`);
          onClose();
        },
        onError: (e) => setProblem(visitErrorMessage(e, 'reassign')),
      },
    );
  };

  return (
    <ActionDialog
      title="Gán lại lượt đã gọi"
      description={`Lượt ${who(visit)} đang do ${visit.assigneeName ?? 'người phụ trách cũ'} phụ trách nhưng người này không còn đủ điều kiện. Bệnh án đã ghi được giữ nguyên.`}
      onClose={onClose}
    >
      <AssigneePicker group={visit.serviceGroup} value={picked} onChange={setPicked} />
      <div className="flex flex-col gap-2">
        <label htmlFor={reasonId} className="text-[13px] font-semibold">
          Lý do gán lại (bắt buộc)
        </label>
        <textarea id={reasonId} rows={2} maxLength={MAX_REASON} value={reason} onChange={(e) => setReason(e.target.value)} className={`${field} py-2`} />
      </div>
      <Problem text={problem} />
      <Footer onClose={onClose}>
        <WsButton variant="primary" disabled={picked === null || !trimmed || reassign.isPending} onClick={submit}>
          {reassign.isPending ? 'Đang gán lại…' : 'Xác nhận gán lại'}
        </WsButton>
      </Footer>
    </ActionDialog>
  );
}

/** #6: đặt lượt ngay trước một lượt đứng trên nó (đích do `moveTargets` chọn sẵn, mặc định đầu hàng). */
export function MoveVisitDialog({ visit, targets, onClose }: { visit: Visit; targets: Visit[]; onClose: () => void }) {
  const move = useMoveVisit();
  const targetId = useId();
  const [target, setTarget] = useState<number>(targets[0].visitId);
  const [problem, setProblem] = useState<string | null>(null);

  const submit = () => {
    if (move.isPending) return;
    setProblem(null);
    move.mutate(
      { visitId: visit.visitId, placeBeforeVisitId: target },
      {
        onSuccess: () => {
          toast.success(`Đã đưa ${who(visit)} lên trước.`);
          onClose();
        },
        onError: (e) => setProblem(visitErrorMessage(e, 'move')),
      },
    );
  };

  return (
    <ActionDialog title="Đưa lượt lên trước" description={`Lượt ${who(visit)} sẽ đứng ngay trước lượt bạn chọn (BR-TN-04).`} onClose={onClose}>
      <div className="flex flex-col gap-2">
        <label htmlFor={targetId} className="text-[13px] font-semibold">
          Đứng ngay trước lượt
        </label>
        <select id={targetId} value={target} onChange={(e) => setTarget(Number(e.target.value))} className={`${field} h-10`}>
          {targets.map((t) => (
            <option key={t.visitId} value={t.visitId}>
              {t.code} · {t.petName}
            </option>
          ))}
        </select>
      </div>
      <Problem text={problem} />
      <Footer onClose={onClose}>
        <WsButton variant="primary" disabled={move.isPending} onClick={submit}>
          {move.isPending ? 'Đang sắp lại…' : 'Xác nhận đưa lên trước'}
        </WsButton>
      </Footer>
    </ActionDialog>
  );
}

/** #11: chỉ lượt WAITING (BR-TN-07). Không nhập lý do thì không gửi body (A4). */
export function CancelVisitDialog({ visit, onClose }: { visit: Visit; onClose: () => void }) {
  const cancel = useCancelVisit();
  const reasonId = useId();
  const [reason, setReason] = useState('');
  const [problem, setProblem] = useState<string | null>(null);

  const submit = () => {
    if (cancel.isPending) return;
    setProblem(null);
    cancel.mutate(
      { visitId: visit.visitId, reason: reason.trim() || undefined },
      {
        onSuccess: () => {
          toast.success(`Đã hủy lượt ${who(visit)}.`);
          onClose();
        },
        onError: (e) => setProblem(visitErrorMessage(e, 'cancel')),
      },
    );
  };

  return (
    <ActionDialog
      title="Hủy lượt khách bỏ về"
      description={`Lượt ${who(visit)} bị hủy. Order của lượt và lịch hẹn liên quan (nếu có) sẽ bị hủy theo. Hủy theo cách này không tính là hủy muộn của khách (BR-TN-07).`}
      onClose={onClose}
    >
      <div className="flex flex-col gap-2">
        <label htmlFor={reasonId} className="text-[13px] font-semibold">
          Lý do (không bắt buộc)
        </label>
        <textarea id={reasonId} rows={2} maxLength={MAX_REASON} value={reason} onChange={(e) => setReason(e.target.value)} className={`${field} py-2`} />
        <span className="ws-num self-end text-xs text-(--ws-ink-3)">
          {reason.length}/{MAX_REASON}
        </span>
      </div>
      <Problem text={problem} />
      <Footer onClose={onClose} closeLabel="Giữ lại">
        <WsButton variant="danger" disabled={cancel.isPending} onClick={submit}>
          {cancel.isPending ? 'Đang hủy…' : 'Xác nhận hủy lượt'}
        </WsButton>
      </Footer>
    </ActionDialog>
  );
}
