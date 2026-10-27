// Ghi mới / sửa nhật ký (A7, A8, BR-LT-11, BR-LT-12). Ghi mới: chọn ngày, cờ bất thường (có bước xác nhận). Sửa: không có cờ và ngày (request không có).
import { ChevronDown, ChevronUp, Plus, Trash2, TriangleAlert } from 'lucide-react';
import { useId, useState, type FormEvent } from 'react';
import { toast } from 'sonner';
import { Field, FormError } from '@/components/admin/form-ui';
import { todayVn } from '@/components/appointments/format';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { fieldErrorsOf, plainMessage, toApiError } from '@/shared/api/api-error';
import { boardingApi } from '@/shared/api/boarding.api';
import { useBoardingMutation } from '@/shared/hooks/useBoarding';
import type { CareLog } from '@/shared/types/boarding';
import { footerClass, textareaClass } from './parts';
import {
  FIELD_LABELS,
  MAX_PHOTOS,
  TEXT_FIELDS,
  TEXT_MAX,
  draftOf,
  emptyDraft,
  photoError,
  toCreateRequest,
  toUpdateRequest,
  validateDraft,
  type CareLogDraft,
  type DraftErrors,
} from './rules';

type Props = { onDone: () => void; onCancel: () => void; onConflict: (message: string) => void } & (
  | { kind: 'create'; bookingId: number; today: string }
  | { kind: 'edit'; log: CareLog }
);

/** Tên trường BE mà biểu mẫu biết đặt lỗi vào; tên lạ thì hiện câu của BE ở dải lỗi. */
const FIELD_NAMES: readonly string[] = ['logDate', ...TEXT_FIELDS, 'note', 'photoUrls'];

export function CareLogForm(props: Props) {
  const creating = props.kind === 'create';
  const [draft, setDraft] = useState<CareLogDraft>(() => (props.kind === 'create' ? emptyDraft(props.today) : draftOf(props.log)));
  const [errors, setErrors] = useState<DraftErrors>({});
  const [serverError, setServerError] = useState('');
  const [confirming, setConfirming] = useState(false);
  const warningId = useId();
  const save = useBoardingMutation((d: CareLogDraft) =>
    props.kind === 'create' ? boardingApi.createCareLog(props.bookingId, toCreateRequest(d)) : boardingApi.updateCareLog(props.log.careLogId, toUpdateRequest(d)),
  );

  const clearError = (key: keyof DraftErrors) =>
    setErrors((prev) => {
      const next = { ...prev };
      delete next[key];
      delete next.form;
      return next;
    });
  const change = (patch: Partial<CareLogDraft>, errorKey?: keyof DraftErrors) => {
    setDraft((prev) => ({ ...prev, ...patch }));
    if (errorKey) clearError(errorKey);
  };
  const setPhotos = (photoUrls: string[]) => change({ photoUrls }, 'photoUrls');
  const movePhoto = (from: number, to: number) => {
    const next = [...draft.photoUrls];
    [next[from], next[to]] = [next[to], next[from]];
    setPhotos(next);
  };

  /** `confirmed`: người dùng đã bấm xác nhận bước "đánh dấu bất thường". */
  const attempt = (confirmed: boolean) => {
    setServerError('');
    const found = validateDraft(draft, { creating, today: todayVn() });
    setErrors(found);
    if (Object.keys(found).length > 0) {
      setConfirming(false);
      return;
    }
    if (creating && draft.isAbnormal && !confirmed) {
      setConfirming(true);
      return;
    }
    save.mutate(draft, {
      onSuccess: () => {
        toast.success(creating ? 'Đã ghi nhật ký chăm sóc.' : 'Đã cập nhật nhật ký chăm sóc.');
        props.onDone();
      },
      onError: (err) => {
        const apiError = toApiError(err);
        if (apiError.statusCode === 409) {
          // Ghi: thú không còn lưu trú. Sửa: quá hạn (BR-LT-11). Cha hiện câu của BE, làm mới và đổi chế độ.
          props.onConflict(plainMessage(apiError));
          return;
        }
        setConfirming(false);
        const fields = fieldErrorsOf(apiError);
        const names = Object.keys(fields);
        if (names.length > 0 && names.every((n) => FIELD_NAMES.includes(n))) setErrors(fields as DraftErrors);
        else setServerError(plainMessage(apiError));
      },
    });
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    attempt(false);
  };

  return (
    <form
      aria-label={creating ? 'Ghi nhật ký mới' : 'Sửa nhật ký'}
      onSubmit={submit}
      noValidate
      className="space-y-4 rounded-xl border border-(--color-border-default) bg-(--color-surface-2) p-3 sm:p-4"
    >
      {creating && (
        <Field label="Ngày nhật ký" error={errors.logDate} hint="Mặc định hôm nay (giờ Việt Nam); không chọn ngày tương lai.">
          {(id) => (
            <Input
              id={id}
              type="date"
              value={draft.logDate}
              max={props.kind === 'create' ? props.today : undefined}
              aria-invalid={errors.logDate ? true : undefined}
              onChange={(e) => change({ logDate: e.target.value }, 'logDate')}
            />
          )}
        </Field>
      )}

      <div className="grid gap-3 sm:grid-cols-2">
        {TEXT_FIELDS.map((field, i) => (
          <Field key={field} label={FIELD_LABELS[field]} error={errors[field]} hint={`${draft[field].trim().length}/${TEXT_MAX}`}>
            {(id) => (
              <Input
                id={id}
                autoFocus={i === 0}
                value={draft[field]}
                aria-invalid={errors[field] ? true : undefined}
                onChange={(e) => change({ [field]: e.target.value }, field)}
              />
            )}
          </Field>
        ))}
      </div>

      <Field label="Ghi chú" error={errors.note}>
        {(id) => <textarea id={id} rows={3} className={textareaClass} value={draft.note} onChange={(e) => change({ note: e.target.value }, 'note')} />}
      </Field>

      <fieldset className="space-y-2">
        <legend className="mb-1 text-sm font-medium text-(--text-primary)">Ảnh đính kèm</legend>
        <p className="text-xs text-(--text-tertiary)">Dán đường dẫn ảnh https (chưa tải ảnh lên được); tối đa {MAX_PHOTOS} ảnh.</p>
        {draft.photoUrls.map((url, i) => (
          <div key={i} className="flex flex-wrap items-center gap-1.5">
            <Input
              aria-label={`Đường dẫn ảnh ${i + 1}`}
              inputMode="url"
              placeholder="https://"
              value={url}
              aria-invalid={errors.photoUrls && photoError(url) ? true : undefined}
              onChange={(e) => setPhotos(draft.photoUrls.map((u, j) => (j === i ? e.target.value : u)))}
              className="basis-full sm:min-w-0 sm:flex-1 sm:basis-0"
            />
            <Button type="button" variant="outline" size="icon" aria-label={`Chuyển ảnh ${i + 1} lên`} disabled={i === 0} onClick={() => movePhoto(i, i - 1)}>
              <ChevronUp aria-hidden className="size-4" />
            </Button>
            <Button
              type="button"
              variant="outline"
              size="icon"
              aria-label={`Chuyển ảnh ${i + 1} xuống`}
              disabled={i === draft.photoUrls.length - 1}
              onClick={() => movePhoto(i, i + 1)}
            >
              <ChevronDown aria-hidden className="size-4" />
            </Button>
            <Button type="button" variant="outline" size="icon" aria-label={`Xóa ảnh ${i + 1}`} onClick={() => setPhotos(draft.photoUrls.filter((_, j) => j !== i))}>
              <Trash2 aria-hidden className="size-4" />
            </Button>
          </div>
        ))}
        {errors.photoUrls && <p className="text-xs font-medium text-red-600">{errors.photoUrls}</p>}
        <Button type="button" variant="outline" disabled={draft.photoUrls.length >= MAX_PHOTOS} onClick={() => setPhotos([...draft.photoUrls, ''])}>
          <Plus aria-hidden className="size-4" />
          Thêm ảnh
        </Button>
      </fieldset>

      {creating ? (
        <div className={cn('rounded-lg border p-3', draft.isAbnormal ? 'border-red-300 bg-red-50' : 'border-(--color-border-default) bg-white')}>
          <label className="flex min-h-10 cursor-pointer items-center gap-3 text-sm font-semibold text-(--text-primary)">
            <input
              type="checkbox"
              role="switch"
              checked={draft.isAbnormal}
              aria-describedby={warningId}
              onChange={(e) => {
                change({ isAbnormal: e.target.checked });
                setConfirming(false);
              }}
              className="peer sr-only"
            />
            <span
              aria-hidden
              className="relative h-6 w-11 shrink-0 rounded-full bg-gray-300 transition-colors after:absolute after:top-0.5 after:left-0.5 after:size-5 after:rounded-full after:bg-white after:shadow after:transition-transform peer-checked:bg-red-600 peer-checked:after:translate-x-5 peer-focus-visible:ring-2 peer-focus-visible:ring-red-600 peer-focus-visible:ring-offset-2"
            />
            <TriangleAlert aria-hidden className="size-4 shrink-0 text-red-600" />
            Đánh dấu bất thường
          </label>
          <p id={warningId} className="mt-1 text-xs text-(--text-secondary)">
            Khi bật, hệ thống thông báo ngay cho chủ thú và lễ tân chi nhánh (BR-LT-12). Nếu thú cần điều trị, phải liên hệ chủ thú trước khi phát sinh chi phí.
          </p>
        </div>
      ) : (
        props.kind === 'edit' &&
        props.log.isAbnormal && (
          <p className="text-xs text-(--text-secondary)">Mục này đã đánh dấu bất thường; cờ không đổi được ở đây (thêm bản bổ sung nếu cần).</p>
        )
      )}

      <FormError message={errors.form ?? ''} />
      <FormError message={serverError} />

      {confirming ? (
        <div className="space-y-3 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-900">
          <p className="font-semibold">Xác nhận đánh dấu bất thường</p>
          <p>Nhật ký được gửi ngay; chủ thú và lễ tân chi nhánh nhận thông báo tức thì và không thu hồi được. Chi phí điều trị (nếu có) chỉ phát sinh khi chủ thú đã đồng ý.</p>
          <div className={footerClass}>
            <Button type="button" variant="outline" className="bg-white" onClick={() => setConfirming(false)} disabled={save.isPending}>
              Quay lại chỉnh sửa
            </Button>
            <Button type="button" onClick={() => attempt(true)} disabled={save.isPending}>
              {save.isPending ? 'Đang xử lý…' : 'Xác nhận và gửi thông báo'}
            </Button>
          </div>
        </div>
      ) : (
        <div className={footerClass}>
          <Button type="button" variant="outline" onClick={props.onCancel} disabled={save.isPending}>
            Hủy
          </Button>
          <Button type="submit" disabled={save.isPending}>
            {save.isPending ? 'Đang xử lý…' : creating ? 'Lưu nhật ký' : 'Lưu thay đổi'}
          </Button>
        </div>
      )}
    </form>
  );
}
