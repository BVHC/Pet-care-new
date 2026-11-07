// Chỉ số theo dõi của một ca nội trú (inpatient-v1 #9–10): bảng mới nhất trước kèm đường nhiệt độ đơn giản. Chỉ thêm, không sửa / xóa; ca đã xuất viện không ghi thêm.
import { useState } from 'react';
import { Plus } from 'lucide-react';
import { EmptyRow, Field, Checkbox } from '@/components/admin/form-ui';
import { Pager } from '@/components/admin/Pager';
import { formatVnDateTime } from '@/components/appointments/format';
import { ClinicalDialog, inputClass, textareaClass } from '@/components/clinical/ClinicalDialog';
import { QueryError } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { StatusBadge } from '@/components/ui/modal-templates';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useCreateObservation, useObservations } from '@/shared/hooks/useInpatient';
import { APPETITE_LABELS, APPETITES, INPATIENT_LIMITS, type Admission, type Appetite, type Observation } from '@/shared/types/inpatient';
import type { InpatientPermissions } from './permissions';

const PAGE_SIZE = 10;
const dash = (v: number | null | undefined) => (v == null ? '–' : String(v));

/** Đường nhiệt độ của các lần đo trong trang đang xem (cũ → mới). Dưới 2 điểm thì không vẽ. */
function TemperatureChart({ rows }: { rows: Observation[] }) {
  const points = rows.filter((o) => o.temperatureC != null).reverse();
  if (points.length < 2) return null;
  const values = points.map((o) => o.temperatureC as number);
  const lo = Math.min(...values) - 0.3;
  const hi = Math.max(...values) + 0.3;
  const x = (i: number) => 10 + (i * 300) / (points.length - 1);
  const y = (v: number) => 70 - ((v - lo) / (hi - lo)) * 60;
  return (
    <svg role="img" aria-label={`Biểu đồ nhiệt độ: ${values.map((v) => `${v}°C`).join(', ')}`} viewBox="0 0 320 80" className="mx-4 my-3 h-20 w-80 text-(--ws-accent)">
      <polyline fill="none" stroke="currentColor" strokeWidth="2" points={points.map((o, i) => `${x(i)},${y(o.temperatureC as number)}`).join(' ')} />
      {points.map((o, i) => (
        <circle key={o.observationId} cx={x(i)} cy={y(o.temperatureC as number)} r="3" fill={o.abnormal ? '#dc2626' : 'currentColor'} />
      ))}
    </svg>
  );
}

function ObservationDialog({ admission, onClose, onDone }: { admission: Admission; onClose: () => void; onDone: (message: string) => void }) {
  const [form, setForm] = useState({ temperatureC: '', heartRate: '', respiratoryRate: '', weightKg: '', note: '' });
  const [appetite, setAppetite] = useState<Appetite | ''>('');
  const [abnormal, setAbnormal] = useState(false);
  const [error, setError] = useState('');
  const create = useCreateObservation();
  const set = (key: keyof typeof form) => (value: string) => setForm((f) => ({ ...f, [key]: value }));
  const num = (v: string) => (v.trim() === '' ? null : Number(v.replace(',', '.')));

  const submit = async () => {
    const values = { temperatureC: num(form.temperatureC), heartRate: num(form.heartRate), respiratoryRate: num(form.respiratoryRate), weightKg: num(form.weightKg) };
    if (Object.values(values).some((v) => v !== null && Number.isNaN(v))) return setError('Chỉ số phải là số.');
    if (Object.values(values).every((v) => v === null) && !appetite && !form.note.trim()) return setError('Nhập ít nhất một chỉ số, tình trạng ăn hoặc ghi chú.');
    try {
      await create.mutateAsync({ admissionId: admission.admissionId, ...values, appetite: appetite || null, note: form.note.trim() || null, abnormal });
      onDone(abnormal ? `Đã ghi chỉ số bất thường của ${admission.petName}, VET phụ trách và khách được báo.` : `Đã ghi chỉ số theo dõi của ${admission.petName}.`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  const number = (key: 'temperatureC' | 'heartRate' | 'respiratoryRate' | 'weightKg', label: string) => (
    <Field label={label}>{(id) => <input id={id} inputMode="decimal" className={inputClass} value={form[key]} onChange={(e) => set(key)(e.target.value)} />}</Field>
  );

  return (
    <ClinicalDialog title="Ghi chỉ số theo dõi" description={`${admission.petName} · ${admission.code}`} onClose={onClose} onSubmit={() => void submit()} submitText="Lưu chỉ số" pending={create.isPending} error={error}>
      <div className="grid grid-cols-2 gap-3">
        {number('temperatureC', 'Nhiệt độ (°C)')}
        {number('weightKg', 'Cân nặng (kg)')}
        {number('heartRate', 'Nhịp tim (lần/phút)')}
        {number('respiratoryRate', 'Nhịp thở (lần/phút)')}
      </div>
      <Field label="Tình trạng ăn">
        {(id) => (
          <select id={id} className={inputClass} value={appetite} onChange={(e) => setAppetite(e.target.value as Appetite | '')}>
            <option value="">Không ghi</option>
            {APPETITES.map((a) => (
              <option key={a} value={a}>
                {APPETITE_LABELS[a]}
              </option>
            ))}
          </select>
        )}
      </Field>
      <Field label="Ghi chú" hint={`${form.note.length}/${INPATIENT_LIMITS.observationNote}`}>
        {(id) => <textarea id={id} rows={2} maxLength={INPATIENT_LIMITS.observationNote} className={textareaClass} value={form.note} onChange={(e) => set('note')(e.target.value)} />}
      </Field>
      <Checkbox label="Bất thường (báo VET phụ trách và khách)" checked={abnormal} onChange={setAbnormal} />
    </ClinicalDialog>
  );
}

export function ObservationsSection({ admission, perms, onNotice }: { admission: Admission; perms: InpatientPermissions; onNotice: (text: string) => void }) {
  const [page, setPage] = useState(0);
  const [recording, setRecording] = useState(false);
  const list = useObservations(admission.admissionId, { page, size: PAGE_SIZE });
  const rows = list.data?.content ?? [];

  return (
    <Card className="card">
      <CardContent className="p-0">
        <div className="flex items-center justify-between gap-3 border-b border-(--border-color) px-4 py-3">
          <h2 className="text-base font-semibold text-(--text-primary)">Chỉ số theo dõi</h2>
          {admission.status === 'ADMITTED' && perms.observe && (
            <Button size="sm" onClick={() => setRecording(true)}>
              <Plus className="h-4 w-4" aria-hidden /> Ghi chỉ số
            </Button>
          )}
        </div>
        {list.isError && !list.data ? (
          <QueryError error={list.error} onRetry={() => list.refetch()} what="chỉ số theo dõi" />
        ) : (
          <>
            <TemperatureChart rows={rows} />
            <table className="table" aria-label="Chỉ số theo dõi">
              <thead>
                <tr>
                  <th>Lúc ghi</th>
                  <th>Nhiệt độ</th>
                  <th>Tim</th>
                  <th>Thở</th>
                  <th>Cân (kg)</th>
                  <th>Ăn uống</th>
                  <th>Ghi chú</th>
                </tr>
              </thead>
              <tbody>
                {rows.length === 0 ? (
                  <EmptyRow colSpan={7} loading={list.isPending} />
                ) : (
                  rows.map((o) => (
                    <tr key={o.observationId}>
                      <td className="whitespace-nowrap align-top tabular-nums">
                        {formatVnDateTime(o.createdAt)}
                        <span className="block text-xs text-(--text-secondary)">{o.recordedByName}</span>
                      </td>
                      <td className="align-top tabular-nums">{dash(o.temperatureC)}</td>
                      <td className="align-top tabular-nums">{dash(o.heartRate)}</td>
                      <td className="align-top tabular-nums">{dash(o.respiratoryRate)}</td>
                      <td className="align-top tabular-nums">{dash(o.weightKg)}</td>
                      <td className="align-top">{o.appetite ? APPETITE_LABELS[o.appetite] : '–'}</td>
                      <td className="align-top">
                        {o.abnormal && <StatusBadge status="Bất thường" variant="danger" />} {o.note}
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
            {list.data && <Pager data={list.data} unit="lần ghi" onPage={setPage} />}
          </>
        )}
      </CardContent>
      {recording && (
        <ObservationDialog
          admission={admission}
          onClose={() => setRecording(false)}
          onDone={(text) => {
            setRecording(false);
            onNotice(text);
          }}
        />
      )}
    </Card>
  );
}
