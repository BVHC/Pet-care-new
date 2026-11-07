import { useState } from 'react';
import { Field } from '@/components/admin/form-ui';
import { Input } from '@/components/ui/input';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { workforceApi } from '@/shared/api/workforce.api';
import { useWorkforceMutation } from '@/shared/hooks/useWorkforce';
import { WORKFORCE_LIMITS, type ShiftDefinition, type UpdateShiftDefinitionRequest } from '@/shared/types/workforce';
import { FormDialog } from './FormDialog';

interface Props {
  /** Vắng = tạo ca mới (#2); có = sửa (#3, chỉ gửi trường đã đổi). */
  definition?: ShiftDefinition;
  onClose: () => void;
  onDone: (message: string) => void;
}

/** BR-NS-01 do máy chủ kiểm: tên trùng, giờ bắt đầu phải nhỏ hơn giờ kết thúc, ca đã có phân ca thì không sửa giờ. */
export function ShiftDefinitionDialog({ definition, onClose, onDone }: Props) {
  const [name, setName] = useState(definition?.name ?? '');
  const [startTime, setStartTime] = useState(definition?.startTime ?? '');
  const [endTime, setEndTime] = useState(definition?.endTime ?? '');
  const [error, setError] = useState('');
  const create = useWorkforceMutation(workforceApi.createShiftDefinition);
  const update = useWorkforceMutation(({ id, body }: { id: number; body: UpdateShiftDefinitionRequest }) => workforceApi.updateShiftDefinition(id, body));
  const touch = <T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    setError('');
  };

  const submit = async () => {
    const trimmed = name.trim();
    if (!trimmed || !startTime || !endTime) return setError('Nhập tên ca, giờ bắt đầu và giờ kết thúc.');
    try {
      if (definition) {
        const body: UpdateShiftDefinitionRequest = {
          ...(trimmed !== definition.name && { name: trimmed }),
          ...(startTime !== definition.startTime && { startTime }),
          ...(endTime !== definition.endTime && { endTime }),
        };
        if (Object.keys(body).length > 0) await update.mutateAsync({ id: definition.shiftDefinitionId, body });
        onDone(`Đã cập nhật ${trimmed}.`);
      } else {
        await create.mutateAsync({ name: trimmed, startTime, endTime });
        onDone(`Đã tạo ${trimmed}.`);
      }
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormDialog
      title={definition ? 'Sửa ca làm việc' : 'Thêm ca làm việc'}
      description="Giờ làm theo giờ Việt Nam. Ca đã có phân ca thì chỉ đổi được tên."
      submitText="Lưu"
      pending={create.isPending || update.isPending}
      error={error}
      onSubmit={submit}
      onClose={onClose}
    >
      <Field label="Tên ca">
        {(id) => <Input id={id} maxLength={WORKFORCE_LIMITS.name} value={name} onChange={(e) => touch(setName)(e.target.value)} />}
      </Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Giờ bắt đầu">
          {(id) => <Input id={id} type="time" value={startTime} onChange={(e) => touch(setStartTime)(e.target.value)} />}
        </Field>
        <Field label="Giờ kết thúc">
          {(id) => <Input id={id} type="time" value={endTime} onChange={(e) => touch(setEndTime)(e.target.value)} />}
        </Field>
      </div>
    </FormDialog>
  );
}
