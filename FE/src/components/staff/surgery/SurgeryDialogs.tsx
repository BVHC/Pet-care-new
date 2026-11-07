// Hộp thoại phẫu thuật (UC50; surgery-v1): lập kế hoạch #1 (VET), lập cam kết #5 (lễ tân), hoàn tất #7 (VET), hủy / dừng #8 (VET, lễ tân khi chưa bắt đầu).
// Lỗi BR-KB-08…11 và 409 của BE hiện ngay trong hộp thoại, không đóng hộp.
import { useState } from 'react';
import { Field } from '@/components/admin/form-ui';
import { ClinicalDialog, inputClass, textareaClass } from '@/components/clinical/ClinicalDialog';
import { useClinicalServices } from '@/components/clinical/useClinicalServices';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useCancelSurgery, useCompleteSurgery, useConsentSurgery, usePlanSurgery } from '@/shared/hooks/useSurgery';
import { SURGERY_LIMITS } from '@/shared/types/surgery';

interface DoneProps {
  onClose: () => void;
  onDone: (message: string) => void;
}
/** Chỉ cần thú và dịch vụ để người thao tác biết mình đang xử lý ca nào (cả `Surgery` lẫn `SurgerySummary`). */
interface Target {
  surgeryId: number;
  petName: string;
  serviceName: string;
}
const whichSurgery = (s: Target) => `${s.petName} · ${s.serviceName}`;
const area = (id: string, value: string, set: (v: string) => void, rows: number, max?: number) => (
  <textarea id={id} rows={rows} maxLength={max} className={textareaClass} value={value} onChange={(e) => set(e.target.value)} />
);

export function PlanSurgeryDialog({ visitId, petName, onClose, onDone }: DoneProps & { visitId: number; petName: string }) {
  const [serviceId, setServiceId] = useState('');
  const [planNote, setPlanNote] = useState('');
  const [error, setError] = useState('');
  const { services, isPending } = useClinicalServices('SURGERY');
  const plan = usePlanSurgery();
  const submit = async () => {
    if (!serviceId) return setError('Chọn dịch vụ phẫu thuật.');
    try {
      await plan.mutateAsync({ visitId, serviceId: Number(serviceId), planNote: planNote.trim() || null });
      onDone(`Đã lập kế hoạch phẫu thuật cho ${petName}, lễ tân chi nhánh được báo để lập cam kết.`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };
  return (
    <ClinicalDialog
      title="Lập kế hoạch phẫu thuật"
      description={petName}
      onClose={onClose}
      onSubmit={() => void submit()}
      submitText="Lập kế hoạch"
      pending={plan.isPending}
      submitDisabled={services.length === 0}
      error={error}
    >
      <Field label="Dịch vụ phẫu thuật" hint={!isPending && services.length === 0 ? 'Danh mục chưa có dịch vụ phẫu thuật đang kinh doanh nên chưa lập kế hoạch được.' : undefined}>
        {(id) => (
          <select id={id} className={inputClass} value={serviceId} onChange={(e) => { setServiceId(e.target.value); setError(''); }}>
            <option value="">Chọn dịch vụ</option>
            {services.map((s) => (
              <option key={s.serviceId} value={s.serviceId}>
                {s.name}
              </option>
            ))}
          </select>
        )}
      </Field>
      <Field label="Ghi chú kế hoạch (không bắt buộc)" hint={`${planNote.length}/${SURGERY_LIMITS.planNote}`}>
        {(id) => area(id, planNote, setPlanNote, 3, SURGERY_LIMITS.planNote)}
      </Field>
    </ClinicalDialog>
  );
}

/** Cam kết là bản chụp, không sửa sau khi lập (BR-KB-09): nhập đủ nội dung và người ký rồi mới gửi. */
export function ConsentSurgeryDialog({ surgery, onClose, onDone }: DoneProps & { surgery: Target }) {
  const [consentText, setConsentText] = useState('');
  const [signerName, setSignerName] = useState('');
  const [signerRelation, setSignerRelation] = useState('');
  const [error, setError] = useState('');
  const consent = useConsentSurgery();
  const submit = async () => {
    if (!consentText.trim() || !signerName.trim() || !signerRelation.trim()) return setError('Nhập đủ nội dung cam kết, người ký và quan hệ với chủ thú.');
    try {
      await consent.mutateAsync({ surgeryId: surgery.surgeryId, consentText: consentText.trim(), signerName: signerName.trim(), signerRelation: signerRelation.trim() });
      onDone(`Đã lập cam kết phẫu thuật cho ${surgery.petName}.`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };
  return (
    <ClinicalDialog
      title="Lập cam kết phẫu thuật"
      description={whichSurgery(surgery)}
      onClose={onClose}
      onSubmit={() => void submit()}
      submitText="Lưu cam kết"
      pending={consent.isPending}
      error={error}
    >
      <Field label="Nội dung cam kết (rủi ro, chi phí dự kiến, phương pháp)">{(id) => area(id, consentText, setConsentText, 5)}</Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Người ký">
          {(id) => <input id={id} maxLength={SURGERY_LIMITS.signerName} className={inputClass} value={signerName} onChange={(e) => setSignerName(e.target.value)} />}
        </Field>
        <Field label="Quan hệ với chủ thú">
          {(id) => <input id={id} maxLength={SURGERY_LIMITS.signerRelation} className={inputClass} placeholder="Chủ thú, người đại diện…" value={signerRelation} onChange={(e) => setSignerRelation(e.target.value)} />}
        </Field>
      </div>
    </ClinicalDialog>
  );
}

export function CompleteSurgeryDialog({ surgery, onClose, onDone }: DoneProps & { surgery: Target }) {
  const [resultNote, setResultNote] = useState('');
  const [error, setError] = useState('');
  const complete = useCompleteSurgery();
  const submit = async () => {
    if (!resultNote.trim()) return setError('Nhập ghi chú kết quả phẫu thuật.');
    try {
      await complete.mutateAsync({ surgeryId: surgery.surgeryId, resultNote: resultNote.trim() });
      onDone(`Đã hoàn tất phẫu thuật cho ${surgery.petName}, dịch vụ đã vào Order của lượt.`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };
  return (
    <ClinicalDialog title="Hoàn tất phẫu thuật" description={whichSurgery(surgery)} onClose={onClose} onSubmit={() => void submit()} submitText="Hoàn tất" pending={complete.isPending} error={error}>
      <Field label="Kết quả phẫu thuật">{(id) => area(id, resultNote, setResultNote, 4)}</Field>
    </ClinicalDialog>
  );
}

export function CancelSurgeryDialog({ surgery, onClose, onDone }: DoneProps & { surgery: Target }) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const cancel = useCancelSurgery();
  const submit = async () => {
    if (!reason.trim()) return setError('Nhập lý do hủy hoặc dừng.');
    try {
      await cancel.mutateAsync({ surgeryId: surgery.surgeryId, reason: reason.trim() });
      onDone(`Đã hủy phẫu thuật của ${surgery.petName}.`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };
  return (
    <ClinicalDialog
      title="Hủy phẫu thuật"
      description={whichSurgery(surgery)}
      onClose={onClose}
      onSubmit={() => void submit()}
      submitText="Hủy phẫu thuật"
      cancelText="Quay lại"
      destructive
      pending={cancel.isPending}
      error={error}
    >
      <Field label="Lý do" hint={`${reason.length}/${SURGERY_LIMITS.reason}`}>
        {(id) => area(id, reason, setReason, 3, SURGERY_LIMITS.reason)}
      </Field>
    </ClinicalDialog>
  );
}
