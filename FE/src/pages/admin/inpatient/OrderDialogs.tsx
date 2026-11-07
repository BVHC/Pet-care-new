// Ra y lệnh (inpatient-v1 #5), đánh dấu đã thực hiện (#7), hủy (#8). Lỗi BR-LT-15 / 409 của BE hiện ngay trong hộp thoại.
import { useState } from 'react';
import { Field } from '@/components/admin/form-ui';
import { ClinicalDialog, inputClass, textareaClass } from '@/components/clinical/ClinicalDialog';
import { nowVnLocal, vnLocalToIso } from '@/components/clinical/vn-datetime';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useMedicalRecord } from '@/shared/hooks/useExam';
import { useCancelAdmissionOrder, useCompleteAdmissionOrder, useCreateAdmissionOrder } from '@/shared/hooks/useInpatient';
import {
  ADMISSION_ORDER_TYPE_LABELS,
  ADMISSION_ORDER_TYPES,
  INPATIENT_LIMITS,
  type Admission,
  type AdmissionOrder,
  type AdmissionOrderType,
} from '@/shared/types/inpatient';

interface DoneProps {
  onClose: () => void;
  onDone: (message: string) => void;
}

export function CreateOrderDialog({ admission, onClose, onDone }: DoneProps & { admission: Admission }) {
  const [orderType, setOrderType] = useState<AdmissionOrderType>('FEEDING');
  const [content, setContent] = useState('');
  const [scheduledAt, setScheduledAt] = useState(nowVnLocal());
  const [itemId, setItemId] = useState('');
  const [error, setError] = useState('');
  const create = useCreateAdmissionOrder();
  const medication = orderType === 'MEDICATION';
  // Y lệnh thuốc phải gắn dòng đơn của lượt (BR-LT-15); lượt điều trị lập từ lưu trú chưa có đơn nên danh sách này có thể rỗng.
  const record = useMedicalRecord(admission.visitId, medication);
  const items = record.data?.prescriptions ?? [];

  const submit = async () => {
    if (!content.trim()) return setError('Nhập nội dung y lệnh.');
    if (!scheduledAt) return setError('Chọn giờ thực hiện.');
    if (medication && !itemId) return setError('Chọn dòng đơn thuốc của lượt khám.');
    try {
      await create.mutateAsync({
        admissionId: admission.admissionId,
        orderType,
        content: content.trim(),
        scheduledAt: vnLocalToIso(scheduledAt),
        prescriptionItemId: medication ? Number(itemId) : null,
      });
      onDone(`Đã ra y lệnh cho ${admission.petName}.`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <ClinicalDialog
      title="Ra y lệnh"
      description={`${admission.petName} · ${admission.code}`}
      onClose={onClose}
      onSubmit={() => void submit()}
      submitText="Ra y lệnh"
      pending={create.isPending}
      error={error}
    >
      <Field label="Loại y lệnh">
        {(id) => (
          <select
            id={id}
            className={inputClass}
            value={orderType}
            onChange={(e) => {
              setOrderType(e.target.value as AdmissionOrderType);
              setItemId('');
              setError('');
            }}
          >
            {ADMISSION_ORDER_TYPES.map((t) => (
              <option key={t} value={t}>
                {ADMISSION_ORDER_TYPE_LABELS[t]}
              </option>
            ))}
          </select>
        )}
      </Field>
      {medication && (
        <Field label="Dòng đơn thuốc" hint={record.isSuccess && items.length === 0 ? 'Lượt này chưa có đơn thuốc: kê đơn trong bệnh án trước.' : undefined}>
          {(id) => (
            <select id={id} className={inputClass} value={itemId} onChange={(e) => setItemId(e.target.value)}>
              <option value="">Chọn dòng đơn</option>
              {items.map((i) => (
                <option key={i.itemId} value={i.itemId}>
                  {i.productName} × {i.quantity}
                </option>
              ))}
            </select>
          )}
        </Field>
      )}
      <Field label="Nội dung" hint={`${content.length}/${INPATIENT_LIMITS.content}`}>
        {(id) => <textarea id={id} rows={3} maxLength={INPATIENT_LIMITS.content} className={textareaClass} value={content} onChange={(e) => setContent(e.target.value)} />}
      </Field>
      <Field label="Giờ thực hiện">
        {(id) => <input id={id} type="datetime-local" className={inputClass} value={scheduledAt} onChange={(e) => setScheduledAt(e.target.value)} />}
      </Field>
    </ClinicalDialog>
  );
}

const whichOrder = (o: AdmissionOrder) => `${ADMISSION_ORDER_TYPE_LABELS[o.orderType]}: ${o.content}`;

export function CompleteOrderDialog({ order, onClose, onDone }: DoneProps & { order: AdmissionOrder }) {
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const complete = useCompleteAdmissionOrder();
  const submit = async () => {
    if (!note.trim()) return setError('Nhập ghi chú đã thực hiện.');
    try {
      await complete.mutateAsync({ admissionOrderId: order.admissionOrderId, note: note.trim() });
      onDone('Đã đánh dấu y lệnh đã thực hiện.');
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };
  return (
    <ClinicalDialog title="Đã thực hiện y lệnh" description={whichOrder(order)} onClose={onClose} onSubmit={() => void submit()} submitText="Lưu" pending={complete.isPending} error={error}>
      <Field label="Ghi chú" hint={`${note.length}/${INPATIENT_LIMITS.doneNote}`}>
        {(id) => <textarea id={id} rows={3} maxLength={INPATIENT_LIMITS.doneNote} className={textareaClass} value={note} onChange={(e) => setNote(e.target.value)} />}
      </Field>
    </ClinicalDialog>
  );
}

export function CancelOrderDialog({ order, onClose, onDone }: DoneProps & { order: AdmissionOrder }) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const cancel = useCancelAdmissionOrder();
  const submit = async () => {
    if (!reason.trim()) return setError('Nhập lý do hủy.');
    try {
      await cancel.mutateAsync({ admissionOrderId: order.admissionOrderId, reason: reason.trim() });
      onDone('Đã hủy y lệnh.');
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };
  return (
    <ClinicalDialog
      title="Hủy y lệnh"
      description={whichOrder(order)}
      onClose={onClose}
      onSubmit={() => void submit()}
      submitText="Hủy y lệnh"
      cancelText="Quay lại"
      destructive
      pending={cancel.isPending}
      error={error}
    >
      <Field label="Lý do hủy" hint={`${reason.length}/${INPATIENT_LIMITS.reason}`}>
        {(id) => <textarea id={id} rows={3} maxLength={INPATIENT_LIMITS.reason} className={textareaClass} value={reason} onChange={(e) => setReason(e.target.value)} />}
      </Field>
    </ClinicalDialog>
  );
}
