// Khung hộp thoại dùng chung cho thao tác lâm sàng tầng 3 (nội trú): Radix Dialog, form một nút gửi, lỗi của BE hiện ngay trong hộp.
import type { FormEvent, ReactNode } from 'react';
import { FormError } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';

export const textareaClass = 'w-full rounded-lg border border-(--color-border-default) bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent';
export const inputClass = 'h-10 w-full rounded-lg border border-(--color-border-default) bg-white px-3 text-sm focus:outline-none focus:ring-2 focus:ring-accent';

interface Props {
  title: string;
  description?: ReactNode;
  onClose: () => void;
  onSubmit: () => void;
  submitText: string;
  cancelText?: string;
  pending?: boolean;
  /** Lỗi từ BE hoặc từ kiểm tra tại chỗ. */
  error?: string;
  /** Nút gửi tắt (vd chưa chọn gì). */
  submitDisabled?: boolean;
  destructive?: boolean;
  wide?: boolean;
  children: ReactNode;
}

export function ClinicalDialog({ title, description, onClose, onSubmit, submitText, cancelText = 'Đóng', pending, error = '', submitDisabled, destructive, wide, children }: Props) {
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!pending) onSubmit();
  };
  return (
    <Dialog open onOpenChange={(open) => !open && !pending && onClose()}>
      <DialogContent className={`max-h-[90dvh] overflow-y-auto bg-white ${wide ? 'sm:max-w-2xl' : 'sm:max-w-lg'}`}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </DialogHeader>
        <form onSubmit={submit} noValidate className="space-y-4">
          {children}
          <FormError message={error} />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" onClick={onClose} disabled={pending}>
              {cancelText}
            </Button>
            <Button type="submit" variant={destructive ? 'destructive' : 'default'} disabled={pending || submitDisabled}>
              {pending ? 'Đang gửi…' : submitText}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
