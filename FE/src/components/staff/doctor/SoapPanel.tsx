import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import type { UseQueryResult } from '@tanstack/react-query';
import { FileText, Lock, Plus, X } from 'lucide-react';
import { toast } from 'sonner';
import { cn, formatCurrency } from '../../../lib/utils';
import {
  useCatalog,
  useMoveVisit,
  useSaveRecord,
  useVisitLines,
} from '../../../shared/hooks/useClinic';
import { useHotkeys } from '../../../shared/hooks/useHotkeys';
import { emptyRecord, useConsultation, useConsultationStore, type RecordPatch } from '../../../shared/stores/consultation.store';
import type { Catalog, ServiceKind, VisitDetail } from '../../../shared/types/clinic';
import { formatClock } from '../../../shared/utils/clinic-format';
import { CLINIC_CFG, completeViolation, lineTotal, type RuleViolation } from '../../../shared/utils/clinic-rules';
import { foldVi } from '../../../shared/utils/text';
import { Panel, PanelTitle, QueryError, RowsSkeleton, StatePanel, WsButton } from '../ui';

interface Props {
  className?: string;
  selectedId: string | null;
  detail: UseQueryResult<VisitDetail, unknown>;
  userId: string;
  onCompleted: (visitId: string) => void;
  hotkeysEnabled: boolean;
}

export function SoapPanel({ className, selectedId, detail, userId, onCompleted, hotkeysEnabled }: Props) {
  if (!selectedId) {
    return (
      <Panel label="Bệnh án" className={className}>
        <StatePanel icon={FileText} title="Chưa mở bệnh án" body="Gọi một lượt vào khám để bắt đầu ghi bệnh án." />
      </Panel>
    );
  }
  if (!detail.data) {
    return (
      <Panel label="Bệnh án" className={className}>
        {detail.isError ? (
          <QueryError error={detail.error} onRetry={() => detail.refetch()} what="bệnh án" />
        ) : (
          <RowsSkeleton rows={6} />
        )}
      </Panel>
    );
  }
  return (
    <SoapEditor
      key={detail.data.id}
      className={className}
      detail={detail.data}
      userId={userId}
      onCompleted={onCompleted}
      hotkeysEnabled={hotkeysEnabled}
    />
  );
}

const input =
  'w-full rounded-lg border border-(--ws-line-strong) bg-(--ws-surface) px-3 py-2 text-sm text-(--ws-ink) placeholder:text-(--ws-ink-3) read-only:border-transparent read-only:bg-(--ws-raised) focus:border-(--ws-accent)';

function SoapEditor({
  className,
  detail,
  userId,
  onCompleted,
  hotkeysEnabled,
}: {
  className?: string;
  detail: VisitDetail;
  userId: string;
  onCompleted: (visitId: string) => void;
  hotkeysEnabled: boolean;
}) {
  const catalog = useCatalog();
  const draft = useConsultation((s) => s.drafts[detail.id]);
  const ensureDraft = useConsultation((s) => s.ensureDraft);
  const update = useConsultation((s) => s.update);
  const markSaved = useConsultation((s) => s.markSaved);
  const { mutateAsync: saveRecord, isPending: saving } = useSaveRecord(detail.id, userId);
  const move = useMoveVisit(userId);
  const lines = useVisitLines(detail.id, userId);
  const [attempted, setAttempted] = useState(false);
  const assessmentRef = useRef<HTMLTextAreaElement>(null);

  const editable = detail.status === 'IN_PROGRESS' && detail.assignee?.id === userId;

  useEffect(() => {
    ensureDraft(detail.id, detail.record);
  }, [detail.id, detail.record, ensureDraft]);

  const record = editable ? (draft?.record ?? detail.record ?? emptyRecord()) : (detail.record ?? emptyRecord());
  const set = (patch: RecordPatch) => update(detail.id, patch);

  /** Push the latest draft; resolves false when the server refused (the toast says why). */
  const saveDraft = useCallback(async () => {
    const current = useConsultationStore.getState().drafts[detail.id];
    if (!editable || !current?.dirty) return true;
    try {
      const saved = await saveRecord({ record: current.record, rev: current.rev });
      markSaved(detail.id, current.rev, saved.record?.updatedAt ?? new Date().toISOString());
      return true;
    } catch {
      return false;
    }
  }, [detail.id, editable, saveRecord, markSaved]);

  // Autosave 1.5 s after the last keystroke.
  useEffect(() => {
    if (!editable || !draft?.dirty) return;
    const id = window.setTimeout(() => void saveDraft(), 1500);
    return () => window.clearTimeout(id);
  }, [draft?.rev, draft?.dirty, editable, saveDraft]);

  const kinds = useMemo(() => {
    const services = catalog.data?.services ?? [];
    const added = detail.lines
      .filter((l) => l.kind === 'SERVICE')
      .map((l) => services.find((s) => s.id === l.refId)?.kind)
      .filter((k): k is ServiceKind => !!k);
    return [...new Set([...detail.services.map((s) => s.kind), ...added])];
  }, [catalog.data, detail.lines, detail.services]);

  const violation: RuleViolation | null = completeViolation({
    status: detail.status,
    kinds,
    assessment: record.assessment,
    vaccineLines: detail.lines.filter((l) => l.vaccine).length,
  });

  const complete = async () => {
    if (!editable || move.isPending) return;
    if (violation) {
      setAttempted(true);
      if (kinds.includes('EXAM')) assessmentRef.current?.focus();
      return;
    }
    if (!(await saveDraft())) return;
    move.mutate(
      { visitId: detail.id, to: 'COMPLETED' },
      {
        onSuccess: () => {
          toast.success(`Đã chuyển ${detail.pet.name} sang quầy thu ngân`);
          onCompleted(detail.id);
        },
      },
    );
  };

  useHotkeys({ 'mod+s': () => void saveDraft(), 'mod+enter': () => void complete() }, editable && hotkeysEnabled);

  const saveState = !editable
    ? null
    : saving
      ? 'Đang lưu…'
      : draft?.dirty
        ? 'Có thay đổi chưa lưu'
        : draft?.savedAt
          ? `Đã lưu lúc ${formatClock(draft.savedAt)}`
          : 'Chưa có thay đổi';

  const needsDiagnosis = kinds.includes('EXAM');
  const serviceLines = detail.lines.filter((l) => l.kind === 'SERVICE').length;
  const clinicalServices = (catalog.data?.services ?? []).filter((s) => s.kind !== 'GROOMING');
  const dayMs = 86_400_000;
  const followUpMin = new Date(Date.now() + dayMs).toLocaleDateString('sv-SE');
  const followUpMax = new Date(Date.now() + CLINIC_CFG.followUpMaxDays * dayMs).toLocaleDateString('sv-SE');
  const id = (part: string) => `soap-${part}-${detail.id}`;

  return (
    <Panel label="Bệnh án" className={className}>
      <PanelTitle aside={saveState && <span role="status" className="text-xs text-(--ws-ink-3)">{saveState}</span>}>
        Bệnh án {detail.pet.name}
      </PanelTitle>

      <div className="min-h-0 flex-1 overflow-y-auto">
        <LockNotice detail={detail} userId={userId} />

        <div className="flex flex-col gap-5 p-4">
          <SoapField letter="S" label="Triệu chứng chủ nuôi kể" htmlFor={id('s')}>
            <textarea
              id={id('s')}
              rows={3}
              readOnly={!editable}
              value={record.subjective}
              onChange={(e) => set({ subjective: e.target.value })}
              placeholder="Bỏ ăn từ hôm qua, nôn 2 lần…"
              className={input}
            />
          </SoapField>

          <fieldset className="flex flex-col gap-2">
            <legend className="mb-2 flex items-center gap-2 text-sm font-semibold">
              <span className="ws-soap-letter" aria-hidden>
                O
              </span>
              Khám lâm sàng
            </legend>
            <div className="grid grid-cols-3 gap-2">
              <Vital label="Nhiệt độ" unit="°C" id={id('t')} step="0.1" readOnly={!editable} value={record.objective.temperatureC} onChange={(n) => set({ objective: { temperatureC: n } })} warn={(n) => n < 37.5 || n > 39.5} />
              <Vital label="Cân nặng" unit="kg" id={id('w')} step="0.1" readOnly={!editable} value={record.objective.weightKg} onChange={(n) => set({ objective: { weightKg: n } })} />
              <Vital label="Nhịp tim" unit="lần/phút" id={id('hr')} step="1" readOnly={!editable} value={record.objective.heartRate} onChange={(n) => set({ objective: { heartRate: n } })} />
            </div>
            <label htmlFor={id('o')} className="sr-only">
              Phát hiện khi khám
            </label>
            <textarea
              id={id('o')}
              rows={2}
              readOnly={!editable}
              value={record.objective.findings}
              onChange={(e) => set({ objective: { findings: e.target.value } })}
              placeholder="Phát hiện khi khám: da, niêm mạc, hạch, tim phổi…"
              className={input}
            />
          </fieldset>

          <SoapField
            letter="A"
            label="Chẩn đoán"
            htmlFor={id('a')}
            badge={needsDiagnosis && editable ? 'cần có để hoàn tất' : undefined}
          >
            <textarea
              ref={assessmentRef}
              id={id('a')}
              rows={2}
              readOnly={!editable}
              value={record.assessment}
              onChange={(e) => set({ assessment: e.target.value })}
              aria-invalid={attempted && !!violation && needsDiagnosis}
              className={cn(input, 'aria-invalid:border-(--ws-urgent-ink)')}
            />
          </SoapField>

          <SoapField letter="P" label="Hướng điều trị" htmlFor={id('p')}>
            <textarea
              id={id('p')}
              rows={3}
              readOnly={!editable}
              value={record.plan}
              onChange={(e) => set({ plan: e.target.value })}
              placeholder="Liều dùng, chế độ ăn, thuốc mua ngoài…"
              className={input}
            />
            <div className="mt-2 flex flex-wrap items-center gap-2 text-sm">
              <label htmlFor={id('f')} className="text-(--ws-ink-2)">
                Hẹn tái khám
              </label>
              <input
                id={id('f')}
                type="date"
                readOnly={!editable}
                value={record.followUpDate ?? ''}
                min={followUpMin}
                max={followUpMax}
                onChange={(e) => set({ followUpDate: e.target.value || undefined })}
                className={cn(input, 'ws-num h-9 w-auto py-1')}
              />
            </div>
          </SoapField>

          <section aria-labelledby={id('lines')} className="rounded-lg border border-(--ws-line)">
            <h3 id={id('lines')} className="border-b border-(--ws-line) px-3 py-2 text-sm font-semibold">
              Chỉ định trong lượt
            </h3>
            <ul className="divide-y divide-(--ws-line)">
              {detail.lines.map((l) => (
                <li key={l.id} className="flex items-center gap-2 px-3 py-2 text-sm">
                  <span className="min-w-0 flex-1">
                    <span className="block truncate">{l.name}</span>
                    <span className="text-xs text-(--ws-ink-3)">
                      {l.autoGenerated ? 'Lúc tiếp nhận' : l.vaccine ? 'Mũi tiêm' : l.kind === 'SERVICE' ? 'Dịch vụ thêm' : 'Thuốc'}
                    </span>
                  </span>
                  <span className="ws-num shrink-0 text-(--ws-ink-2)">
                    {l.qty} × {formatCurrency(l.unitPrice)}
                  </span>
                  {editable && l.autoGenerated && (
                    <>
                      <label className="sr-only" htmlFor={`swap-${l.id}`}>
                        Đổi dịch vụ {l.name}
                      </label>
                      <select
                        id={`swap-${l.id}`}
                        value=""
                        disabled={lines.replace.isPending}
                        onChange={(e) => e.target.value && lines.replace.mutate(e.target.value)}
                        className="h-8 max-w-[7.5rem] rounded-md border border-(--ws-line-strong) bg-(--ws-surface) px-1.5 text-xs text-(--ws-ink)"
                      >
                        <option value="">Đổi dịch vụ…</option>
                        {clinicalServices
                          .filter((s) => s.id !== l.refId)
                          .map((s) => (
                            <option key={s.id} value={s.id}>
                              {s.name}
                            </option>
                          ))}
                      </select>
                    </>
                  )}
                  {editable && (l.autoGenerated ? serviceLines > 1 : l.addedBy === userId) && (
                    <button
                      type="button"
                      onClick={() => lines.remove.mutate(l.id)}
                      aria-label={`Xóa ${l.name}`}
                      className="inline-flex h-8 w-8 items-center justify-center rounded-md text-(--ws-ink-3) hover:bg-(--ws-urgent-bg) hover:text-(--ws-urgent-ink)"
                    >
                      <X className="h-4 w-4" />
                    </button>
                  )}
                </li>
              ))}
            </ul>
            {editable && catalog.data && (
              <LinePicker catalog={catalog.data} busy={lines.add.isPending} onAdd={(input) => lines.add.mutate(input)} />
            )}
            <p className="ws-num flex justify-between border-t border-(--ws-line) px-3 py-2 text-sm font-semibold">
              <span>Tạm tính</span>
              {formatCurrency(lineTotal(detail.lines))}
            </p>
          </section>

          <div className="flex flex-col gap-1.5">
            <label htmlFor={id('note')} className="text-sm font-semibold">
              Ghi chú nội bộ
            </label>
            <textarea
              id={id('note')}
              rows={2}
              readOnly={!editable}
              value={record.internalNote}
              onChange={(e) => set({ internalNote: e.target.value })}
              className={input}
            />
            <p className="text-xs text-(--ws-ink-3)">Khách không xem được ghi chú này.</p>
          </div>
        </div>
      </div>

      {editable && (
        <div className="flex flex-col gap-2 border-t border-(--ws-line) bg-(--ws-raised) p-4">
          {attempted && violation && (
            <p role="alert" className="text-sm font-medium text-(--ws-urgent-ink)">
              {violation.message}
            </p>
          )}
          <div className="flex flex-wrap gap-2">
            <WsButton onClick={() => void saveDraft()} disabled={saving || !draft?.dirty}>
              Lưu <span className="text-xs font-medium opacity-70">Ctrl S</span>
            </WsButton>
            <WsButton variant="primary" className="flex-1" disabled={move.isPending} onClick={() => void complete()}>
              {move.isPending ? 'Đang chuyển…' : 'Hoàn tất, chuyển thu ngân'}
              <span className="text-xs font-medium opacity-75">Ctrl Enter</span>
            </WsButton>
          </div>
        </div>
      )}
    </Panel>
  );
}

function LockNotice({ detail, userId }: { detail: VisitDetail; userId: string }) {
  let text: string | null = null;
  if (detail.status === 'COMPLETED') {
    text = `Đã hoàn tất lúc ${formatClock(detail.completedAt ?? detail.checkedInAt)}. Bệnh án đã khóa; ${
      detail.orderStatus === 'PAID' ? 'khách đã thanh toán.' : 'đơn đang chờ thu ở quầy.'
    }`;
  } else if (detail.assignee && detail.assignee.id !== userId) {
    text = `Lượt của ${detail.assignee.name}. Bạn chỉ xem được.`;
  } else if (detail.status === 'WAITING') {
    text = detail.assignee ? 'Gọi lượt này vào khám để bắt đầu ghi bệnh án.' : 'Lượt chưa gán bác sĩ. Lễ tân gán cho bạn thì mới ghi được.';
  }
  if (!text) return null;
  return (
    <p className="mx-4 mt-4 flex items-start gap-2 rounded-lg bg-(--ws-raised) px-3 py-2 text-sm text-(--ws-ink-2)">
      <Lock className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
      {text}
    </p>
  );
}

function SoapField({
  letter,
  label,
  htmlFor,
  badge,
  children,
}: {
  letter: string;
  label: string;
  htmlFor: string;
  badge?: string;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center gap-2">
        <span className="ws-soap-letter" aria-hidden>
          {letter}
        </span>
        <label htmlFor={htmlFor} className="text-sm font-semibold">
          {label}
        </label>
        {badge && (
          <span className="ws-chip" data-tone="wait">
            {badge}
          </span>
        )}
      </div>
      {children}
    </div>
  );
}

function Vital({
  label,
  unit,
  id,
  step,
  value,
  onChange,
  readOnly,
  warn,
}: {
  label: string;
  unit: string;
  id: string;
  step: string;
  value?: number;
  onChange: (value: number | undefined) => void;
  readOnly: boolean;
  warn?: (value: number) => boolean;
}) {
  const out = value !== undefined && warn?.(value);
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-xs text-(--ws-ink-2)">
        {label} <span className="text-(--ws-ink-3)">({unit})</span>
      </label>
      <input
        id={id}
        type="number"
        inputMode="decimal"
        step={step}
        readOnly={readOnly}
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value === '' ? undefined : Number(e.target.value))}
        aria-describedby={out ? `${id}-warn` : undefined}
        className={cn(input, 'ws-num h-10', out && 'border-(--ws-wait-ink)')}
      />
      {out && (
        <span id={`${id}-warn`} className="text-xs font-semibold text-(--ws-wait-ink)">
          Ngoài khoảng thường gặp
        </span>
      )}
    </div>
  );
}

/** Pick an extra exam service, a medicine or a vaccine for this visit's order. */
function LinePicker({
  catalog,
  busy,
  onAdd,
}: {
  catalog: Catalog;
  busy: boolean;
  onAdd: (input: { kind: 'SERVICE' | 'PRODUCT'; refId: string; qty: number }) => void;
}) {
  const [query, setQuery] = useState('');
  const [qty, setQty] = useState(1);
  const [open, setOpen] = useState(false);

  const options = useMemo(() => {
    const q = foldVi(query.trim());
    const services = catalog.services
      .filter((s) => s.kind !== 'GROOMING')
      .map((s) => ({ kind: 'SERVICE' as const, id: s.id, name: s.name, hint: formatCurrency(s.price) }));
    const medicine = catalog.products
      .filter((p) => p.category === 'MEDICINE')
      .map((p) => ({
        kind: 'PRODUCT' as const,
        id: p.id,
        name: p.name,
        hint: `${p.vaccineType ? 'Vaccine, ' : ''}${formatCurrency(p.price)}/${p.unit}, còn ${p.stock}`,
      }));
    return [...medicine, ...services].filter((o) => !q || foldVi(o.name).includes(q)).slice(0, 7);
  }, [catalog, query]);

  const pick = (option: (typeof options)[number]) => {
    onAdd({ kind: option.kind, refId: option.id, qty: option.kind === 'SERVICE' ? 1 : Math.max(1, qty) });
    setQuery('');
    setQty(1);
    setOpen(false);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' && options[0] && query.trim()) {
      e.preventDefault();
      pick(options[0]);
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  };

  return (
    <div className="relative border-t border-(--ws-line) p-3">
      <div className="flex gap-2">
        <label className="sr-only" htmlFor="line-picker">
          Thêm thuốc, vaccine hoặc dịch vụ
        </label>
        <input
          id="line-picker"
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setOpen(true);
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => window.setTimeout(() => setOpen(false), 150)}
          onKeyDown={onKeyDown}
          placeholder="Thêm thuốc, vaccine, xét nghiệm…"
          autoComplete="off"
          className={cn(input, 'h-9 py-1')}
        />
        <label className="sr-only" htmlFor="line-qty">
          Số lượng
        </label>
        <input
          id="line-qty"
          type="number"
          min={1}
          value={qty}
          onChange={(e) => setQty(Math.max(1, Number(e.target.value) || 1))}
          className={cn(input, 'ws-num h-9 w-16 py-1 text-right')}
        />
      </div>
      {open && options.length > 0 && (
        <ul className="absolute inset-x-3 top-full z-20 -mt-1 max-h-64 overflow-y-auto rounded-lg border border-(--ws-line-strong) bg-(--ws-surface) py-1 shadow-(--ws-shadow-lift)">
          {options.map((o) => (
            <li key={o.id}>
              <button
                type="button"
                disabled={busy}
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => pick(o)}
                className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-(--ws-raised)"
              >
                <Plus className="h-3.5 w-3.5 shrink-0 text-(--ws-ink-3)" aria-hidden />
                <span className="min-w-0 flex-1 truncate">{o.name}</span>
                <span className="ws-num shrink-0 text-xs text-(--ws-ink-3)">{o.hint}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
