import { useState, type FormEvent, type ReactNode } from 'react';
import { Field, FormError } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { WORKFORCE_LIMITS } from '@/shared/types/workforce';

export const textareaClass =
  'w-full rounded-lg border border-(--color-border-default) bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent';

interface FormDialogProps {
  title: string;
  description?: string;
  submitText: string;
  pending: boolean;
  /** Lỗi hiện ngay trong hộp (lỗi kiểm tra ở FE hoặc lỗi BR của máy chủ). */
  error: string;
  onSubmit: () => void;
  onClose: () => void;
  children: ReactNode;
}

/** Khung hộp thoại Radix có biểu mẫu: Enter hoặc nút lưu đều gọi `onSubmit`; đang lưu thì khóa cả hai nút. */
export function FormDialog({ title, description, submitText, pending, error, onSubmit, onClose, children }: FormDialogProps) {
  const submit = (e: FormEvent) => {
    e.preventDefault();
    onSubmit();
  };
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto bg-white sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </DialogHeader>
        <form className="space-y-4" onSubmit={submit}>
          {children}
          <FormError message={error} />
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose} disabled={pending}>
              Hủy
            </Button>
            <Button type="submit" disabled={pending}>
              {pending ? 'Đang lưu…' : submitText}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

interface ReasonDialogProps {
  title: string;
  description?: string;
  label: string;
  submitText: string;
  /** Gọi API; ném lỗi thì hộp giữ nguyên và hiện lỗi. */
  onConfirm: (reason: string) => Promise<unknown>;
  onClose: () => void;
  onDone: () => void;
}

/** Hộp nhập một lý do bắt buộc (≤ 300 ký tự): hủy phân ca, ghi vắng, từ chối yêu cầu. */
export function ReasonDialog({ title, description, label, submitText, onConfirm, onClose, onDone }: ReasonDialogProps) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);

  const submit = async () => {
    if (!reason.trim()) return setError(`Nhập ${label.toLowerCase()}.`);
    setPending(true);
    try {
      await onConfirm(reason.trim());
      onDone();
    } catch (e) {
      setError(plainMessage(toApiError(e)));
      setPending(false);
    }
  };

  return (
    <FormDialog title={title} description={description} submitText={submitText} pending={pending} error={error} onSubmit={submit} onClose={onClose}>
      <Field label={label} hint={`${reason.length}/${WORKFORCE_LIMITS.reason}`}>
        {(id) => (
          <textarea
            id={id}
            rows={3}
            maxLength={WORKFORCE_LIMITS.reason}
            className={textareaClass}
            value={reason}
            onChange={(e) => {
              setReason(e.target.value);
              setError('');
            }}
          />
        )}
      </Field>
    </FormDialog>
  );
}
