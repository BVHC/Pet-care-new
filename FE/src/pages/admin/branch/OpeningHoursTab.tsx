import { useState } from 'react';
import { Plus, X } from 'lucide-react';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { FormModal } from '../../../components/ui/modal-templates';
import { Field, FormError, NoticeBar, type Notice } from '../../../components/admin/form-ui';
import { formatDate } from '../../../lib/utils';
import { branchApi } from '../../../shared/api/branch.api';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useBranchMutation, useOpeningHours } from '../../../shared/hooks/useBranch';
import type { Branch, DayHours, OpeningHoursVersion, ScheduleImpact, TimeRange } from '../../../shared/types/branch';
import { DAY_LABELS, WEEK_DAYS, addDays, hoursError, isEmptyImpact, localIsoDate, versionOn } from '../../../shared/utils/branch-schedule';
import { ImpactList } from './ImpactList';
import { WeekHours } from './WeekHours';

/** UC14 — giờ mở cửa theo bản có ngày hiệu lực (BR-CN-02, 04). Chỉ BRANCH_MANAGER của chi nhánh được đặt giờ mới. */
export function OpeningHoursTab({ branch, canEdit }: { branch: Branch; canEdit: boolean }) {
  const hours = useOpeningHours(branch.branchId);
  const [editing, setEditing] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const versions = hours.data ?? [];
  const current = versionOn(versions, localIsoDate());

  return (
    <div className="space-y-4">
      {canEdit && (
        <div className="flex justify-end">
          <Button onClick={() => setEditing(true)}>
            <Plus className="h-4 w-4" />
            Đặt giờ mở cửa mới
          </Button>
        </div>
      )}
      <NoticeBar notice={notice} />
      {!hours.isLoading && versions.length === 0 && (
        <Card className="card">
          <CardContent className="p-6 text-(--text-secondary)">
            Chưa cấu hình giờ mở cửa.
            {branch.status === 'DRAFT' && ' Chi nhánh cần có giờ mở cửa mới kích hoạt được (BR-CN-01).'}
          </CardContent>
        </Card>
      )}
      <div className="grid gap-4 lg:grid-cols-2">
        {versions.map((v) => (
          <Card key={v.effectiveFrom} className="card">
            <CardContent className="p-5">
              <h3 className="mb-3 font-semibold text-(--text-primary)">
                {`${v === current ? 'Đang áp dụng từ' : 'Áp dụng từ'} ${formatDate(v.effectiveFrom)}`}
              </h3>
              <WeekHours version={v} />
            </CardContent>
          </Card>
        ))}
      </div>
      {editing && (
        <HoursEditor
          branch={branch}
          versions={versions}
          onClose={() => setEditing(false)}
          onSaved={(text) => {
            setEditing(false);
            setNotice({ kind: 'success', text });
          }}
        />
      )}
    </div>
  );
}

const NEW_RANGES: TimeRange[] = [
  { open: '08:00', close: '12:00' },
  { open: '13:30', close: '17:30' },
];

/** Điền sẵn từ bản gần nhất; chưa có bản nào thì T2–T7 hai ca, Chủ nhật nghỉ. */
function prefill(versions: OpeningHoursVersion[]): DayHours[] {
  const latest = versions[versions.length - 1];
  return WEEK_DAYS.map((day) => ({
    dayOfWeek: day,
    ranges: latest
      ? (latest.days.find((d) => d.dayOfWeek === day)?.ranges ?? []).map((r) => ({ ...r }))
      : day === 7
        ? []
        : NEW_RANGES.map((r) => ({ ...r })),
  }));
}

function HoursEditor({
  branch,
  versions,
  onClose,
  onSaved,
}: {
  branch: Branch;
  versions: OpeningHoursVersion[];
  onClose: () => void;
  onSaved: (notice: string) => void;
}) {
  const today = localIsoDate();
  // BR-CN-04: sớm nhất là ngày mai; chi nhánh nháp được áp dụng từ hôm nay (branch-v1 A2).
  const minDate = branch.status === 'DRAFT' ? today : addDays(today, 1);
  const [effectiveFrom, setEffectiveFrom] = useState(minDate);
  const [days, setDays] = useState(() => prefill(versions));
  const [impact, setImpact] = useState<ScheduleImpact | null>(null);
  const [cancel, setCancel] = useState(false);
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState('');
  const save = useBranchMutation((cancelAffected: boolean) =>
    branchApi.setOpeningHours(branch.branchId, {
      effectiveFrom,
      days,
      cancelAffected,
    }),
  );

  /** Mọi thay đổi sau khi đã xem trước thì phải xem trước lại. */
  const edit = (next: DayHours[]) => {
    setDays(next);
    setImpact(null);
    setCancel(false);
    setError('');
  };
  const editDay = (day: number, change: (ranges: TimeRange[]) => TimeRange[]) =>
    edit(days.map((d) => (d.dayOfWeek === day ? { ...d, ranges: change(d.ranges) } : d)));

  const submit = async () => {
    const invalid =
      (!effectiveFrom && 'Chọn ngày hiệu lực.') ||
      (effectiveFrom < minDate &&
        (branch.status === 'DRAFT' ? 'Ngày hiệu lực không được ở quá khứ.' : 'Ngày hiệu lực của giờ mở cửa mới sớm nhất là ngày mai.')) ||
      hoursError(days);
    if (invalid) return setError(invalid);
    setError('');
    try {
      let affected = impact;
      if (!affected) {
        setChecking(true);
        affected = await branchApi.previewOpeningHours(branch.branchId, { effectiveFrom, days }).finally(() => setChecking(false));
        if (!isEmptyImpact(affected)) return setImpact(affected);
      } else if (!cancel) {
        return setError('Chọn hủy hàng loạt để lưu, hoặc chỉnh lại giờ để không ảnh hưởng lịch đã đặt.');
      }
      const cancelled = !isEmptyImpact(affected);
      await save.mutateAsync(cancelled);
      onSaved(
        `Đã lưu giờ mở cửa áp dụng từ ${formatDate(effectiveFrom)}` +
          (cancelled ? `, hủy ${affected.appointments.length} lịch hẹn và ${affected.boardingBookings.length} đặt chỗ lưu trú` : ''),
      );
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  const timeClass =
    'h-9 rounded-lg border border-(--color-border-default) bg-white px-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent';

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title="Đặt giờ mở cửa mới"
      description="Mỗi ngày tối đa 2 khoảng không chồng nhau; ngày không có khoảng nào là ngày nghỉ cố định. Cùng ngày hiệu lực thì ghi đè bản cũ."
      onSubmit={submit}
      submitText={impact ? 'Lưu và hủy hàng loạt' : 'Kiểm tra và lưu'}
      loading={checking || save.isPending}
      size="xl"
    >
      <Field
        label="Ngày hiệu lực"
        hint={branch.status === 'DRAFT' ? 'Chi nhánh nháp: được chọn từ hôm nay.' : 'Sớm nhất là ngày mai (BR-CN-04).'}
      >
        {(id) => (
          <Input
            id={id}
            type="date"
            min={minDate}
            value={effectiveFrom}
            onChange={(e) => {
              setEffectiveFrom(e.target.value);
              setImpact(null);
              setCancel(false);
            }}
            className="w-48!"
          />
        )}
      </Field>
      <div className="divide-y divide-(--border-color) rounded-lg border border-(--border-color)">
        {days.map(({ dayOfWeek: day, ranges }) => {
          const label = DAY_LABELS[day];
          return (
            <div key={day} className="flex items-start gap-3 px-3 py-2">
              <span className="w-20 shrink-0 pt-2 text-sm font-medium text-(--text-primary)">{label}</span>
              <div className="flex flex-1 flex-wrap items-center gap-2">
                {ranges.length === 0 && <span className="py-2 text-sm text-(--text-tertiary)">Nghỉ</span>}
                {ranges.map((range, j) => (
                  <span key={j} className="flex items-center gap-1">
                    <input
                      type="time"
                      aria-label={`${label} khoảng ${j + 1}: giờ mở`}
                      value={range.open}
                      onChange={(e) => editDay(day, (rs) => rs.map((r, k) => (k === j ? { ...r, open: e.target.value } : r)))}
                      className={timeClass}
                    />
                    <span className="text-(--text-tertiary)">–</span>
                    <input
                      type="time"
                      aria-label={`${label} khoảng ${j + 1}: giờ đóng`}
                      value={range.close}
                      onChange={(e) => editDay(day, (rs) => rs.map((r, k) => (k === j ? { ...r, close: e.target.value } : r)))}
                      className={timeClass}
                    />
                    <button
                      type="button"
                      aria-label={`Xóa khoảng ${j + 1} ${label}`}
                      onClick={() => editDay(day, (rs) => rs.filter((_, k) => k !== j))}
                      className="rounded p-1 text-(--text-tertiary) hover:bg-(--bg-tertiary) hover:text-red-500"
                    >
                      <X className="h-4 w-4" />
                    </button>
                  </span>
                ))}
                {ranges.length < 2 && (
                  <button
                    type="button"
                    aria-label={`Thêm khoảng ${label}`}
                    onClick={() => editDay(day, (rs) => [...rs, { ...NEW_RANGES[rs.length] }])}
                    className="ml-auto text-xs font-medium text-accent hover:underline"
                  >
                    + Thêm khoảng
                  </button>
                )}
              </div>
            </div>
          );
        })}
      </div>
      {impact && <ImpactList impact={impact} cancel={cancel} onCancelChange={setCancel} />}
      <FormError message={error} />
    </FormModal>
  );
}
