import { useState } from 'react';
import { Field, selectClass } from '@/components/admin/form-ui';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { workforceApi } from '@/shared/api/workforce.api';
import { useWorkforceMutation } from '@/shared/hooks/useWorkforce';
import { SHIFT_REQUEST_TYPES, WORKFORCE_LIMITS, type ShiftAssignment, type ShiftRequestType } from '@/shared/types/workforce';
import { FormDialog, textareaClass } from './FormDialog';
import { REQUEST_TYPE_LABELS, dayLabel, shiftTime } from './labels';

export interface ShiftOption {
  shiftDefinitionId: number;
  shiftName: string;
  startTime: string;
  endTime: string;
}

interface Props {
  assignment: ShiftAssignment;
  /** Ca đích cho đổi ca; rỗng thì chỉ xin nghỉ được. */
  options: ShiftOption[];
  onClose: () => void;
  onDone: (message: string) => void;
}

/** Gửi yêu cầu nghỉ / đổi ca (workforce-v1 #9). BR-NS-04 do máy chủ kiểm: còn đủ 24 giờ, chưa có yêu cầu chờ, ca đích không chồng giờ. */
export function ShiftRequestDialog({ assignment: a, options, onClose, onDone }: Props) {
  const [type, setType] = useState<ShiftRequestType>('LEAVE');
  const [targetId, setTargetId] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const create = useWorkforceMutation(workforceApi.createShiftRequest);

  const submit = async () => {
    if (type === 'SWAP' && !targetId) return setError('Chọn ca muốn đổi sang.');
    if (!reason.trim()) return setError('Nhập lý do.');
    try {
      await create.mutateAsync({
        shiftAssignmentId: a.shiftAssignmentId,
        type,
        ...(type === 'SWAP' && { targetShiftDefinitionId: Number(targetId) }),
        reason: reason.trim(),
      });
      onDone(`Đã gửi yêu cầu ${REQUEST_TYPE_LABELS[type].toLowerCase()} ${a.shiftName} ngày ${a.workDate}. Quản lý sẽ duyệt.`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormDialog
      title="Xin nghỉ / đổi ca"
      description={`${a.shiftName} (${shiftTime(a)}) · ${dayLabel(a.workDate)}`}
      submitText="Gửi yêu cầu"
      pending={create.isPending}
      error={error}
      onSubmit={submit}
      onClose={onClose}
    >
      <fieldset>
        <legend className="mb-1.5 text-sm font-medium text-(--text-primary)">Loại yêu cầu</legend>
        <div className="flex gap-4">
          {SHIFT_REQUEST_TYPES.map((t) => (
            <label key={t} className="flex items-center gap-2 text-sm">
              <input
                type="radio"
                name="shift-request-type"
                checked={type === t}
                disabled={t === 'SWAP' && options.length === 0}
                onChange={() => {
                  setType(t);
                  setError('');
                }}
                className="h-4 w-4 accent-[#a43324]"
              />
              {REQUEST_TYPE_LABELS[t]}
            </label>
          ))}
        </div>
        {options.length === 0 && <p className="mt-1 text-xs text-(--text-tertiary)">Chưa có ca khác trong lịch của bạn để đổi sang.</p>}
      </fieldset>
      {type === 'SWAP' && (
        <Field label="Đổi sang ca">
          {(id) => (
            <select id={id} className={selectClass} value={targetId} onChange={(e) => { setTargetId(e.target.value); setError(''); }}>
              <option value="">Chọn ca</option>
              {options.map((o) => (
                <option key={o.shiftDefinitionId} value={o.shiftDefinitionId}>
                  {o.shiftName} ({shiftTime(o)})
                </option>
              ))}
            </select>
          )}
        </Field>
      )}
      <Field label="Lý do" hint={`${reason.length}/${WORKFORCE_LIMITS.reason}`}>
        {(id) => (
          <textarea id={id} rows={3} maxLength={WORKFORCE_LIMITS.reason} className={textareaClass} value={reason} onChange={(e) => { setReason(e.target.value); setError(''); }} />
        )}
      </Field>
    </FormDialog>
  );
}
