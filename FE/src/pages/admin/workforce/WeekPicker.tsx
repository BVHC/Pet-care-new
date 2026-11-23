import { ChevronLeft, ChevronRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { addDays } from '@/shared/utils/branch-schedule';
import { vnDate } from '@/shared/utils/vn-time';
import { weekRange, weekStartOf } from './registration';

const dateInput = 'h-10 rounded-lg border border-(--color-border-default) bg-white px-3 text-sm';

/** Chọn tuần (thứ Hai → Chủ nhật): Tuần trước / Tuần này / Tuần sau, hoặc một ngày bất kỳ được quy về thứ Hai của tuần đó. */
export function WeekPicker({ weekStart, onChange }: { weekStart: string; onChange: (weekStart: string) => void }) {
  return (
    <div className="flex flex-wrap items-center gap-3">
      <Button size="sm" variant="outline" onClick={() => onChange(addDays(weekStart, -7))}>
        <ChevronLeft className="h-4 w-4" aria-hidden /> Tuần trước
      </Button>
      <Button size="sm" variant="outline" onClick={() => onChange(weekStartOf(vnDate()))}>
        Tuần này
      </Button>
      <Button size="sm" variant="outline" onClick={() => onChange(addDays(weekStart, 7))}>
        Tuần sau <ChevronRight className="h-4 w-4" aria-hidden />
      </Button>
      <input
        type="date"
        aria-label="Chọn ngày trong tuần"
        className={dateInput}
        value={weekStart}
        onChange={(e) => e.target.value && onChange(weekStartOf(e.target.value))}
      />
      <span aria-live="polite" className="text-sm font-medium text-(--text-primary)">{`Tuần ${weekRange(weekStart)}`}</span>
    </div>
  );
}
