import { useState } from 'react';
import { CalendarX } from 'lucide-react';
import { formatVnDateTime } from '@/components/appointments/format';
import { NoticeBar, type Notice } from '@/components/admin/form-ui';
import { QueryError, RowsSkeleton, StatePanel } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { StatusBadge } from '@/components/ui/modal-templates';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { workforceApi } from '@/shared/api/workforce.api';
import { useMyShiftRegistration, useShiftDefinitions, useWorkforceMutation } from '@/shared/hooks/useWorkforce';
import type { ShiftRegistration, ShiftRegistrationItem } from '@/shared/types/workforce';
import { DAY_LABELS, isoDayOfWeek } from '@/shared/utils/branch-schedule';
import { shiftTime } from './labels';
import { gridShifts, isPast, keyOf, nextWeekStart, weekDays, weekRange, type GridShift } from './registration';
import { WeekPicker } from './WeekPicker';

type SaveArgs = { weekStart: string; items: ShiftRegistrationItem[] };

/**
 * Đăng ký ca theo tuần của tôi (UC35, workforce-v1 #17 + #15): lưới 7 ngày × các ca đang dùng, mặc định tuần kế tiếp. Lưu là THAY CẢ TUẦN: chỉ gửi ô tự đăng ký (SELF);
 * ô do quản lý xếp (MANAGER) khóa, không gửi nhưng được tính vào định mức (BR-NS-12). Hạn đăng ký là 00:00 thứ Hai của tuần (BR-NS-11): sau hạn chỉ xem.
 * Thiếu định mức vẫn gửi được: máy chủ từ chối và lỗi hiện nguyên văn.
 */
export function MyRegistration() {
  const [weekStart, setWeekStart] = useState(nextWeekStart);
  const [notice, setNotice] = useState<Notice | null>(null);
  const definitions = useShiftDefinitions({ activeOnly: true });
  const query = useMyShiftRegistration(weekStart);
  // `keepPreviousData` giữ dữ liệu của tuần vừa xem trong lúc tải tuần mới: không dùng lại cho tuần khác.
  const data = query.data?.weekStart === weekStart ? query.data : undefined;
  const shifts = data && definitions.data ? gridShifts(definitions.data, data.assignments) : [];

  let body;
  if (!data && query.isError) body = <QueryError error={toApiError(query.error)} onRetry={() => query.refetch()} what="đăng ký ca theo tuần" />;
  else if (!definitions.data && definitions.isError) body = <QueryError error={toApiError(definitions.error)} onRetry={() => definitions.refetch()} what="danh sách ca làm việc" />;
  else if (!data || !definitions.data) body = <RowsSkeleton rows={4} />;
  else if (shifts.length === 0) body = <StatePanel icon={CalendarX} title="Chi nhánh chưa có ca đang dùng" body="Quản lý chi nhánh cần tạo ca làm việc trước khi bạn đăng ký." />;
  else body = <RegistrationEditor key={data.weekStart} data={data} shifts={shifts} onNotice={setNotice} />;

  return (
    <section aria-labelledby="my-registration-title" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="my-registration-title" className="text-lg font-semibold text-(--text-primary)">
          Đăng ký ca theo tuần
        </h2>
        <WeekPicker
          weekStart={weekStart}
          onChange={(w) => {
            setWeekStart(w);
            setNotice(null);
          }}
        />
      </div>
      <NoticeBar notice={notice} />
      <Card className="card">
        <CardContent className="overflow-x-auto p-0">{body}</CardContent>
      </Card>
    </section>
  );
}

function RegistrationEditor({ data, shifts, onNotice }: { data: ShiftRegistration; shifts: GridShift[]; onNotice: (n: Notice | null) => void }) {
  const own = data.assignments.filter((a) => a.source === 'SELF').map((a) => keyOf(a.workDate, a.shiftDefinitionId));
  const managed = new Set(data.assignments.filter((a) => a.source === 'MANAGER').map((a) => keyOf(a.workDate, a.shiftDefinitionId)));
  // Ô đã chọn khởi tạo từ phân ca SELF của máy chủ và chỉ nạp lại khi chính tập đó đổi (sau khi lưu). Lệnh ghi lỗi cũng làm mới #17 nhưng tập không đổi nên các ô đang chọn được giữ.
  const serverKey = [...own].sort().join(',');
  const [syncedWith, setSyncedWith] = useState(serverKey);
  const [selected, setSelected] = useState(() => new Set(own));
  if (syncedWith !== serverKey) {
    setSyncedWith(serverKey);
    setSelected(new Set(own));
  }
  const save = useWorkforceMutation(({ weekStart, items }: SaveArgs) => workforceApi.putMyShiftRegistration(weekStart, { items }));
  const days = weekDays(data.weekStart);
  const locked = isPast(data.editableUntil);
  const total = selected.size + managed.size;
  const missing = Math.max(0, data.requiredShifts - total);

  const toggle = (key: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (!next.delete(key)) next.add(key);
      return next;
    });
    onNotice(null);
  };

  const submit = async () => {
    const items = days.flatMap((workDate) => shifts.filter((s) => selected.has(keyOf(workDate, s.shiftDefinitionId))).map((s) => ({ workDate, shiftDefinitionId: s.shiftDefinitionId })));
    try {
      const saved = await save.mutateAsync({ weekStart: data.weekStart, items });
      onNotice({ kind: 'success', text: `Đã lưu đăng ký tuần ${weekRange(saved.weekStart)}: ${saved.shiftCount} / ${saved.requiredShifts} ca.` });
    } catch (e) {
      onNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  return (
    <>
      <table className="table min-w-[860px]" aria-label="Đăng ký ca theo tuần">
        <thead>
          <tr>
            <th scope="col">Ca</th>
            {days.map((d) => (
              <th key={d} scope="col" className="text-center!">
                {DAY_LABELS[isoDayOfWeek(d)]}
                <span className="block text-xs font-normal text-(--text-secondary)">{`${d.slice(8)}/${d.slice(5, 7)}`}</span>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {shifts.map((s) => (
            <tr key={s.shiftDefinitionId}>
              <th scope="row" className="whitespace-nowrap text-left! font-medium text-(--text-primary)">
                {s.name}
                <span className="block text-xs font-normal tabular-nums text-(--text-secondary)">{shiftTime(s)}</span>
              </th>
              {days.map((d) => {
                const key = keyOf(d, s.shiftDefinitionId);
                const byManager = managed.has(key);
                return (
                  <td key={d} className="text-center align-middle">
                    <label className="inline-flex flex-col items-center gap-1">
                      <input
                        type="checkbox"
                        className="h-4 w-4 accent-[#a43324]"
                        checked={byManager || selected.has(key)}
                        disabled={byManager || locked}
                        aria-label={`${s.name}, ${d}${byManager ? ', quản lý xếp' : ''}`}
                        onChange={() => toggle(key)}
                      />
                      {byManager && <StatusBadge status="Quản lý xếp" variant="neutral" />}
                    </label>
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <div className="space-y-1.5 border-t border-(--color-border-default) px-4 py-3 text-sm">
        <p className="text-(--text-primary)">
          Đang chọn <strong className="tabular-nums">{`${total} / ${data.requiredShifts} ca`}</strong>
          {managed.size > 0 && ` (gồm ${managed.size} ca do quản lý xếp)`}
        </p>
        {!locked && missing > 0 && <p className="text-amber-700">{`Còn thiếu ${missing} ca so với định mức. Nếu lưu lúc này, hệ thống sẽ từ chối cho tới khi đủ.`}</p>}
        <p id="registration-deadline" className="text-(--text-secondary)">
          {locked
            ? `Đã quá hạn đăng ký (hạn là ${formatVnDateTime(data.editableUntil)}): chỉ còn xin nghỉ hoặc đổi ca ở lịch phân ca phía trên.`
            : `Hạn đăng ký: ${formatVnDateTime(data.editableUntil)} (giờ Việt Nam). Lưu sẽ thay toàn bộ đăng ký của tuần.`}
        </p>
        <div className="flex justify-end">
          <Button onClick={submit} disabled={locked || save.isPending} aria-describedby="registration-deadline">
            Lưu đăng ký
          </Button>
        </div>
      </div>
    </>
  );
}
