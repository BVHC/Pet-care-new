// Chọn người phụ trách cho lượt (BR-TN-05: VET cho Khám/Tiêm, CARETAKER cho Thẩm mỹ), người online xếp trước, offline vẫn chọn được kèm cảnh báo (BR-TN-06).
import { useId } from 'react';
import { useAssignableStaff } from '../../../shared/hooks/useVisits';
import { ASSIGNEE_ROLE_OF_GROUP, type VisitServiceGroup } from '../../../shared/types/visit';
import { QueryError } from '../ui';

const ROLE_NAME = { VET: 'bác sĩ thú y', CARETAKER: 'nhân viên chăm sóc' } as const;
const field = 'h-10 w-full rounded-lg border border-(--ws-line-strong) bg-(--ws-surface) px-3 text-sm text-(--ws-ink)';

export const STAFF_PICKER_BLOCKED = 'Danh sách nhân viên chờ BE-1 mở GET /api/staff cho lễ tân';

export function AssigneePicker({
  group,
  value,
  onChange,
  branchId,
}: {
  group: VisitServiceGroup;
  value: number | null;
  onChange: (accountId: number | null) => void;
  branchId?: number;
}) {
  const id = useId();
  const role = ASSIGNEE_ROLE_OF_GROUP[group];
  const { staff, staffPickerBlocked, isLoading, isError, error, refetch } = useAssignableStaff(role, true, branchId);

  // Lễ tân là người duy nhất được gán (UC45) mà BE thật đang trả 403 cho `GET /api/staff` của lễ tân (docs/testcases/T36-fe.md A.7).
  if (staffPickerBlocked) {
    return (
      <p role="alert" className="rounded-lg bg-(--ws-urgent-bg) px-3 py-2 text-sm font-medium text-(--ws-urgent-ink)">
        {STAFF_PICKER_BLOCKED}
      </p>
    );
  }
  if (isError) return <QueryError error={error} onRetry={() => refetch()} what="danh sách nhân viên" />;
  if (isLoading) return <p role="status" className="text-sm text-(--ws-ink-2)">Đang tải danh sách nhân viên…</p>;

  const picked = staff.find((s) => s.accountId === value);
  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={id} className="text-[13px] font-semibold">
        Người phụ trách ({ROLE_NAME[role]})
      </label>
      <select
        id={id}
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value ? Number(e.target.value) : null)}
        className={field}
      >
        <option value="">Chọn nhân viên</option>
        {staff.map((s) => (
          <option key={s.accountId} value={s.accountId}>
            {s.fullName} · {s.online ? 'trực tuyến' : 'ngoại tuyến'}
          </option>
        ))}
      </select>
      {picked?.offlineWarning && (
        <p role="status" className="text-sm text-(--ws-wait-ink)">
          {picked.fullName} đang ngoại tuyến. Vẫn gán được, nhưng người này có thể chưa thấy lượt ngay.
        </p>
      )}
    </div>
  );
}
