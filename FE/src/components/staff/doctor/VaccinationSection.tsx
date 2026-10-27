// Tiêm chủng trong lượt khám (UC49, visit-v1 #18–20; BR-KB-04, BR-KO-05). VET chọn loại vaccine đúng loài, mũi theo phác đồ (mặc định là mũi gợi ý, chọn mũi khác được) và sản phẩm còn tồn
// khả dụng; ghi mũi trừ kho FEFO ngay và thêm dòng Order vaccine. Xóa mũi ghi nhầm chỉ khi lượt còn IN_PROGRESS và chỉ người phụ trách HIỆN TẠI của lượt (kể cả mũi do VET cũ ghi trước khi gán lại,
// ADR-0075); sau khi hoàn tất, sai sót ghi bằng bản bổ sung. BE có các endpoint này từ T28 (26/10, ADR 0072–0075); lô / hạn của mũi BE chưa điền (null, nợ D046) nên chỉ hiện khi có.
import { useState } from 'react';
import { toast } from 'sonner';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useVaccineTypes } from '../../../shared/hooks/useCatalog';
import { useDeleteVaccination, useRecordVaccination, useVaccinationOptions } from '../../../shared/hooks/useExam';
import { visitErrorMessage } from '../../../shared/hooks/useVisits';
import { VACCINATION_WARNING_LABELS, type VaccinationRecord } from '../../../shared/types/medical-record';
import type { Visit } from '../../../shared/types/visit';
import { WsButton } from '../ui';

const viDate = (isoDate: string) => isoDate.split('-').reverse().join('/');
const select = 'h-9 w-full rounded-lg border border-(--ws-line-strong) bg-(--ws-surface) px-2 text-sm text-(--ws-ink) focus:border-(--ws-accent)';
const hint = 'text-sm text-(--ws-ink-3)';

interface Props {
  visit: Visit;
  doses: VaccinationRecord[];
  /** Lượt đang IN_PROGRESS và người mở là VET được gán (visitPermissions.vaccinate): vừa ghi vừa xóa mũi (BE không xét người đã ghi mũi). */
  canVaccinate: boolean;
}

export function VaccinationSection({ visit, doses, canVaccinate }: Props) {
  const remove = useDeleteVaccination();
  const [error, setError] = useState<string | null>(null);
  if (!canVaccinate && doses.length === 0) return null;

  const removeDose = async (dose: VaccinationRecord) => {
    setError(null);
    try {
      await remove.mutateAsync({ visitId: visit.visitId, vaccinationId: dose.vaccinationId });
      toast.success(`Đã xóa mũi ${dose.doseNumber} ${dose.vaccineTypeName}`);
    } catch (e) {
      setError(visitErrorMessage(e, 'deleteVaccination'));
    }
  };

  return (
    <section aria-labelledby={`vaccination-${visit.visitId}`} className="flex flex-col gap-3 rounded-lg border border-(--ws-line) p-3">
      <h3 id={`vaccination-${visit.visitId}`} className="text-sm font-semibold">
        Tiêm chủng
      </h3>
      {doses.length === 0 ? (
        <p className={hint}>Chưa ghi mũi tiêm nào trong lượt này.</p>
      ) : (
        <ul aria-label="Mũi tiêm trong lượt" className="flex flex-col gap-2">
          {doses.map((d) => (
            <li key={d.vaccinationId} className="flex items-start gap-2 rounded-md bg-(--ws-raised) px-3 py-2 text-sm">
              <span className="min-w-0 flex-1">
                <span className="block font-medium">
                  Mũi {d.doseNumber} · {d.vaccineTypeName}
                </span>
                <span className="block text-(--ws-ink-2)">
                  {d.productName}
                  {d.lotNumber ? `, lô ${d.lotNumber}` : ''}
                  {d.expiryDate ? `, hạn ${viDate(d.expiryDate)}` : ''}
                </span>
                <span className="ws-num block text-xs text-(--ws-ink-3)">
                  Tiêm {viDate(d.administeredOn)}, tái chủng {viDate(d.nextDueDate)}
                </span>
              </span>
              {d.superseded && (
                <span className="ws-chip" data-tone="done">
                  Có mũi mới hơn
                </span>
              )}
              {canVaccinate && (
                <WsButton size="sm" variant="danger" aria-label={`Xóa mũi ${d.doseNumber} ${d.vaccineTypeName}`} disabled={remove.isPending} onClick={() => void removeDose(d)}>
                  Xóa
                </WsButton>
              )}
            </li>
          ))}
        </ul>
      )}
      {visit.status === 'COMPLETED' && doses.length > 0 && <p className={hint}>Lượt đã hoàn tất nên không xóa mũi tiêm được. Sai sót ghi bằng bản bổ sung (BR-KB-04).</p>}
      {error && (
        <p role="alert" className="text-sm font-medium text-(--ws-urgent-ink)">
          {error}
        </p>
      )}
      {canVaccinate && <VaccinationForm visit={visit} />}
    </section>
  );
}

function VaccinationForm({ visit }: { visit: Visit }) {
  const types = useVaccineTypes(visit.species);
  const record = useRecordVaccination();
  const [typeId, setTypeId] = useState<number | null>(null);
  const [protocolPick, setProtocolPick] = useState<number | null>(null);
  const [productPick, setProductPick] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const options = useVaccinationOptions(visit.visitId, typeId);

  const data = options.data;
  const protocolId = protocolPick ?? data?.suggestedProtocolId ?? null;
  const productId = productPick ?? data?.products[0]?.productId ?? null;
  const suggestedDose = data?.protocol.find((p) => p.protocolId === data.suggestedProtocolId);
  const choices = (types.data ?? []).filter((t) => t.isActive);

  const pickType = (id: number | null) => {
    setTypeId(id);
    setProtocolPick(null);
    setProductPick(null);
    setError(null);
  };

  const submit = async () => {
    if (typeId === null || protocolId === null || productId === null) return;
    setError(null);
    try {
      const { vaccination, warnings } = await record.mutateAsync({ visitId: visit.visitId, vaccineTypeId: typeId, protocolId, productId });
      toast.success(`Đã ghi mũi ${vaccination.doseNumber} ${vaccination.vaccineTypeName} cho ${visit.petName}`);
      for (const warning of warnings) toast.warning(VACCINATION_WARNING_LABELS[warning] ?? warning);
      setProtocolPick(null);
      setProductPick(null);
    } catch (e) {
      setError(visitErrorMessage(e, 'vaccinate'));
    }
  };

  if (types.isLoading) return <p className={hint}>Đang tải danh mục vaccine…</p>;
  if (choices.length === 0) return <p className={hint}>Loài này chưa có loại vaccine nào trong danh mục.</p>;

  return (
    <div className="flex flex-col gap-2 border-t border-(--ws-line) pt-3">
      <select aria-label="Loại vaccine" className={select} value={typeId ?? ''} onChange={(e) => pickType(e.target.value === '' ? null : Number(e.target.value))}>
        <option value="">Chọn loại vaccine…</option>
        {choices.map((t) => (
          <option key={t.vaccineTypeId} value={t.vaccineTypeId}>
            {t.name}
          </option>
        ))}
      </select>

      {typeId !== null && options.isLoading && <p className={hint}>Đang tải gợi ý mũi tiêm…</p>}
      {typeId !== null && options.isError && <p className="text-sm text-(--ws-urgent-ink)">{plainMessage(toApiError(options.error))}</p>}

      {data && (
        <>
          {data.lastDose && (
            <p className={hint}>
              Mũi gần nhất tại hệ thống: mũi {data.lastDose.doseNumber} ngày {viDate(data.lastDose.administeredOn)}.
              {suggestedDose && suggestedDose.doseNumber <= data.lastDose.doseNumber && ` Đã hết phác đồ, gợi ý nhắc lại mũi ${suggestedDose.doseNumber}.`}
            </p>
          )}
          {data.protocol.length === 0 ? (
            <p className={hint}>Loại vaccine này chưa có phác đồ cho loài của thú.</p>
          ) : (
            <select aria-label="Mũi theo phác đồ" className={select} value={protocolId ?? ''} onChange={(e) => setProtocolPick(e.target.value === '' ? null : Number(e.target.value))}>
              {protocolId === null && <option value="">Chọn mũi…</option>}
              {data.protocol.map((p) => (
                <option key={p.protocolId} value={p.protocolId}>
                  Mũi {p.doseNumber} (cách {p.intervalDays} ngày, từ {p.minAgeWeeks} tuần tuổi){p.protocolId === data.suggestedProtocolId ? ', gợi ý' : ''}
                </option>
              ))}
            </select>
          )}
          {data.underMinAge && protocolId === data.suggestedProtocolId && (
            <p role="note" className="text-sm font-medium text-(--ws-wait-ink)">
              {VACCINATION_WARNING_LABELS.UNDER_MIN_AGE}
            </p>
          )}
          {data.products.length === 0 ? (
            <p className={hint}>Loại vaccine này hiện không còn sản phẩm nào có tồn khả dụng.</p>
          ) : (
            <select aria-label="Sản phẩm vaccine" className={select} value={productId ?? ''} onChange={(e) => setProductPick(Number(e.target.value))}>
              {data.products.map((p) => (
                <option key={p.productId} value={p.productId}>
                  {p.name} (còn {p.availableQuantity} liều)
                </option>
              ))}
            </select>
          )}
        </>
      )}

      {error && (
        <p role="alert" className="text-sm font-medium text-(--ws-urgent-ink)">
          {error}
        </p>
      )}
      <WsButton variant="primary" className="self-start" disabled={record.isPending || protocolId === null || productId === null} onClick={() => void submit()}>
        Ghi mũi tiêm
      </WsButton>
    </div>
  );
}
