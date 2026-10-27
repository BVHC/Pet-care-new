// Bản bổ sung nhật ký (A4): 1–2000 ký tự, bất cứ lúc nào, bởi VET / CARETAKER chi nhánh. Chỉ thêm, không sửa / xóa.
import { useState, type FormEvent } from 'react';
import { toast } from 'sonner';
import { Field, FormError } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { fieldErrorsOf, plainMessage, toApiError } from '@/shared/api/api-error';
import { boardingApi } from '@/shared/api/boarding.api';
import { useBoardingMutation } from '@/shared/hooks/useBoarding';
import { footerClass, textareaClass } from './parts';
import { ADDENDUM_MAX, addendumError } from './rules';

export function AddendumForm({ careLogId, onDone, onCancel }: { careLogId: number; onDone: () => void; onCancel: () => void }) {
  const [content, setContent] = useState('');
  const [error, setError] = useState<string>();
  const [serverError, setServerError] = useState('');
  const add = useBoardingMutation((text: string) => boardingApi.addAddendum(careLogId, { content: text }));

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setServerError('');
    const found = addendumError(content);
    setError(found);
    if (found) return;
    add.mutate(content.trim(), {
      onSuccess: () => {
        toast.success('Đã thêm bản bổ sung.');
        onDone();
      },
      onError: (err) => {
        const apiError = toApiError(err);
        const fields = fieldErrorsOf(apiError);
        if (fields.content) setError(fields.content);
        else setServerError(plainMessage(apiError));
      },
    });
  };

  return (
    <form aria-label="Thêm bản bổ sung" onSubmit={submit} noValidate className="mt-3 space-y-3 rounded-xl border border-(--color-border-default) bg-(--color-surface-2) p-3">
      <Field label="Nội dung bổ sung" error={error} hint={`${content.trim().length}/${ADDENDUM_MAX}`}>
        {(id) => (
          <textarea
            id={id}
            rows={3}
            autoFocus
            className={textareaClass}
            value={content}
            aria-invalid={error ? true : undefined}
            onChange={(e) => {
              setContent(e.target.value);
              setError(undefined);
            }}
          />
        )}
      </Field>
      <p className="text-xs text-(--text-tertiary)">Bản bổ sung được lưu kèm tên người thêm và giờ; không sửa hoặc xóa được sau khi lưu.</p>
      <FormError message={serverError} />
      <div className={footerClass}>
        <Button type="button" variant="outline" onClick={onCancel} disabled={add.isPending}>
          Hủy
        </Button>
        <Button type="submit" disabled={add.isPending}>
          {add.isPending ? 'Đang xử lý…' : 'Lưu bản bổ sung'}
        </Button>
      </div>
    </form>
  );
}
