import { useState } from 'react';
import { Trash2 } from 'lucide-react';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { FormModal } from '../../../components/ui/modal-templates';
import { EmptyRow, Field, FormError, NoticeBar, selectClass, type Notice } from '../../../components/admin/form-ui';
import { cn, formatDate } from '../../../lib/utils';
import { branchApi } from '../../../shared/api/branch.api';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useBranchMutation, useHolidays, useOpeningHours, useQuotaDefaults, useSlotQuotas } from '../../../shared/hooks/useBranch';
import { QUOTA_GROUP_LABELS, type Branch, type QuotaGroup, type SlotQuota } from '../../../shared/types/branch';
import { addDays, localIsoDate, slotsOn } from '../../../shared/utils/branch-schedule';

const GROUPS: QuotaGroup[] = ['MEDICAL', 'GROOMING'];
const MAX_QUOTA = 32767;
/** Quota của khung chưa cấu hình (BR-LH-03). */
const FALLBACK_QUOTA = 1;

const quotaText = (quota: number) => (quota === 0 ? 'Khóa' : `${quota} lượt`);
const validQuota = (value: string) => value.trim() !== '' && Number.isInteger(Number(value)) && Number(value) >= 0 && Number(value) <= MAX_QUOTA;

/**
 * UC42 — quota lịch hẹn (BR-LH-03): mặc định theo nhóm, và quota riêng của từng khung 30 phút (0 = khóa khung).
 * Chỉ BRANCH_MANAGER của chi nhánh (SUPER_MANAGER không đọc được quota).
 */
export function QuotaTab({ branch }: { branch: Branch }) {
  const branchId = branch.branchId;
  const today = localIsoDate();
  const defaults = useQuotaDefaults(branchId);
  const hours = useOpeningHours(branchId);
  const holidays = useHolidays(branchId);
  const [date, setDate] = useState(today);
  const [group, setGroup] = useState<QuotaGroup>('MEDICAL');
  const dayQuotas = useSlotQuotas(branchId, date, date, group);
  const upcoming = useSlotQuotas(branchId, today, addDays(today, 30));
  const [editingSlot, setEditingSlot] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const removeOverride = useBranchMutation((q: SlotQuota) => branchApi.deleteSlotQuota(branchId, q.slotQuotaId));

  const defaultOf = (g: QuotaGroup) => defaults.data?.find((d) => d.serviceGroup === g)?.defaultQuota ?? null;
  const holidayDates = (holidays.data ?? []).map((h) => h.holidayDate);
  const slots = slotsOn(hours.data ?? [], holidayDates, date);
  const overrideAt = (start: string) => dayQuotas.data?.find((q) => q.slotStart === start);
  const effective = (start: string) => overrideAt(start)?.quota ?? defaultOf(group) ?? FALLBACK_QUOTA;

  const removeUpcoming = async (q: SlotQuota) => {
    try {
      await removeOverride.mutateAsync(q);
      setNotice({ kind: 'success', text: `Đã bỏ quota riêng khung ${q.slotStart} ngày ${formatDate(q.slotDate)}` });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  return (
    <div className="space-y-4">
      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="p-5 space-y-3">
          <div>
            <h3 className="font-semibold text-(--text-primary)">Quota mặc định</h3>
            <p className="text-sm text-(--text-secondary)">
              Số lịch hẹn tối đa của mọi khung 30 phút. Chưa đặt thì mỗi khung nhận 1 lượt. Giảm quota không ảnh hưởng lịch đã đặt.
            </p>
          </div>
          {GROUPS.map((g) => (
            <DefaultQuotaRow key={g} branchId={branchId} group={g} current={defaultOf(g)} onDone={setNotice} />
          ))}
        </CardContent>
      </Card>

      <Card className="card">
        <CardContent className="p-5 space-y-4">
          <div>
            <h3 className="font-semibold text-(--text-primary)">Quota riêng theo khung giờ</h3>
            <p className="text-sm text-(--text-secondary)">Chọn ngày và nhóm, bấm vào khung để đặt quota riêng (0 = khóa khung) hoặc bỏ quota riêng.</p>
          </div>
          <div className="flex flex-wrap items-end gap-3">
            <Field label="Ngày">
              {(id) => <Input id={id} type="date" value={date} onChange={(e) => e.target.value && setDate(e.target.value)} className="w-48!" />}
            </Field>
            <select aria-label="Nhóm dịch vụ" className={cn(selectClass, 'w-44')} value={group} onChange={(e) => setGroup(e.target.value as QuotaGroup)}>
              {GROUPS.map((g) => (
                <option key={g} value={g}>
                  {QUOTA_GROUP_LABELS[g]}
                </option>
              ))}
            </select>
          </div>
          {holidayDates.includes(date) ? (
            <p className="text-sm text-(--text-secondary)">Ngày nghỉ của chi nhánh, không có khung đặt lịch.</p>
          ) : hours.isSuccess && slots.length === 0 ? (
            <p className="text-sm text-(--text-secondary)">Chi nhánh không mở cửa ngày này.</p>
          ) : (
            <div className="grid grid-cols-3 gap-2 sm:grid-cols-5 lg:grid-cols-8">
              {slots.map((start) => {
                const quota = effective(start);
                const own = !!overrideAt(start);
                return (
                  <button
                    key={start}
                    type="button"
                    onClick={() => setEditingSlot(start)}
                    aria-label={`Khung ${start}: ${quotaText(quota)}${own ? ' (quota riêng)' : ''}`}
                    className={cn(
                      'rounded-lg border px-2 py-2 text-left text-sm transition-colors hover:border-accent',
                      quota === 0 ? 'border-red-200 bg-red-50 text-red-700' : 'border-(--border-color) bg-white',
                      own && 'ring-2 ring-accent/40',
                    )}
                  >
                    <span className="block font-semibold">{start}</span>
                    <span className="block text-xs">{quotaText(quota)}</span>
                  </button>
                );
              })}
            </div>
          )}
        </CardContent>
      </Card>

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <h3 className="px-5 pt-4 font-semibold text-(--text-primary)">Quota riêng 30 ngày tới</h3>
          <table className="table">
            <thead>
              <tr>
                <th>Ngày</th>
                <th>Khung</th>
                <th>Nhóm</th>
                <th className="text-right">Quota</th>
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {upcoming.data?.length ? (
                upcoming.data.map((q) => (
                  <tr key={q.slotQuotaId}>
                    <td>{formatDate(q.slotDate)}</td>
                    <td>{q.slotStart}</td>
                    <td>{QUOTA_GROUP_LABELS[q.serviceGroup]}</td>
                    <td className="text-right">{quotaText(q.quota)}</td>
                    <td>
                      <div className="flex justify-end">
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => removeUpcoming(q)}
                          aria-label={`Bỏ quota riêng ${formatDate(q.slotDate)} ${q.slotStart} ${QUOTA_GROUP_LABELS[q.serviceGroup]}`}
                          title="Bỏ quota riêng"
                          className="text-red-500 hover:text-red-600"
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))
              ) : (
                <EmptyRow colSpan={5} loading={upcoming.isLoading} />
              )}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {editingSlot && (
        <SlotQuotaForm
          branchId={branchId}
          group={group}
          date={date}
          start={editingSlot}
          override={overrideAt(editingSlot)}
          effective={effective(editingSlot)}
          onClose={() => setEditingSlot(null)}
          onDone={(text) => {
            setEditingSlot(null);
            setNotice({ kind: 'success', text });
          }}
        />
      )}
    </div>
  );
}

function DefaultQuotaRow({
  branchId,
  group,
  current,
  onDone,
}: {
  branchId: number;
  group: QuotaGroup;
  current: number | null;
  onDone: (notice: Notice) => void;
}) {
  const label = QUOTA_GROUP_LABELS[group];
  const [value, setValue] = useState('');
  const save = useBranchMutation((quota: number) => branchApi.setQuotaDefault(branchId, group, quota));

  const submit = async () => {
    if (!validQuota(value)) return onDone({ kind: 'error', text: `Quota phải là số nguyên từ 0 đến ${MAX_QUOTA}.` });
    try {
      const saved = await save.mutateAsync(Number(value));
      setValue('');
      onDone({ kind: 'success', text: `Đã đặt quota mặc định ${label}: ${saved.defaultQuota}` });
    } catch (e) {
      onDone({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  return (
    <div className="flex flex-wrap items-center gap-3">
      <span className="w-28 text-sm font-medium text-(--text-primary)">{label}</span>
      <span className="w-56 text-sm text-(--text-secondary)">
        {current === null ? 'Chưa đặt, mỗi khung nhận 1 lượt' : `Đang đặt: ${current} lượt / khung`}
      </span>
      <Input
        aria-label={`Quota mặc định ${label}`}
        type="number"
        min={0}
        max={MAX_QUOTA}
        value={value}
        placeholder={String(current ?? FALLBACK_QUOTA)}
        onChange={(e) => setValue(e.target.value)}
        className="w-28!"
      />
      <Button size="sm" variant="outline" onClick={submit} disabled={save.isPending}>
        {`Lưu quota ${label}`}
      </Button>
    </div>
  );
}

function SlotQuotaForm({
  branchId,
  group,
  date,
  start,
  override,
  effective,
  onClose,
  onDone,
}: {
  branchId: number;
  group: QuotaGroup;
  date: string;
  start: string;
  override?: SlotQuota;
  effective: number;
  onClose: () => void;
  onDone: (notice: string) => void;
}) {
  const [value, setValue] = useState(String(effective));
  const [error, setError] = useState('');
  const save = useBranchMutation((quota: number) =>
    branchApi.setSlotQuota(branchId, { serviceGroup: group, slotDate: date, slotStart: start, quota }),
  );
  const remove = useBranchMutation(() => branchApi.deleteSlotQuota(branchId, override!.slotQuotaId));

  const submit = async () => {
    if (!validQuota(value)) return setError(`Quota phải là số nguyên từ 0 đến ${MAX_QUOTA}.`);
    try {
      const quota = Number(value);
      await save.mutateAsync(quota);
      onDone(quota === 0 ? `Đã khóa khung ${start} ngày ${formatDate(date)}` : `Đã đặt quota riêng ${quota} cho khung ${start} ngày ${formatDate(date)}`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  const clear = async () => {
    try {
      await remove.mutateAsync(undefined);
      onDone(`Đã bỏ quota riêng khung ${start} ngày ${formatDate(date)}, về quota mặc định`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title={`Quota riêng khung ${start} ngày ${formatDate(date)}`}
      description={`${QUOTA_GROUP_LABELS[group]}. Giảm quota không ảnh hưởng lịch đã đặt, chỉ chặn đặt mới.`}
      onSubmit={submit}
      submitText="Lưu quota riêng"
      loading={save.isPending || remove.isPending}
      size="sm"
    >
      <Field label="Quota (0 = khóa khung)">
        {(id) => <Input id={id} type="number" min={0} max={MAX_QUOTA} value={value} onChange={(e) => setValue(e.target.value)} />}
      </Field>
      {override && (
        <Button type="button" variant="outline" size="sm" onClick={clear}>
          Bỏ quota riêng
        </Button>
      )}
      <FormError message={error} />
    </FormModal>
  );
}
