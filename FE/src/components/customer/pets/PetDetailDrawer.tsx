/**
 * Chi tiết thú cưng phía khách (UC24) và hồ sơ sức khỏe (UC25).
 * Thú đã mất: chỉ đọc (BR-KH-05). Khách không nhận ghi chú nội bộ — BE đã lọc (BR-KH-07);
 * nhân viên mở ở chế độ `readOnly` (tra cứu UC23) thì nhận và thấy cả ghi chú nội bộ.
 */
import { useState, type FormEvent } from 'react';
import { CalendarClock, HeartCrack, Pencil, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { FormError } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet';
import { cn } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import {
  useAddWeight,
  useDeletePet,
  useHealthRecord,
  usePetDetail,
  usePetWeights,
  weightErrors,
} from '@/domains/pets/hooks/useCustomerPet';
import { SERVICE_GROUP_LABEL, SEX_LABEL, SPECIES_LABEL, WEIGHT_SOURCE_LABEL } from '@/domains/pets/labels';
import type { PetResponse } from '@/domains/pets/types';
import { MarkDeceasedDialog } from './MarkDeceasedDialog';
import { PetAvatar } from './pet-presentation';
import { PetFormDialog } from './PetFormDialog';

/** yyyy-MM-dd → dd/MM/yyyy (ngày nghiệp vụ, không đổi múi giờ). */
const vnDate = (iso: string) => iso.slice(0, 10).split('-').reverse().join('/');
const vnDateTime = (iso: string) =>
  new Date(iso).toLocaleString('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh', dateStyle: 'short', timeStyle: 'short' });

type DrawerTab = 'profile' | 'health';

export function PetDetailDrawer({
  petId,
  onClose,
  readOnly = false,
  initialTab = 'profile',
}: {
  petId: number | null;
  onClose: () => void;
  /** Nhân viên xem: ẩn sửa, đánh dấu mất, xóa, tự khai cân nặng. */
  readOnly?: boolean;
  initialTab?: DrawerTab;
}) {
  const detail = usePetDetail(petId);
  const [tab, setTab] = useState<DrawerTab>(initialTab);
  const [editing, setEditing] = useState(false);
  const [deceased, setDeceased] = useState<PetResponse | null>(null);
  const pet = detail.data;

  return (
    <>
      <Sheet open={petId != null} onOpenChange={(open) => !open && onClose()}>
        <SheetContent className="ws-portal w-full gap-0 overflow-y-auto border-(--ws-line) bg-(--ws-surface) p-0 text-(--ws-ink) sm:max-w-md">
          <SheetHeader className="border-b border-(--ws-line) p-5">
            <SheetTitle className="flex items-center gap-3 pr-6">
              {pet && <PetAvatar pet={pet} size="lg" />}
              <span>{pet?.name ?? 'Thú cưng'}</span>
            </SheetTitle>
            <SheetDescription>{pet ? `${SPECIES_LABEL[pet.species]} · ${SEX_LABEL[pet.sex]}` : 'Đang tải…'}</SheetDescription>
          </SheetHeader>

          {detail.isError && (
            <div className="p-5">
              <FormError message={plainMessage(toApiError(detail.error))} />
            </div>
          )}

          {pet && (
            <>
              <div role="tablist" className="flex gap-1 border-b border-(--ws-line) px-5">
                {(
                  [
                    ['profile', 'Hồ sơ'],
                    ['health', 'Sức khỏe'],
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
                      tab === key ? 'border-(--ws-accent) text-(--ws-accent-ink)' : 'border-transparent text-(--ws-ink-2)',
                    )}
                  >
                    {label}
                  </button>
                ))}
              </div>
              <div className="p-5">
                {tab === 'profile' ? (
                  <ProfileSection
                    pet={pet}
                    readOnly={readOnly}
                    onEdit={() => setEditing(true)}
                    onDeceased={() => setDeceased(pet)}
                    onDeleted={onClose}
                  />
                ) : (
                  <div className="space-y-6">
                    <HealthSection petId={pet.petId} />
                    <WeightSection pet={pet} readOnly={readOnly} />
                  </div>
                )}
              </div>
            </>
          )}
        </SheetContent>
      </Sheet>

      {pet && !readOnly && <PetFormDialog open={editing} onOpenChange={setEditing} mode="edit" pet={pet} />}
      {!readOnly && <MarkDeceasedDialog pet={deceased} onClose={() => setDeceased(null)} />}
    </>
  );
}

function ProfileSection({
  pet,
  readOnly,
  onEdit,
  onDeceased,
  onDeleted,
}: {
  pet: PetResponse;
  readOnly: boolean;
  onEdit: () => void;
  onDeceased: () => void;
  onDeleted: () => void;
}) {
  const remove = useDeletePet();
  const [confirmDelete, setConfirmDelete] = useState(false);
  const rows: [string, string][] = [
    ['Giống', pet.breed ?? '—'],
    ['Ngày sinh', pet.birthDate ? `${vnDate(pet.birthDate)}${pet.birthDateEstimated ? ' (ước lượng)' : ''}` : '—'],
    ['Màu lông', pet.color ?? '—'],
    ['Triệt sản', pet.isNeutered == null ? 'Chưa rõ' : pet.isNeutered ? 'Đã triệt sản' : 'Chưa triệt sản'],
    ['Cân nặng hiện tại', pet.currentWeightKg != null ? `${pet.currentWeightKg} kg` : '—'],
  ];

  return (
    <div className="space-y-4">
      {pet.deceasedOn && (
        <p className="flex items-center gap-2 rounded-lg bg-(--ws-raised) p-3 text-sm text-(--ws-ink-2)">
          <HeartCrack className="h-4 w-4" aria-hidden /> Đã mất ngày {vnDate(pet.deceasedOn)}. Hồ sơ chỉ đọc.
        </p>
      )}
      <dl className="grid grid-cols-2 gap-3 text-sm">
        {rows.map(([label, value]) => (
          <div key={label}>
            <dt className="text-xs text-(--ws-ink-2)">{label}</dt>
            <dd className="font-medium">{value}</dd>
          </div>
        ))}
      </dl>
      {!readOnly && !pet.deceasedOn && (
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" size="sm" onClick={onEdit}>
            <Pencil className="h-4 w-4" aria-hidden /> Sửa thông tin
          </Button>
          <Button variant="outline" size="sm" onClick={onDeceased}>
            <HeartCrack className="h-4 w-4" aria-hidden /> Đánh dấu đã mất
          </Button>
          {pet.deletable &&
            (confirmDelete ? (
              <span className="flex items-center gap-2 text-sm">
                Xóa hẳn {pet.name}?
                <Button size="sm" variant="outline" onClick={() => setConfirmDelete(false)} disabled={remove.isPending}>
                  Không
                </Button>
                <Button
                  size="sm"
                  variant="destructive"
                  disabled={remove.isPending}
                  onClick={() =>
                    remove.mutate(pet.petId, {
                      onSuccess: () => {
                        toast.success(`Đã xóa ${pet.name}`);
                        onDeleted();
                      },
                      onError: (e) => toast.error(plainMessage(toApiError(e))),
                    })
                  }
                >
                  Xóa
                </Button>
              </span>
            ) : (
              <Button variant="ghost" size="sm" className="text-red-600" onClick={() => setConfirmDelete(true)}>
                <Trash2 className="h-4 w-4" aria-hidden /> Xóa
              </Button>
            ))}
        </div>
      )}
      {!readOnly && !pet.deceasedOn && !pet.deletable && (
        <p className="text-xs text-(--ws-ink-3)">Thú đã có lịch hẹn, lượt khám hoặc Order nên không xóa được (BR-KH-06).</p>
      )}
    </div>
  );
}

function HealthSection({ petId }: { petId: number }) {
  const health = useHealthRecord(petId);
  if (health.isPending) return <p className="text-sm text-(--ws-ink-2)">Đang tải hồ sơ sức khỏe…</p>;
  if (health.isError) return <FormError message={plainMessage(toApiError(health.error))} />;
  const record = health.data;
  const hasUpcoming = record.upcomingVaccinations.length > 0 || !!record.upcomingFollowUpDate;

  return (
    <div className="space-y-6">
      {hasUpcoming && (
        <section aria-label="Sắp tới">
          <h3 className="mb-2 flex items-center gap-2 text-sm font-semibold">
            <CalendarClock className="h-4 w-4" aria-hidden /> Sắp tới
          </h3>
          <ul className="space-y-1.5 text-sm">
            {record.upcomingFollowUpDate && (
              <li className="rounded-md bg-sky-50 px-3 py-2 text-sky-900">Tái khám ngày {vnDate(record.upcomingFollowUpDate)}</li>
            )}
            {record.upcomingVaccinations.map((v) => (
              <li key={v.vaccineTypeId} className="rounded-md bg-amber-50 px-3 py-2 text-amber-900">
                Tái chủng {v.vaccineTypeName} ngày {vnDate(v.nextDueDate)}
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-label="Lượt khám và dịch vụ">
        <h3 className="mb-2 text-sm font-semibold">Lượt khám & dịch vụ</h3>
        {record.visits.length === 0 ? (
          <p className="text-sm text-(--ws-ink-2)">Chưa có lượt khám nào.</p>
        ) : (
          <ul className="space-y-2">
            {record.visits.map((v) => (
              <li key={v.visitId} className="rounded-lg border border-(--ws-line) p-3 text-sm">
                <p className="text-xs text-(--ws-ink-2)">
                  {vnDate(v.visitDate)} · {v.branchName} · {SERVICE_GROUP_LABEL[v.serviceGroup]}
                </p>
                {v.diagnosis && <p className="mt-1 font-medium">{v.diagnosis}</p>}
                {v.examination && <p className="text-(--ws-ink-2)">Khám: {v.examination}</p>}
                {v.treatmentPlan && <p className="text-(--ws-ink-2)">Điều trị: {v.treatmentPlan}</p>}
                {v.followUpDate && <p className="text-xs">Hẹn tái khám: {vnDate(v.followUpDate)}</p>}
                {/* Chỉ nhân viên nhận trường này; khách không có (BR-KH-07). */}
                {v.internalNote && (
                  <p className="mt-1 rounded-md bg-amber-50 px-2 py-1 text-xs text-amber-900">Ghi chú nội bộ: {v.internalNote}</p>
                )}
                {v.prescriptions.length > 0 && (
                  <ul aria-label="Đơn thuốc" className="mt-2 space-y-0.5 rounded-md bg-(--ws-raised) p-2 text-xs">
                    {v.prescriptions.map((p) => (
                      <li key={p.itemId}>
                        {p.productName} × {p.quantity} — {p.dosageInstructions}
                        {p.isExternalPurchase && ' (mua ngoài)'}
                      </li>
                    ))}
                  </ul>
                )}
                {v.addenda.map((a) => (
                  <p key={a.addendumId} className="mt-1 text-xs text-(--ws-ink-2)">
                    Bổ sung{a.isInternal && ' (nội bộ)'} {vnDateTime(a.createdAt)} — {a.createdByName}: {a.content}
                  </p>
                ))}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-label="Mũi tiêm">
        <h3 className="mb-2 text-sm font-semibold">Mũi tiêm tại hệ thống</h3>
        {record.vaccinations.length === 0 ? (
          <p className="text-sm text-(--ws-ink-2)">Chưa có mũi tiêm nào.</p>
        ) : (
          <ul className="space-y-1.5 text-sm">
            {record.vaccinations.map((v) => (
              <li key={v.vaccinationId} className="rounded-md border border-(--ws-line) px-3 py-2">
                <span className="font-medium">
                  {v.vaccineTypeName} — mũi {v.doseNumber}
                </span>
                <span className="block text-xs text-(--ws-ink-2)">
                  {v.productName}
                  {v.lotNumber && ` · lô ${v.lotNumber}`} · tiêm {vnDate(v.administeredOn)}
                  {!v.superseded && ` · tái chủng ${vnDate(v.nextDueDate)}`}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

/** Lịch sử cân nặng, mới nhất trước; chỉ chủ tự khai thêm (BR-KH-04). */
function WeightSection({ pet, readOnly }: { pet: PetResponse; readOnly: boolean }) {
  const weights = usePetWeights(pet.petId);
  const add = useAddWeight(pet.petId);
  const [value, setValue] = useState('');
  const [error, setError] = useState('');

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const weightKg = value.trim() === '' ? null : Number(value.replace(',', '.'));
    const found = weightErrors(weightKg);
    if (found.weightKg) {
      setError(found.weightKg);
      return;
    }
    setError('');
    add.mutate(
      { weightKg: weightKg! },
      {
        onSuccess: () => {
          toast.success('Đã ghi cân nặng');
          setValue('');
        },
        onError: (err) => setError(plainMessage(toApiError(err))),
      },
    );
  };

  return (
    <section aria-label="Cân nặng">
      <h3 className="mb-2 text-sm font-semibold">Cân nặng</h3>
      {!readOnly && !pet.deceasedOn && (
        <form className="mb-2 flex gap-2" onSubmit={submit} noValidate>
          <Input aria-label="Cân nặng (kg)" inputMode="decimal" placeholder="Ví dụ 4.5" value={value} onChange={(e) => setValue(e.target.value)} />
          <Button type="submit" disabled={add.isPending}>
            Ghi
          </Button>
        </form>
      )}
      {error && <p className="mb-2 text-xs font-medium text-red-600">{error}</p>}
      {weights.data && weights.data.length > 0 ? (
        <ul className="space-y-1 text-sm">
          {weights.data.map((w) => (
            <li key={w.weightRecordId} className="flex justify-between gap-2">
              <span className="text-(--ws-ink-2)">
                {vnDateTime(w.measuredAt)} · {WEIGHT_SOURCE_LABEL[w.source]}
              </span>
              <span className="font-medium">{w.weightKg} kg</span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-(--ws-ink-2)">Chưa có lần đo nào.</p>
      )}
    </section>
  );
}
