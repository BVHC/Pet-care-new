import { useState } from 'react';
import { Field, FormError } from '@/components/admin/form-ui';
import { FormModal } from '@/components/ui/modal-templates';
import { cn } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useCancelCareTask, useCompleteCareTask } from '@/shared/hooks/useCareTasks';
import {
  CARE_TASK_LIMITS,
  CARE_TASK_RESULT_LABELS,
  CARE_TASK_RESULTS,
  type CareTask,
  type CareTaskResult,
} from '@/shared/types/care';

const textareaClass =
  'w-full rounded-lg border border-(--color-border-default) px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent';

/** Gọi cho ai, về thú nào, số nào: để lễ tân không nhầm việc khi hộp thoại mở ra. */
const whoToCall = (t: CareTask) => `${t.customerName} · ${t.petName} · ${t.phoneToCall ?? 'chưa có số điện thoại'}`;

interface DialogProps {
  task: CareTask;
  onClose: () => void;
  /** Đã lưu: trang đóng hộp thoại và báo kết quả. */
  onDone: (message: string) => void;
}

/** BR-TB-05: bắt buộc chọn kết quả (không chọn sẵn) và nhập ghi chú. Lỗi của máy chủ (BR-TB-05, 404, 409) hiện ngay trong hộp thoại. */
export function CompleteCareTaskDialog({ task, onClose, onDone }: DialogProps) {
  const [result, setResult] = useState<CareTaskResult | null>(null);
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const complete = useCompleteCareTask();

  const submit = async () => {
    if (!result) return setError('Chọn kết quả cuộc gọi.');
    if (!note.trim()) return setError('Nhập ghi chú cuộc gọi.');
    try {
      await complete.mutateAsync({ careTaskId: task.careTaskId, body: { result, note: note.trim() } });
      onDone(`Đã ghi kết quả cuộc gọi: ${task.customerName} (${task.petName}).`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title="Ghi kết quả cuộc gọi"
      description={whoToCall(task)}
      onSubmit={submit}
      submitText="Lưu kết quả"
      loading={complete.isPending}
    >
      <fieldset>
        <legend className="mb-1.5 text-sm font-medium text-(--text-primary)">Kết quả cuộc gọi</legend>
        <div className="grid gap-2 sm:grid-cols-2">
          {CARE_TASK_RESULTS.map((r) => (
            <label
              key={r}
              className={cn(
                'flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2.5 text-sm text-(--text-primary)',
                result === r ? 'border-(--ws-accent) bg-(--ws-accent-soft)' : 'border-(--color-border-default) hover:bg-(--bg-secondary)',
              )}
            >
              <input
                type="radio"
                name="care-task-result"
                value={r}
                checked={result === r}
                onChange={() => {
                  setResult(r);
                  setError('');
                }}
                className="h-4 w-4 accent-[#a43324]"
              />
              {CARE_TASK_RESULT_LABELS[r]}
            </label>
          ))}
        </div>
      </fieldset>
      <Field label="Ghi chú" hint={`${note.length}/${CARE_TASK_LIMITS.note}`}>
        {(id) => (
          <textarea
            id={id}
            rows={3}
            maxLength={CARE_TASK_LIMITS.note}
            className={textareaClass}
            placeholder="Khách nói gì, hẹn lịch nào…"
            value={note}
            onChange={(e) => {
              setNote(e.target.value);
              setError('');
            }}
          />
        )}
      </Field>
      <FormError message={error} />
    </FormModal>
  );
}

/** Lý do hủy bắt buộc, tối đa 300 ký tự (care-v1 CancelCareTaskRequest). */
export function CancelCareTaskDialog({ task, onClose, onDone }: DialogProps) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const cancel = useCancelCareTask();

  const submit = async () => {
    if (!reason.trim()) return setError('Nhập lý do hủy.');
    try {
      await cancel.mutateAsync({ careTaskId: task.careTaskId, body: { reason: reason.trim() } });
      onDone(`Đã hủy việc gọi điện của ${task.customerName} (${task.petName}).`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title="Hủy việc gọi điện"
      description={whoToCall(task)}
      onSubmit={submit}
      submitText="Hủy việc này"
      cancelText="Quay lại"
      loading={cancel.isPending}
    >
      <Field label="Lý do hủy" hint={`${reason.length}/${CARE_TASK_LIMITS.reason}`}>
        {(id) => (
          <textarea
            id={id}
            rows={3}
            maxLength={CARE_TASK_LIMITS.reason}
            className={textareaClass}
            placeholder="Vì sao không cần gọi nữa?"
            value={reason}
            onChange={(e) => {
              setReason(e.target.value);
              setError('');
            }}
          />
        )}
      </Field>
      <FormError message={error} />
    </FormModal>
  );
}
