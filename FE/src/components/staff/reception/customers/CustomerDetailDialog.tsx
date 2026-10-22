/**
 * Hồ sơ khách tại quầy (UC22) kèm thú cưng (UC24 phía lễ tân) và chuyển chủ (UC26).
 * Sửa SĐT sang số đã có ở hồ sơ khác: vẫn lưu, hiện cảnh báo nghi trùng (BR-KH-10).
 * Hồ sơ đã gắn tài khoản: email theo tài khoản, sửa hộ qua OTP (BR-TK-16); hồ sơ tại quầy chưa gắn: liên kết hộ (BR-TK-19).
 * `readOnly`: bác sĩ, nhân viên chăm sóc tra cứu (UC23) — chỉ xem, kể cả hồ sơ sức khỏe của thú.
 */
import { useState, type FormEvent } from 'react';
import { AlertTriangle, ArrowRightLeft, HeartCrack, Link2, Mail, Pencil, Plus, Stethoscope, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Field, FormError } from '@/components/admin/form-ui';
import { MarkDeceasedDialog } from '@/components/customer/pets/MarkDeceasedDialog';
import { PetAvatar } from '@/components/customer/pets/pet-presentation';
import { PetDetailDrawer } from '@/components/customer/pets/PetDetailDrawer';
import { PetFormDialog } from '@/components/customer/pets/PetFormDialog';
import { TransferOwnerDialog } from '@/components/customer/pets/TransferOwnerDialog';
import { AccountEmailDialog, LinkAccountDialog } from './CustomerAccountDialogs';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { fieldErrorsOf, plainMessage, toApiError } from '@/shared/api/api-error';
import type { CustomerProfile } from '@/shared/types/profile';
import {
  customerErrors,
  hasErrors,
  useCustomerDetail,
  useDeletePet,
  useUpdateCustomer,
  type FieldErrors,
} from '@/domains/pets/hooks/useCustomerPet';
import { CHANNEL_LABEL, SEX_LABEL, SPECIES_LABEL } from '@/domains/pets/labels';
import type { CustomerSearchItem, PetResponse, UpdateCustomerRequest } from '@/domains/pets/types';

type Tab = 'profile' | 'pets';

export function CustomerDetailDialog({
  customerId,
  onClose,
  readOnly = false,
}: {
  customerId: number | null;
  onClose: () => void;
  readOnly?: boolean;
}) {
  const detail = useCustomerDetail(customerId);
  const [tab, setTab] = useState<Tab>('pets');
  const [petForm, setPetForm] = useState<{ pet: PetResponse | null } | null>(null);
  const [deceased, setDeceased] = useState<PetResponse | null>(null);
  const [transfer, setTransfer] = useState<PetResponse | null>(null);
  const [healthPetId, setHealthPetId] = useState<number | null>(null);
  const customer = detail.data?.customer;

  return (
    <>
      <Dialog open={customerId != null} onOpenChange={(open) => !open && onClose()}>
        <DialogContent className="ws-portal max-h-[90dvh] overflow-y-auto border-(--ws-line) bg-(--ws-surface) text-(--ws-ink) sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>{customer?.fullName ?? 'Hồ sơ khách'}</DialogTitle>
            <DialogDescription>
              {customer
                ? `${CHANNEL_LABEL[customer.createdChannel]} · ${customer.phone ?? 'chưa có SĐT'}${customer.hasAccount ? ' · có tài khoản online' : ''}`
                : 'Đang tải…'}
            </DialogDescription>
          </DialogHeader>

          {detail.isError && <FormError message={plainMessage(toApiError(detail.error))} />}

          {customer && detail.data && (
            <>
              <div role="tablist" className="flex gap-1 border-b border-(--ws-line)">
                {(
                  [
                    ['pets', `Thú cưng (${detail.data.pets.length})`],
                    ['profile', 'Thông tin khách'],
                  ] as const
                ).map(([key, label]) => (
                  <button
                    key={key}
                    role="tab"
                    type="button"
                    aria-selected={tab === key}
                    onClick={() => setTab(key)}
                    className={cn(
                      '-mb-px border-b-2 px-3 py-2 text-sm font-medium',
                      tab === key ? 'border-(--ws-accent) text-(--ws-accent-ink)' : 'border-transparent text-(--ws-ink-2) hover:text-(--ws-ink)',
                    )}
                  >
                    {label}
                  </button>
                ))}
              </div>

              {tab === 'pets' ? (
                <PetList
                  pets={detail.data.pets}
                  readOnly={readOnly}
                  onAdd={() => setPetForm({ pet: null })}
                  onEdit={(pet) => setPetForm({ pet })}
                  onDeceased={setDeceased}
                  onTransfer={setTransfer}
                  onHealth={(pet) => setHealthPetId(pet.petId)}
                />
              ) : (
                <ProfileTab key={customer.customerId} customer={customer} readOnly={readOnly} />
              )}
            </>
          )}
        </DialogContent>
      </Dialog>

      {/* key: mỗi thú mở lại từ tab Sức khỏe. */}
      <PetDetailDrawer key={healthPetId ?? 'none'} petId={healthPetId} onClose={() => setHealthPetId(null)} readOnly initialTab="health" />
      {!readOnly && customerId != null && (
        <PetFormDialog
          open={petForm != null}
          onOpenChange={(open) => !open && setPetForm(null)}
          {...(petForm?.pet ? { mode: 'edit' as const, pet: petForm.pet } : { mode: 'create' as const, customerId })}
        />
      )}
      <MarkDeceasedDialog pet={deceased} onClose={() => setDeceased(null)} />
      <TransferOwnerDialog pet={transfer} onClose={() => setTransfer(null)} />
    </>
  );
}

function PetList({
  pets,
  readOnly,
  onAdd,
  onEdit,
  onDeceased,
  onTransfer,
  onHealth,
}: {
  pets: PetResponse[];
  readOnly: boolean;
  onAdd: () => void;
  onEdit: (pet: PetResponse) => void;
  onDeceased: (pet: PetResponse) => void;
  onTransfer: (pet: PetResponse) => void;
  onHealth: (pet: PetResponse) => void;
}) {
  const remove = useDeletePet();
  const [confirmDelete, setConfirmDelete] = useState<number | null>(null);

  const doDelete = (pet: PetResponse) =>
    remove.mutate(pet.petId, {
      onSuccess: () => toast.success(`Đã xóa ${pet.name}`),
      onError: (e) => toast.error(plainMessage(toApiError(e))),
      onSettled: () => setConfirmDelete(null),
    });

  return (
    <div className="space-y-3">
      {pets.length === 0 && (
        <p className="rounded-lg border border-dashed border-(--ws-line) p-6 text-center text-sm text-(--ws-ink-2)">Khách chưa có thú cưng nào.</p>
      )}
      <ul className="space-y-2">
        {pets.map((pet) => (
          <li key={pet.petId} className="rounded-lg border border-(--ws-line) p-3">
            <div className="flex items-start gap-3">
              <PetAvatar pet={pet} />
              <div className="min-w-0 flex-1">
                <p className={cn('font-semibold', pet.deceasedOn && 'text-(--ws-ink-3)')}>{pet.name}</p>
                <p className="text-xs text-(--ws-ink-2)">
                  {SPECIES_LABEL[pet.species]} · {SEX_LABEL[pet.sex]}
                  {pet.breed && ` · ${pet.breed}`}
                  {pet.currentWeightKg != null && ` · ${pet.currentWeightKg} kg`}
                </p>
                {pet.deceasedOn && <p className="mt-1 text-xs font-medium text-(--ws-ink-3)">Đã mất ngày {pet.deceasedOn} — chỉ đọc</p>}
              </div>
            </div>
            {confirmDelete === pet.petId ? (
              <div className="mt-2 flex flex-wrap items-center justify-end gap-2 text-sm">
                <span>Xóa hẳn {pet.name}?</span>
                <Button size="sm" variant="outline" onClick={() => setConfirmDelete(null)} disabled={remove.isPending}>
                  Không
                </Button>
                <Button size="sm" variant="destructive" onClick={() => doDelete(pet)} disabled={remove.isPending}>
                  Xóa
                </Button>
              </div>
            ) : (
              <div className="mt-2 flex flex-wrap justify-end gap-1">
                <Button size="sm" variant="ghost" onClick={() => onHealth(pet)} aria-label={`Hồ sơ sức khỏe của ${pet.name}`}>
                  <Stethoscope className="h-3.5 w-3.5" aria-hidden /> Sức khỏe
                </Button>
                {!readOnly && !pet.deceasedOn && (
                  <>
                    <Button size="sm" variant="ghost" onClick={() => onEdit(pet)}>
                      <Pencil className="h-3.5 w-3.5" aria-hidden /> Sửa
                    </Button>
                    <Button size="sm" variant="ghost" onClick={() => onTransfer(pet)}>
                      <ArrowRightLeft className="h-3.5 w-3.5" aria-hidden /> Chuyển chủ
                    </Button>
                    <Button size="sm" variant="ghost" onClick={() => onDeceased(pet)}>
                      <HeartCrack className="h-3.5 w-3.5" aria-hidden /> Đã mất
                    </Button>
                    {pet.deletable && (
                      <Button size="sm" variant="ghost" className="text-red-600" onClick={() => setConfirmDelete(pet.petId)}>
                        <Trash2 className="h-3.5 w-3.5" aria-hidden /> Xóa
                      </Button>
                    )}
                  </>
                )}
              </div>
            )}
          </li>
        ))}
      </ul>
      {!readOnly && (
        <div className="flex justify-end">
          <Button onClick={onAdd}>
            <Plus className="h-4 w-4" aria-hidden /> Thêm thú cưng
          </Button>
        </div>
      )}
    </div>
  );
}

function ProfileTab({ customer, readOnly }: { customer: CustomerProfile; readOnly: boolean }) {
  const [account, setAccount] = useState<'email' | 'link' | null>(null);
  const [editing, setEditing] = useState(false);
  const [fullName, setFullName] = useState(customer.fullName);
  const [phone, setPhone] = useState(customer.phone ?? '');
  const [email, setEmail] = useState(customer.email ?? '');
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState('');
  const [duplicates, setDuplicates] = useState<CustomerSearchItem[]>([]);
  const update = useUpdateCustomer(customer.customerId);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setFormError('');
    const found = customerErrors({ fullName, phone, email: customer.hasAccount ? '' : email }, customer.createdChannel === 'COUNTER');
    setErrors(found);
    if (hasErrors(found)) return;
    const changes: UpdateCustomerRequest = {};
    if (fullName.trim() !== customer.fullName) changes.fullName = fullName.trim();
    if (phone.trim() !== (customer.phone ?? '')) changes.phone = phone.trim();
    if (!customer.hasAccount && email.trim() !== (customer.email ?? '')) changes.email = email.trim();
    if (Object.keys(changes).length === 0) {
      setEditing(false);
      return;
    }
    update.mutate(changes, {
      onSuccess: (result) => {
        toast.success('Đã cập nhật hồ sơ khách');
        setDuplicates(result.duplicatePhoneProfiles);
        setEditing(false);
      },
      onError: (err) => {
        const error = toApiError(err);
        const fields = fieldErrorsOf(error);
        if (Object.keys(fields).length) setErrors(fields);
        else setFormError(plainMessage(error));
      },
    });
  };

  if (!editing) {
    return (
      <div className="space-y-3">
        {duplicates.length > 0 && (
          <p role="alert" className="flex items-start gap-2 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            SĐT mới trùng với: {duplicates.map((d) => d.fullName).join(', ')}. Kiểm tra có phải cùng một người không.
          </p>
        )}
        <dl className="grid gap-3 text-sm sm:grid-cols-2">
          {(
            [
              ['Họ tên', customer.fullName],
              ['Số điện thoại', customer.phone ?? '—'],
              ['Email', customer.email ?? '—'],
              ['Nguồn hồ sơ', CHANNEL_LABEL[customer.createdChannel]],
              ['Tài khoản online', customer.hasAccount ? 'Đã gắn' : 'Chưa gắn'],
            ] as const
          ).map(([label, value]) => (
            <div key={label}>
              <dt className="text-xs text-(--ws-ink-2)">{label}</dt>
              <dd className="font-medium">{value}</dd>
            </div>
          ))}
        </dl>
        {!readOnly && (
          <div className="flex flex-wrap justify-end gap-2">
            {customer.hasAccount && (
              <Button variant="outline" onClick={() => setAccount('email')}>
                <Mail className="h-4 w-4" aria-hidden /> Sửa email tài khoản
              </Button>
            )}
            {!customer.hasAccount && customer.createdChannel === 'COUNTER' && (
              <Button variant="outline" onClick={() => setAccount('link')}>
                <Link2 className="h-4 w-4" aria-hidden /> Liên kết tài khoản online
              </Button>
            )}
            <Button variant="outline" onClick={() => setEditing(true)}>
              <Pencil className="h-4 w-4" aria-hidden /> Sửa thông tin
            </Button>
          </div>
        )}
        <AccountEmailDialog customer={customer} open={account === 'email'} onClose={() => setAccount(null)} />
        <LinkAccountDialog customer={customer} open={account === 'link'} onClose={() => setAccount(null)} />
      </div>
    );
  }

  return (
    <form className="space-y-3" onSubmit={submit} noValidate>
      <Field label="Họ tên" error={errors.fullName}>
        {(id) => <Input id={id} value={fullName} maxLength={100} onChange={(e) => setFullName(e.target.value)} />}
      </Field>
      <Field label="Số điện thoại" error={errors.phone}>
        {(id) => <Input id={id} value={phone} inputMode="tel" maxLength={10} onChange={(e) => setPhone(e.target.value)} />}
      </Field>
      <Field
        label="Email"
        error={errors.email}
        hint={customer.hasAccount ? 'Hồ sơ đã gắn tài khoản: email theo tài khoản, không sửa ở đây.' : undefined}
      >
        {(id) => <Input id={id} type="email" value={email} disabled={customer.hasAccount} onChange={(e) => setEmail(e.target.value)} />}
      </Field>
      <FormError message={formError} />
      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" onClick={() => setEditing(false)}>
          Hủy
        </Button>
        <Button type="submit" disabled={update.isPending}>
          {update.isPending ? 'Đang lưu…' : 'Lưu'}
        </Button>
      </div>
    </form>
  );
}
