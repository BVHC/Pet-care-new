// Tạo / sửa chuồng (UC54, boarding-v1 #2, #3). Không kiểm độ dài hay bắt buộc ở trình duyệt: BE trả câu theo từng trường (`fieldErrorsOf`) và quy tắc BR-LT-01.
import { useState } from 'react';
import { toast } from 'sonner';
import { Field, FormError, selectClass } from '@/components/admin/form-ui';
import { Input } from '@/components/ui/input';
import { FormModal } from '@/components/ui/modal-templates';
import { fieldErrorsOf, plainMessage, toApiError } from '@/shared/api/api-error';
import { useRefreshBoarding } from '@/shared/hooks/useBoarding';
import type { CreateKennelRequest, Kennel } from '@/shared/types/boarding';
import { useCreateKennel, useUpdateKennel, type KennelTypeOption } from './hooks';

export type KennelFormMode = { kind: 'create' } | { kind: 'edit'; kennel: Kennel };

interface Props {
  mode: KennelFormMode;
  /** Loại lưu trú đang bật tại chi nhánh (chỉ dùng khi tạo; loại không đổi sau khi tạo). */
  types: KennelTypeOption[];
  onClose: () => void;
}

export function KennelFormDialog({ mode, types, onClose }: Props) {
  const editing = mode.kind === 'edit' ? mode.kennel : null;
  const create = useCreateKennel();
  const update = useUpdateKennel();
  const refresh = useRefreshBoarding();
  const [typeId, setTypeId] = useState(types.length === 1 ? String(types[0].kennelTypeId) : '');
  const [code, setCode] = useState(editing?.code ?? '');
  const [note, setNote] = useState(editing?.note ?? '');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState('');

  async function submit() {
    setErrors({});
    setFormError('');
    try {
      if (editing) {
        // Ghi chú rỗng = xóa (BE: bỏ trống / null mới là giữ nguyên), nên luôn gửi cả hai trường.
        const saved = await update.mutateAsync({ kennelId: editing.kennelId, body: { code: code.trim(), note: note.trim() } });
        toast.success(`Đã cập nhật chuồng ${saved.code}`);
      } else {
        // Chưa chọn loại thì không gửi trường: BE trả "Vui lòng chọn loại chuồng".
        const body = { kennelTypeId: typeId ? Number(typeId) : undefined, code: code.trim(), ...(note.trim() ? { note: note.trim() } : {}) };
        const saved = await create.mutateAsync(body as CreateKennelRequest);
        toast.success(`Đã tạo chuồng ${saved.code}`);
      }
      onClose();
    } catch (e) {
      const error = toApiError(e);
      const fields = fieldErrorsOf(error);
      if (Object.keys(fields).length) setErrors(fields);
      else setFormError(plainMessage(error));
      // 404 / 409: dữ liệu đã đổi sau khi trang tải, làm mới để hàng trong bảng đúng với BE.
      if (error.statusCode === 404 || error.statusCode === 409) void refresh();
    }
  }

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title={editing ? `Sửa chuồng ${editing.code}` : 'Tạo chuồng'}
      description={editing ? 'Chỉ đổi được mã và ghi chú; loại chuồng không đổi sau khi tạo.' : undefined}
      submitText={editing ? 'Lưu' : 'Tạo chuồng'}
      loading={create.isPending || update.isPending}
      onSubmit={submit}
    >
      {!editing && (
        <Field label="Loại chuồng" error={errors.kennelTypeId} hint="Chỉ gồm loại lưu trú đang bật tại chi nhánh.">
          {(id) => (
            <select id={id} className={selectClass} value={typeId} onChange={(e) => setTypeId(e.target.value)}>
              {types.length !== 1 && <option value="">Chọn loại chuồng</option>}
              {types.map((t) => (
                <option key={t.kennelTypeId} value={t.kennelTypeId}>
                  {t.spec ? `${t.name} (${t.spec})` : t.name}
                </option>
              ))}
            </select>
          )}
        </Field>
      )}
      <Field label="Mã chuồng" error={errors.code} hint="Bắt buộc, tối đa 20 ký tự, phân biệt hoa thường, không trùng trong chi nhánh.">
        {(id) => <Input id={id} value={code} onChange={(e) => setCode(e.target.value)} autoComplete="off" />}
      </Field>
      <Field label="Ghi chú" error={errors.note} hint={editing ? 'Tối đa 300 ký tự. Để trống để xóa ghi chú.' : 'Tối đa 300 ký tự.'}>
        {(id) => <textarea id={id} className="input min-h-20" rows={3} value={note} onChange={(e) => setNote(e.target.value)} />}
      </Field>
      {formError && <FormError message={formError} />}
    </FormModal>
  );
}
