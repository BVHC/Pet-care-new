// Thanh lọc: chi nhánh (chỉ SUPER_MANAGER chọn được; BRANCH_MANAGER chỉ thấy tên chi nhánh mình) và kỳ báo cáo (BR-BC-01).
import { Field, selectClass } from '../../../components/admin/form-ui';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { useStaffBranchId } from '../../../shared/hooks/useStaffBranch';
import type { Branch } from '../../../shared/types/branch';
import { activePreset, PRESET_LABELS, presetPeriod, type Period, type PeriodPreset } from './report-format';

const PRESETS = Object.keys(PRESET_LABELS) as PeriodPreset[];

/** Chỉ mount cho BRANCH_MANAGER: SUPER_MANAGER không có chi nhánh riêng nên khỏi hỏi `GET /me`. */
function OwnBranch({ branchName }: { branchName: (branchId: number) => string }) {
  const branchId = useStaffBranchId();
  return (
    <div>
      <p className="mb-1.5 text-sm font-medium text-(--text-primary)">Chi nhánh</p>
      <p className="flex h-10 items-center text-sm text-(--text-primary)">{branchId == null ? '—' : branchName(branchId)}</p>
    </div>
  );
}

interface Props {
  chainWide: boolean;
  branches: Branch[] | undefined;
  branchName: (branchId: number) => string;
  /** '' = toàn chuỗi. */
  branchId: string;
  onBranch: (branchId: string) => void;
  /** Ẩn phần kỳ ở báo cáo không theo kỳ (tồn kho). */
  withPeriod: boolean;
  period: Period;
  today: string;
  error: string | null;
  onPeriod: (period: Period) => void;
}

export function ReportFilters({ chainWide, branches, branchName, branchId, onBranch, withPeriod, period, today, error, onPeriod }: Props) {
  const active = activePreset(period, today);
  return (
    <section aria-label="Bộ lọc báo cáo" className="card space-y-3 p-4">
      <div className="flex flex-wrap items-end gap-3">
        {chainWide ? (
          <div className="w-56">
            <Field label="Chi nhánh">
              {(id) => (
                <select id={id} className={selectClass} value={branchId} onChange={(e) => onBranch(e.target.value)}>
                  <option value="">Toàn chuỗi</option>
                  {branches?.map((b) => (
                    <option key={b.branchId} value={b.branchId}>
                      {b.name}
                    </option>
                  ))}
                </select>
              )}
            </Field>
          </div>
        ) : (
          <OwnBranch branchName={branchName} />
        )}

        {withPeriod && (
          <>
            <div className="w-44">
              <Field label="Từ ngày">
                {(id) => <Input id={id} type="date" className="h-10 py-0" value={period.from} onChange={(e) => onPeriod({ ...period, from: e.target.value })} />}
              </Field>
            </div>
            <div className="w-44">
              <Field label="Đến ngày">
                {(id) => <Input id={id} type="date" className="h-10 py-0" value={period.to} onChange={(e) => onPeriod({ ...period, to: e.target.value })} />}
              </Field>
            </div>
            <div className="flex flex-wrap gap-2" role="group" aria-label="Chọn nhanh kỳ">
              {PRESETS.map((p) => (
                <Button key={p} size="sm" variant={active === p ? 'default' : 'outline'} aria-pressed={active === p} onClick={() => onPeriod(presetPeriod(p, today))}>
                  {PRESET_LABELS[p]}
                </Button>
              ))}
            </div>
          </>
        )}
      </div>
      {withPeriod && error && (
        <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm font-medium text-red-700">
          {error}
        </p>
      )}
      {!withPeriod && <p className="text-sm text-(--text-secondary)">Báo cáo tồn kho tính tại thời điểm xem, không theo kỳ.</p>}
    </section>
  );
}
