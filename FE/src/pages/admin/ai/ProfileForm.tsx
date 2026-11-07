import { useState } from 'react';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { Modal, ModalBody, ModalFooter, ModalHeader, ModalTitle } from '../../../components/ui/modal-templates';
import { Field, FormError, selectClass } from '../../../components/admin/form-ui';
import { aiApi } from '../../../shared/api/ai.api';
import { fieldErrorsOf, plainMessage, toApiError, type ApiError } from '../../../shared/api/api-error';
import { useAiMutation } from '../../../shared/hooks/useAi';
import { PROFILE_SPECIES, type ItemProfile, type ItemProfileRow, type ProfileSpecies } from '../../../shared/types/ai';
import { PROFILE_SPECIES_LABELS } from './labels';

interface Draft {
  species: ProfileSpecies | '';
  minAgeWeeks: string;
  maxAgeWeeks: string;
  minWeightKg: string;
  maxWeightKg: string;
  breedKeywords: string;
  repeatIntervalDays: string;
}
type Errors = Partial<Record<keyof Draft, string>>;
const NUMBERS = ['minAgeWeeks', 'maxAgeWeeks', 'minWeightKg', 'maxWeightKg', 'repeatIntervalDays'] as const;

const draftOf = (p?: ItemProfile | null): Draft => ({
  species: p?.species ?? '',
  minAgeWeeks: p?.minAgeWeeks?.toString() ?? '',
  maxAgeWeeks: p?.maxAgeWeeks?.toString() ?? '',
  minWeightKg: p?.minWeightKg?.toString() ?? '',
  maxWeightKg: p?.maxWeightKg?.toString() ?? '',
  breedKeywords: p?.breedKeywords.join(', ') ?? '',
  repeatIntervalDays: p?.repeatIntervalDays?.toString() ?? '',
});

/** Ghi đè toàn bộ (#13): trường trống gửi `null`, từ khóa luôn là mảng; toàn bộ trống = xóa hồ sơ (BR-AI-20). */
function bodyOf(d: Draft): ItemProfile {
  const num = (s: string) => (s.trim() === '' ? null : Number(s));
  return {
    species: d.species || null,
    minAgeWeeks: num(d.minAgeWeeks),
    maxAgeWeeks: num(d.maxAgeWeeks),
    minWeightKg: num(d.minWeightKg),
    maxWeightKg: num(d.maxWeightKg),
    breedKeywords: d.breedKeywords.split(',').map((k) => k.trim()).filter(Boolean),
    repeatIntervalDays: num(d.repeatIntervalDays),
  };
}

/** UC94: hồ sơ gợi ý của một dịch vụ thẩm mỹ / sản phẩm. Lệch min > max do BE từ chối và hiện ngay dưới ô tương ứng. */
export function ProfileForm({ row, onClose, onSaved }: { row: ItemProfileRow; onClose: () => void; onSaved: (text: string) => void }) {
  const [draft, setDraft] = useState(() => draftOf(row.profile));
  const [errors, setErrors] = useState<Errors>({});
  const [failure, setFailure] = useState<ApiError | null>(null);
  const save = useAiMutation((body: ItemProfile) => aiApi.saveItemProfile(row.itemType, row.itemId, body));
  const set = (change: Partial<Draft>) => {
    setDraft((d) => ({ ...d, ...change }));
    setErrors((e) => Object.fromEntries(Object.entries(e).filter(([key]) => !(key in change))));
  };

  const submit = async (body: ItemProfile, done: string) => {
    setFailure(null);
    try {
      await save.mutateAsync(body);
      onSaved(done);
    } catch (e) {
      setFailure(toApiError(e));
    }
  };

  const onSubmit = () => {
    const found: Errors = {};
    for (const key of NUMBERS) if (draft[key].trim() !== '' && !Number.isFinite(Number(draft[key]))) found[key] = 'Nhập số.';
    setErrors(found);
    if (!Object.keys(found).length) void submit(bodyOf(draft), `Đã lưu hồ sơ gợi ý của “${row.name}”.`);
  };

  const remote = failure ? fieldErrorsOf(failure) : {};
  const shown: Errors = { ...errors, ...remote };
  const generic = failure && Object.keys(remote).length === 0 ? plainMessage(failure) : '';
  const numberField = (key: (typeof NUMBERS)[number], label: string, hint?: string) => (
    <Field label={label} error={shown[key]} hint={hint}>
      {(id) => <Input id={id} inputMode="decimal" value={draft[key]} onChange={(e) => set({ [key]: e.target.value })} />}
    </Field>
  );

  return (
    <Modal open onOpenChange={(open) => !open && onClose()} className="max-w-xl">
      <ModalHeader onClose={onClose}>
        <ModalTitle>Hồ sơ gợi ý: {row.name}</ModalTitle>
      </ModalHeader>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          onSubmit();
        }}
      >
        <ModalBody className="space-y-4">
          <p className="text-sm text-(--text-secondary)">Để trống một thuộc tính nghĩa là không giới hạn. Bỏ trống tất cả thì mục trở về điểm trung tính “Phù hợp chung”.</p>
          <Field label="Loài" error={shown.species}>
            {(id) => (
              <select id={id} className={selectClass} value={draft.species} onChange={(e) => set({ species: e.target.value as ProfileSpecies | '' })}>
                <option value="">Mọi loài</option>
                {PROFILE_SPECIES.map((s) => (
                  <option key={s} value={s}>
                    {PROFILE_SPECIES_LABELS[s]}
                  </option>
                ))}
              </select>
            )}
          </Field>
          <div className="grid gap-4 sm:grid-cols-2">
            {numberField('minAgeWeeks', 'Tuổi tối thiểu (tuần)')}
            {numberField('maxAgeWeeks', 'Tuổi tối đa (tuần)')}
            {numberField('minWeightKg', 'Cân nặng tối thiểu (kg)')}
            {numberField('maxWeightKg', 'Cân nặng tối đa (kg)')}
          </div>
          <Field label="Từ khóa giống" error={shown.breedKeywords} hint="Ngăn cách bằng dấu phẩy; hệ thống đổi sang chữ thường không dấu. Trống = mọi giống.">
            {(id) => <Input id={id} value={draft.breedKeywords} onChange={(e) => set({ breedKeywords: e.target.value })} />}
          </Field>
          {numberField('repeatIntervalDays', 'Chu kỳ dùng lại (ngày)', 'Trống = dùng mặc định cấu hình.')}
          <FormError message={generic} />
        </ModalBody>
        <ModalFooter>
          {row.hasProfile && (
            <Button type="button" variant="outline" className="mr-auto text-red-600" disabled={save.isPending} onClick={() => void submit(bodyOf(draftOf()), `Đã xóa hồ sơ gợi ý của “${row.name}”.`)}>
              Xóa hồ sơ
            </Button>
          )}
          <Button type="button" variant="outline" onClick={onClose} disabled={save.isPending}>
            Hủy
          </Button>
          <Button type="submit" disabled={save.isPending}>
            {save.isPending ? 'Đang lưu...' : 'Lưu hồ sơ'}
          </Button>
        </ModalFooter>
      </form>
    </Modal>
  );
}
