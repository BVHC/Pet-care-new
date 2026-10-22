/**
 * Thêm / sửa thú cưng (UC24) — dùng chung cho lễ tân (hồ sơ khách tại quầy) và khách (/pets).
 * BR-KH-02: tên, loài, giới tính bắt buộc, ngày sinh không ở tương lai; trùng tên + loài chỉ cảnh báo.
 * BR-KH-03: thú đã có bệnh án / mũi tiêm thì loài chỉ đọc.
 */
import { useState, type FormEvent } from 'react';
import { toast } from 'sonner';
import { Checkbox, Field, FormError, selectClass } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { fieldErrorsOf, plainMessage, toApiError } from '@/shared/api/api-error';
import {
  hasErrors,
  petErrors,
  todayVn,
  useAddMyPet,
  useAddPetForCustomer,
  useUpdatePet,
  type FieldErrors,
} from '@/domains/pets/hooks/useCustomerPet';
import { SEX_LABEL, SPECIES_LABEL } from '@/domains/pets/labels';
import type { CreatePetRequest, PetResponse, PetWriteResult, Sex, Species, UpdatePetRequest } from '@/domains/pets/types';

type Props = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSaved?: (pet: PetResponse) => void;
} & (
  | { mode: 'create'; /** Lễ tân thêm cho hồ sơ này; bỏ trống = khách tự thêm (/me/pets). */ customerId?: number }
  | { mode: 'edit'; pet: PetResponse }
);

interface Draft {
  name: string;
  species: Species;
  sex: Sex;
  breed: string;
  birthDate: string;
  birthDateEstimated: boolean;
  color: string;
  isNeutered: '' | 'yes' | 'no';
  photoUrl: string;
}

const SPECIES: Species[] = ['DOG', 'CAT', 'OTHER'];
const SEXES: Sex[] = ['MALE', 'FEMALE', 'UNKNOWN'];

const draftOf = (pet: PetResponse | null): Draft => ({
  name: pet?.name ?? '',
  species: pet?.species ?? 'DOG',
  sex: pet?.sex ?? 'UNKNOWN',
  breed: pet?.breed ?? '',
  birthDate: pet?.birthDate ?? '',
  birthDateEstimated: pet?.birthDateEstimated ?? false,
  color: pet?.color ?? '',
  isNeutered: pet?.isNeutered == null ? '' : pet.isNeutered ? 'yes' : 'no',
  photoUrl: pet?.photoUrl ?? '',
});

const requestOf = (d: Draft): CreatePetRequest => ({
  name: d.name.trim(),
  species: d.species,
  sex: d.sex,
  breed: d.breed.trim() || undefined,
  birthDate: d.birthDate || undefined,
  birthDateEstimated: d.birthDateEstimated,
  color: d.color.trim() || undefined,
  isNeutered: d.isNeutered === '' ? undefined : d.isNeutered === 'yes',
  photoUrl: d.photoUrl.trim() || undefined,
});

/** PATCH chỉ gửi trường đã đổi; chuỗi rỗng = xóa giá trị cũ. */
function changesOf(before: Draft, after: Draft): UpdatePetRequest {
  const changes: UpdatePetRequest = {};
  if (after.name.trim() !== before.name) changes.name = after.name.trim();
  if (after.species !== before.species) changes.species = after.species;
  if (after.sex !== before.sex) changes.sex = after.sex;
  if (after.breed.trim() !== before.breed) changes.breed = after.breed.trim();
  if (after.birthDate && after.birthDate !== before.birthDate) changes.birthDate = after.birthDate;
  if (after.birthDateEstimated !== before.birthDateEstimated) changes.birthDateEstimated = after.birthDateEstimated;
  if (after.color.trim() !== before.color) changes.color = after.color.trim();
  if (after.isNeutered && after.isNeutered !== before.isNeutered) changes.isNeutered = after.isNeutered === 'yes';
  if (after.photoUrl.trim() !== before.photoUrl) changes.photoUrl = after.photoUrl.trim();
  return changes;
}

export function PetFormDialog(props: Props) {
  const editing = props.mode === 'edit' ? props.pet : null;
  return (
    <Dialog open={props.open} onOpenChange={props.onOpenChange}>
      <DialogContent className="ws-portal max-h-[90dvh] overflow-y-auto border-(--ws-line) bg-(--ws-surface) text-(--ws-ink) sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{editing ? `Sửa thông tin ${editing.name}` : 'Thêm thú cưng'}</DialogTitle>
          <DialogDescription>Tên, loài và giới tính là bắt buộc.</DialogDescription>
        </DialogHeader>
        {/* Nội dung chỉ mount khi mở nên mỗi lần mở là một bản nháp mới. */}
        <PetForm {...props} />
      </DialogContent>
    </Dialog>
  );
}

function PetForm(props: Props) {
  const editing = props.mode === 'edit' ? props.pet : null;
  const forCustomer = props.mode === 'create' ? props.customerId : undefined;
  const [draft, setDraft] = useState(() => draftOf(editing));
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState('');
  const addForCustomer = useAddPetForCustomer(forCustomer ?? 0);
  const addMine = useAddMyPet();
  const update = useUpdatePet(editing?.petId ?? 0);
  const pending = addForCustomer.isPending || addMine.isPending || update.isPending;

  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((d) => ({ ...d, [key]: value }));

  const saved = (result: PetWriteResult) => {
    toast.success(editing ? `Đã cập nhật ${result.pet.name}` : `Đã thêm ${result.pet.name}`);
    if (result.duplicateNameWarning) {
      toast.warning(`Chủ đã có một thú khác cùng tên "${result.pet.name}" và cùng loài — kiểm tra có bị tạo trùng không.`);
    }
    props.onOpenChange(false);
    props.onSaved?.(result.pet);
  };
  const failed = (e: unknown) => {
    const error = toApiError(e);
    const fields = fieldErrorsOf(error);
    if (Object.keys(fields).length) setErrors(fields);
    else setFormError(plainMessage(error));
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setFormError('');
    const body = requestOf(draft);
    const found = petErrors(body);
    setErrors(found);
    if (hasErrors(found)) return;
    const options = { onSuccess: saved, onError: failed };
    if (editing) {
      const changes = changesOf(draftOf(editing), draft);
      if (Object.keys(changes).length === 0) props.onOpenChange(false);
      else update.mutate(changes, options);
    } else if (forCustomer != null) {
      addForCustomer.mutate(body, options);
    } else {
      addMine.mutate(body, options);
    }
  };

  return (
    <form className="space-y-4" onSubmit={submit} noValidate>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Tên thú cưng" error={errors.name}>
          {(id) => <Input id={id} value={draft.name} onChange={(e) => set('name', e.target.value)} autoFocus />}
        </Field>
        <Field
          label="Loài"
          error={errors.species}
          hint={editing?.speciesLocked ? 'Đã có bệnh án hoặc mũi tiêm nên không đổi được loài (BR-KH-03)' : undefined}
        >
          {(id) => (
            <select
              id={id}
              className={selectClass}
              value={draft.species}
              disabled={!!editing?.speciesLocked}
              onChange={(e) => set('species', e.target.value as Species)}
            >
              {SPECIES.map((s) => (
                <option key={s} value={s}>
                  {SPECIES_LABEL[s]}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Giới tính" error={errors.sex}>
          {(id) => (
            <select id={id} className={selectClass} value={draft.sex} onChange={(e) => set('sex', e.target.value as Sex)}>
              {SEXES.map((s) => (
                <option key={s} value={s}>
                  {SEX_LABEL[s]}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Giống" error={errors.breed}>
          {(id) => <Input id={id} value={draft.breed} maxLength={100} onChange={(e) => set('breed', e.target.value)} />}
        </Field>
        <Field label="Ngày sinh" error={errors.birthDate}>
          {(id) => (
            <Input id={id} type="date" max={todayVn()} value={draft.birthDate} onChange={(e) => set('birthDate', e.target.value)} />
          )}
        </Field>
        <div className="flex items-end pb-2.5">
          <Checkbox label="Ngày sinh ước lượng" checked={draft.birthDateEstimated} onChange={(v) => set('birthDateEstimated', v)} />
        </div>
        <Field label="Màu lông" error={errors.color}>
          {(id) => <Input id={id} value={draft.color} maxLength={50} onChange={(e) => set('color', e.target.value)} />}
        </Field>
        <Field label="Triệt sản">
          {(id) => (
            <select
              id={id}
              className={selectClass}
              value={draft.isNeutered}
              onChange={(e) => set('isNeutered', e.target.value as Draft['isNeutered'])}
            >
              <option value="">Chưa rõ</option>
              <option value="yes">Đã triệt sản</option>
              <option value="no">Chưa triệt sản</option>
            </select>
          )}
        </Field>
        <div className="sm:col-span-2">
          <Field label="Đường dẫn ảnh" error={errors.photoUrl} hint="Chưa có tải ảnh lên, dán đường dẫn ảnh (customer-v1 Q1).">
            {(id) => <Input id={id} value={draft.photoUrl} maxLength={500} onChange={(e) => set('photoUrl', e.target.value)} />}
          </Field>
        </div>
      </div>
      <FormError message={formError} />
      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" onClick={() => props.onOpenChange(false)}>
          Hủy
        </Button>
        <Button type="submit" disabled={pending}>
          {pending ? 'Đang lưu…' : editing ? 'Lưu thay đổi' : 'Thêm thú cưng'}
        </Button>
      </div>
    </form>
  );
}
