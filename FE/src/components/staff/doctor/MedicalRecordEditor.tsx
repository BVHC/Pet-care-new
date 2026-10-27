// Bệnh án của lượt Khám/Tiêm (UC48, UC53; visit-v1 #10, #12–17; BR-KB-01, 02, 03, 06, BR-KH-04): 5 trường của hợp đồng, cân nặng, tiêm chủng, đơn thuốc, hoàn tất lượt, bản bổ sung khi đã khóa.
// Chỉ VET được gán lượt đang IN_PROGRESS mới sửa được; lượt COMPLETED là bệnh án đã khóa. KHÔNG tự lưu: mỗi lần lưu có thay đổi BE ghi một dòng audit (ADR-0066), nên lưu khi bấm "Lưu"
// hoặc Ctrl+S, và hoàn tất lưu trước. FE không tự kiểm BR-KB-02: hỏi BE và hiện nguyên câu BE trả để luật không lệch.
import { useRef, useState, type ReactNode, type Ref } from 'react';
import { Lock } from 'lucide-react';
import { toast } from 'sonner';
import { formatCurrency } from '../../../lib/utils';
import { usePetDetail } from '../../../domains/pets/hooks/useCustomerPet';
import { toApiError } from '../../../shared/api/api-error';
import { useCompleteVisit, useMedicalRecord, useRecordWeight, useSaveMedicalRecord } from '../../../shared/hooks/useExam';
import { useHotkeys } from '../../../shared/hooks/useHotkeys';
import { useOrder } from '../../../shared/hooks/useSales';
import { visitErrorMessage } from '../../../shared/hooks/useVisits';
import { FOLLOW_UP_MAX_DAYS, type MedicalRecord, type UpdateMedicalRecordRequest } from '../../../shared/types/medical-record';
import type { Visit, VisitPermissions } from '../../../shared/types/visit';
import { addDays } from '../../../shared/utils/branch-schedule';
import { formatClock } from '../../../shared/utils/clinic-format';
import { vnDate } from '../../../shared/utils/vn-time';
import { QueryError, RowsSkeleton, WsButton } from '../ui';
import { AddendaSection } from './AddendaSection';
import { fieldClass } from './field-class';
import { PrescriptionSection } from './PrescriptionSection';
import { VaccinationSection } from './VaccinationSection';

interface Props {
  visit: Visit;
  userId: number;
  perms: VisitPermissions;
  /** Thông tin bệnh nhân (chủ nuôi, dịch vụ, người phụ trách…) đặt đầu vùng cuộn. */
  summary: ReactNode;
  onCompleted: (visit: Visit) => void;
}

type Draft = { examination: string; diagnosis: string; treatmentPlan: string; internalNote: string; followUpDate: string };

const draftOf = (r: MedicalRecord): Draft => ({
  examination: r.examination ?? '',
  diagnosis: r.diagnosis ?? '',
  treatmentPlan: r.treatmentPlan ?? '',
  internalNote: r.internalNote ?? '',
  followUpDate: r.followUpDate ?? '',
});

/** Cùng chuẩn hóa như BE: bỏ khoảng trắng hai đầu, chuỗi rỗng thành null (UpdateMedicalRecordRequest). */
const bodyOf = (d: Draft): UpdateMedicalRecordRequest => ({
  examination: d.examination.trim() || null,
  diagnosis: d.diagnosis.trim() || null,
  treatmentPlan: d.treatmentPlan.trim() || null,
  internalNote: d.internalNote.trim() || null,
  followUpDate: d.followUpDate || null,
});

export function MedicalRecordEditor(props: Props) {
  const query = useMedicalRecord(props.visit.visitId, props.perms.viewRecord);
  if (!query.data) {
    return (
      <div className="min-h-0 flex-1 overflow-y-auto">
        {props.summary}
        {query.isError ? <QueryError error={query.error} onRetry={() => query.refetch()} what="bệnh án" /> : <RowsSkeleton rows={6} />}
      </div>
    );
  }
  return <Editor key={props.visit.visitId} {...props} record={query.data} />;
}

function Editor({ visit, userId, perms, summary, onCompleted, record }: Props & { record: MedicalRecord }) {
  const save = useSaveMedicalRecord();
  const complete = useCompleteVisit();
  const [draft, setDraft] = useState(() => draftOf(record));
  const [savedAt, setSavedAt] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const diagnosisRef = useRef<HTMLTextAreaElement>(null);

  const editable = visit.status === 'IN_PROGRESS' && visit.assigneeId === userId && perms.writeRecord;
  const dirty = JSON.stringify(bodyOf(draft)) !== JSON.stringify(bodyOf(draftOf(record)));
  const busy = save.isPending || complete.isPending;
  const set = (patch: Partial<Draft>) => setDraft((d) => ({ ...d, ...patch }));

  /** Ghi bệnh án đang soạn; false khi BE từ chối (câu báo đã hiện trong form). */
  const doSave = async (): Promise<boolean> => {
    if (!editable || !dirty) return true;
    setError(null);
    try {
      await save.mutateAsync({ visitId: visit.visitId, ...bodyOf(draft) });
      setSavedAt(new Date().toISOString());
      return true;
    } catch (e) {
      setError(visitErrorMessage(e, 'writeRecord'));
      return false;
    }
  };

  const doComplete = async () => {
    if (!editable || busy) return;
    if (!(await doSave())) return;
    setError(null);
    try {
      const done = await complete.mutateAsync(visit.visitId);
      toast.success(`Đã chuyển ${visit.petName} sang quầy thu ngân`);
      onCompleted(done);
    } catch (e) {
      setError(visitErrorMessage(e, 'complete'));
      if (toApiError(e).ruleId === 'BR-KB-02' && toApiError(e).message.includes('chẩn đoán')) diagnosisRef.current?.focus();
    }
  };

  useHotkeys({ 'mod+s': () => void doSave(), 'mod+enter': () => void doComplete() }, editable);

  const examDate = vnDate(Date.parse(visit.checkedInAt));
  const state = save.isPending ? 'Đang lưu…' : dirty ? 'Có thay đổi chưa lưu' : savedAt ? `Đã lưu lúc ${formatClock(savedAt)}` : 'Chưa có thay đổi';
  const id = (part: string) => `exam-${part}-${visit.visitId}`;
  const text = (key: keyof Draft, label: string, rows: number, ref?: Ref<HTMLTextAreaElement>) => (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id(key)} className="text-sm font-semibold">
        {label}
      </label>
      <textarea ref={ref} id={id(key)} rows={rows} readOnly={!editable} value={draft[key]} onChange={(e) => set({ [key]: e.target.value })} className={fieldClass} />
    </div>
  );

  return (
    <>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {summary}
        <LockNotice visit={visit} editable={editable} />
        <div className="flex flex-col gap-5 p-4">
          <div className="flex items-center justify-between gap-2">
            <h3 className="text-[15px] font-semibold">Bệnh án {visit.petName}</h3>
            {editable && (
              <span role="status" aria-label="Trạng thái bệnh án" className="text-xs text-(--ws-ink-3)">
                {state}
              </span>
            )}
          </div>
          {text('examination', 'Khám lâm sàng', 3)}
          {text('diagnosis', 'Chẩn đoán', 2, diagnosisRef)}
          {text('treatmentPlan', 'Hướng điều trị', 3)}
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <label htmlFor={id('followUpDate')} className="text-(--ws-ink-2)">
              Hẹn tái khám
            </label>
            <input
              id={id('followUpDate')}
              type="date"
              readOnly={!editable}
              value={draft.followUpDate}
              min={addDays(examDate, 1)}
              max={addDays(examDate, FOLLOW_UP_MAX_DAYS)}
              onChange={(e) => set({ followUpDate: e.target.value })}
              className={`${fieldClass} ws-num h-9 w-auto py-1`}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            {text('internalNote', 'Ghi chú nội bộ', 2)}
            <p className="text-xs text-(--ws-ink-3)">Khách không xem được ghi chú này.</p>
          </div>

          {editable && perms.writeRecord && <WeightBox visit={visit} />}
          {visit.serviceGroup === 'MEDICAL' && <VaccinationSection visit={visit} doses={record.vaccinations} canVaccinate={editable && perms.vaccinate} />}
          <OrderLines orderId={visit.orderId} />
          <PrescriptionSection visitId={visit.visitId} items={record.prescriptions} canPrescribe={editable && perms.prescribe} />
          {record.locked && <AddendaSection visitId={visit.visitId} addenda={record.addenda} canAdd={perms.addendum} />}
        </div>
      </div>

      {editable && (
        <div className="flex flex-col gap-2 border-t border-(--ws-line) bg-(--ws-raised) p-4">
          {error && (
            <p role="alert" className="text-sm font-medium text-(--ws-urgent-ink)">
              {error}
            </p>
          )}
          <div className="flex flex-wrap gap-2">
            <WsButton onClick={() => void doSave()} disabled={busy || !dirty}>
              Lưu <span className="text-xs font-medium opacity-70">Ctrl S</span>
            </WsButton>
            <WsButton variant="primary" className="flex-1" disabled={busy} onClick={() => void doComplete()}>
              {complete.isPending ? 'Đang chuyển…' : 'Hoàn tất, chuyển thu ngân'}
              <span className="text-xs font-medium opacity-75">Ctrl Enter</span>
            </WsButton>
          </div>
        </div>
      )}
    </>
  );
}

function LockNotice({ visit, editable }: { visit: Visit; editable: boolean }) {
  let text: string | null = null;
  if (visit.status === 'COMPLETED') {
    text = `Đã hoàn tất lúc ${formatClock(visit.completedAt ?? visit.checkedInAt)}. Bệnh án đã khóa, chỉ thêm được bản bổ sung (BR-KB-01).`;
  } else if (!editable) {
    text = `Lượt của ${visit.assigneeName ?? 'nhân viên khác'}. Bạn chỉ xem được.`;
  }
  if (!text) return null;
  return (
    <p className="mx-4 mt-4 flex items-start gap-2 rounded-lg bg-(--ws-raised) px-3 py-2 text-sm text-(--ws-ink-2)">
      <Lock className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
      {text}
    </p>
  );
}

/** Ghi cân nặng khi khám (visit-v1 #14, BR-KH-04): mỗi lần là một bản ghi, cân nặng hiện tại của thú là lần đo gần nhất. */
function WeightBox({ visit }: { visit: Visit }) {
  const weigh = useRecordWeight();
  const pet = usePetDetail(visit.petId);
  const [value, setValue] = useState('');
  const [error, setError] = useState<string | null>(null);
  const current = pet.data?.currentWeightKg;

  const submit = async () => {
    setError(null);
    try {
      const done = await weigh.mutateAsync({ visitId: visit.visitId, weightKg: Number(value.replace(',', '.')) });
      toast.success(`Đã ghi cân nặng ${done.weightKg} kg cho ${visit.petName}`);
      setValue('');
    } catch (e) {
      setError(visitErrorMessage(e, 'writeRecord'));
    }
  };

  return (
    <div className="flex flex-wrap items-end gap-2">
      <div className="flex flex-col gap-1">
        <label htmlFor={`weight-${visit.visitId}`} className="text-sm font-semibold">
          Cân nặng (kg)
        </label>
        <input
          id={`weight-${visit.visitId}`}
          type="number"
          inputMode="decimal"
          step="0.1"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          className={`${fieldClass} ws-num h-9 w-28 py-1`}
        />
      </div>
      <WsButton disabled={weigh.isPending || value.trim() === ''} onClick={() => void submit()}>
        Ghi cân
      </WsButton>
      {current != null && <span className="ws-num pb-2 text-sm text-(--ws-ink-3)">Hiện tại {current} kg</span>}
      {error && (
        <p role="alert" className="w-full text-sm font-medium text-(--ws-urgent-ink)">
          {error}
        </p>
      )}
    </div>
  );
}

/** Các dòng của Order lượt (sales-v1 #3), chỉ đọc: dịch vụ lúc tiếp nhận, mũi tiêm (dòng vaccine) và tổng tạm tính. Sửa dòng thuộc module bán hàng (UC67). */
function OrderLines({ orderId }: { orderId: number }) {
  const order = useOrder(orderId > 0 ? orderId : null);
  if (!order.data) return null;
  const kindOf = (l: (typeof order.data.lines)[number]) => (l.isAutoGenerated ? 'Lúc tiếp nhận' : l.lineType === 'VACCINE' ? 'Mũi tiêm' : l.lineType === 'DRUG' ? 'Thuốc' : l.lineType === 'SERVICE' ? 'Dịch vụ thêm' : 'Hàng');
  return (
    <section aria-labelledby={`lines-${orderId}`} className="rounded-lg border border-(--ws-line)">
      <h3 id={`lines-${orderId}`} className="border-b border-(--ws-line) px-3 py-2 text-sm font-semibold">
        Chỉ định trong lượt
      </h3>
      <ul aria-label="Chỉ định trong lượt" className="divide-y divide-(--ws-line)">
        {order.data.lines.map((l) => (
          <li key={l.lineId} className="flex items-center gap-2 px-3 py-2 text-sm">
            <span className="min-w-0 flex-1">
              <span className="block truncate">{l.description}</span>
              <span className="text-xs text-(--ws-ink-3)">{kindOf(l)}</span>
            </span>
            <span className="ws-num shrink-0 text-(--ws-ink-2)">
              {l.quantity} × {formatCurrency(l.unitPrice)}
            </span>
          </li>
        ))}
      </ul>
      <p className="ws-num flex justify-between border-t border-(--ws-line) px-3 py-2 text-sm font-semibold">
        <span>Tạm tính</span>
        {formatCurrency(order.data.totalAmount)}
      </p>
    </section>
  );
}
