import { useState } from 'react';
import { Field, selectClass } from '@/components/admin/form-ui';
import { Input } from '@/components/ui/input';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { workforceApi } from '@/shared/api/workforce.api';
import { useShiftDefinitions, useWorkforceMutation } from '@/shared/hooks/useWorkforce';
import { ROLE_LABELS } from '@/shared/types/auth';
import type { StaffResponse } from '@/shared/types/identity';
import { vnDate } from '@/shared/utils/vn-time';
import { FormDialog } from './FormDialog';
import { shiftTime } from './labels';

interface Props {
  staff: StaffResponse[];
  /** Ngày gợi ý ban đầu (đầu khoảng đang xem nếu chưa qua). */
  defaultDate: string;
  onClose: () => void;
  onDone: (message: string) => void;
}

/** Phân ca (workforce-v1 #5). BR-NS-02 do máy chủ kiểm: ngày quá khứ, ca ngừng dùng, trùng hoặc chồng giờ với ca khác của nhân viên. */
export function AssignDialog({ staff, defaultDate, onClose, onDone }: Props) {
  const shifts = useShiftDefinitions({ activeOnly: true });
  const [staffId, setStaffId] = useState('');
  const [shiftId, setShiftId] = useState('');
  const [workDate, setWorkDate] = useState(defaultDate);
  const [error, setError] = useState('');
  const assign = useWorkforceMutation(workforceApi.createShiftAssignment);
  const touch = (set: (v: string) => void) => (v: string) => {
    set(v);
    setError('');
  };

  const submit = async () => {
    if (!staffId || !shiftId || !workDate) return setError('Chọn nhân viên, ca và ngày làm.');
    try {
      const a = await assign.mutateAsync({ staffId: Number(staffId), shiftDefinitionId: Number(shiftId), workDate });
      onDone(`Đã phân ${a.shiftName} ngày ${a.workDate} cho ${a.staffName}.`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormDialog title="Phân ca" description="Mỗi nhân viên một ca trong khung giờ không chồng nhau." submitText="Phân ca" pending={assign.isPending} error={error} onSubmit={submit} onClose={onClose}>
      <Field label="Nhân viên">
        {(id) => (
          <select id={id} className={selectClass} value={staffId} onChange={(e) => touch(setStaffId)(e.target.value)}>
            <option value="">Chọn nhân viên</option>
            {staff.map((s) => (
              <option key={s.accountId} value={s.accountId}>
                {s.fullName} · {ROLE_LABELS[s.role]}
              </option>
            ))}
          </select>
        )}
      </Field>
      <Field label="Ca làm việc">
        {(id) => (
          <select id={id} className={selectClass} value={shiftId} onChange={(e) => touch(setShiftId)(e.target.value)}>
            <option value="">Chọn ca</option>
            {(shifts.data ?? []).map((d) => (
              <option key={d.shiftDefinitionId} value={d.shiftDefinitionId}>
                {d.name} ({shiftTime(d)})
              </option>
            ))}
          </select>
        )}
      </Field>
      <Field label="Ngày làm">
        {(id) => <Input id={id} type="date" min={vnDate()} value={workDate} onChange={(e) => touch(setWorkDate)(e.target.value)} />}
      </Field>
    </FormDialog>
  );
}
