import { useState } from 'react';
import { FormError } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { COMPLAINT_LIMITS } from '@/shared/types/complaint';

const textareaClass =
  'w-full rounded-lg border border-(--color-border-default) bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent';

interface Props {
  /** Tên truy cập của ô nhập. */
  label: string;
  submitText: string;
  placeholder?: string;
  onSend: (text: string) => Promise<unknown>;
  /** Gửi xong (đã xóa chữ): để cha đóng ô mở lại / báo thành công. */
  onSent?: () => void;
  onCancel?: () => void;
}

/**
 * Ô nhập một đoạn chữ rồi gửi: tin nhắn, lý do mở lại, kết luận. Lỗi của máy chủ (409 đã đóng, 400 BR-DG-07…) hiện ngay dưới ô
 * và giữ nguyên chữ đã gõ; độ dài tối thiểu / tối đa do máy chủ kiểm.
 */
export function MessageComposer({ label, submitText, placeholder, onSend, onSent, onCancel }: Props) {
  const [text, setText] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    if (!text.trim()) return setError('Nhập nội dung trước khi gửi.');
    setBusy(true);
    try {
      await onSend(text.trim());
      setText('');
      setError('');
      onSent?.();
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form
      className="space-y-2"
      onSubmit={(e) => {
        e.preventDefault();
        void submit();
      }}
    >
      <textarea
        aria-label={label}
        rows={3}
        maxLength={COMPLAINT_LIMITS.body}
        className={textareaClass}
        placeholder={placeholder}
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          setError('');
        }}
      />
      <FormError message={error} />
      <div className="flex justify-end gap-2">
        {onCancel && (
          <Button type="button" variant="outline" size="sm" onClick={onCancel} disabled={busy}>
            Hủy
          </Button>
        )}
        <Button type="submit" size="sm" disabled={busy}>
          {busy ? 'Đang gửi…' : submitText}
        </Button>
      </div>
    </form>
  );
}
