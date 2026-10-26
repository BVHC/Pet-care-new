// Đơn thuốc trong lượt khám (UC48, visit-v1 #16–17; BR-KB-03, BR-SP-01, BR-BH-02). VET được gán lượt IN_PROGRESS chọn thuốc kê đơn, nhập số lượng và liều dùng; đủ tồn thì dòng đơn sinh
// một dòng Order thuốc (hiện ở "Chỉ định trong lượt"). Thiếu tồn thì BE trả 400 BR-KB-03 kèm số tồn: FE hiện nguyên câu đó và để VET chọn giảm số lượng cho vừa tồn hoặc mua ngoài (dòng vẫn in trên đơn,
// không vào hóa đơn), rồi gửi lại cùng body kèm `shortageResolution`. Bệnh án đã khóa hoặc lượt của người khác chỉ xem. BE chưa có các endpoint này (T38): màn chạy trên mock theo hợp đồng.
import { useState } from 'react';
import { toast } from 'sonner';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useProducts } from '../../../shared/hooks/useCatalog';
import { useAddPrescription, useDeletePrescription } from '../../../shared/hooks/useExam';
import { visitErrorMessage } from '../../../shared/hooks/useVisits';
import type { PrescriptionItem } from '../../../shared/types/medical-record';
import type { AddPrescriptionRequest, ShortageResolution } from '../../../shared/types/visit';
import { WsButton } from '../ui';
import { fieldClass } from './field-class';

/** `prescription_items.dosage_instructions` VARCHAR(500). */
const MAX_DOSAGE_LENGTH = 500;
const EXTERNAL_REASON_LABELS = { OUT_OF_STOCK_AT_PRESCRIBE: 'hết tồn khi kê', OUT_OF_STOCK_AT_PAYMENT: 'hết tồn khi thu tiền' } as const;
const select = 'h-9 w-full rounded-lg border border-(--ws-line-strong) bg-(--ws-surface) px-2 text-sm text-(--ws-ink) focus:border-(--ws-accent)';
const hint = 'text-sm text-(--ws-ink-3)';

interface Props {
  visitId: number;
  items: PrescriptionItem[];
  /** Lượt đang IN_PROGRESS và người mở là VET được gán (visitPermissions.prescribe). */
  canPrescribe: boolean;
}

export function PrescriptionSection({ visitId, items, canPrescribe }: Props) {
  const remove = useDeletePrescription();
  const [error, setError] = useState<string | null>(null);
  if (!canPrescribe && items.length === 0) return null;

  const removeItem = async (item: PrescriptionItem) => {
    setError(null);
    try {
      await remove.mutateAsync({ visitId, itemId: item.itemId });
      toast.success(`Đã xóa ${item.productName} khỏi đơn`);
    } catch (e) {
      setError(visitErrorMessage(e, 'prescribe'));
    }
  };

  return (
    <section aria-labelledby={`prescription-${visitId}`} className="flex flex-col gap-3 rounded-lg border border-(--ws-line) p-3">
      <h3 id={`prescription-${visitId}`} className="text-sm font-semibold">
        Đơn thuốc
      </h3>
      {items.length === 0 ? (
        <p className={hint}>Chưa kê thuốc nào trong lượt này.</p>
      ) : (
        <ul aria-label="Thuốc đã kê" className="flex flex-col gap-2">
          {items.map((item) => (
            <li key={item.itemId} className="flex items-start gap-2 rounded-md bg-(--ws-raised) px-3 py-2 text-sm">
              <span className="min-w-0 flex-1">
                <span className="block font-medium">{item.productName}</span>
                <span className="ws-num block text-(--ws-ink-2)">Số lượng {item.quantity}</span>
                <span className="block whitespace-pre-wrap text-(--ws-ink-2)">{item.dosageInstructions}</span>
                {item.isExternalPurchase && (
                  <span className="block text-xs text-(--ws-ink-3)">Không tính vào hóa đơn{item.externalReason ? `, ${EXTERNAL_REASON_LABELS[item.externalReason]}` : ''}</span>
                )}
              </span>
              <span className="ws-chip" data-tone={item.isExternalPurchase ? 'wait' : 'ready'}>
                {item.isExternalPurchase ? 'Mua ngoài' : 'Tính vào hóa đơn'}
              </span>
              {canPrescribe && (
                <WsButton size="sm" variant="danger" aria-label={`Xóa dòng thuốc ${item.productName}`} disabled={remove.isPending} onClick={() => void removeItem(item)}>
                  Xóa
                </WsButton>
              )}
            </li>
          ))}
        </ul>
      )}
      {error && (
        <p role="alert" className="text-sm font-medium text-(--ws-urgent-ink)">
          {error}
        </p>
      )}
      {canPrescribe && <PrescriptionForm visitId={visitId} />}
    </section>
  );
}

function PrescriptionForm({ visitId }: { visitId: number }) {
  // API sản phẩm không có bộ lọc "thuốc kê đơn" (retailOnly=true là ngược ý): hỏi thuốc đang kinh doanh rồi lọc isPrescription ở FE.
  const products = useProducts({ productType: 'DRUG', isActive: true, size: 100 });
  const add = useAddPrescription();
  const [productId, setProductId] = useState<number | null>(null);
  const [quantity, setQuantity] = useState('1');
  const [dosage, setDosage] = useState('');
  const [error, setError] = useState<string | null>(null);
  /** Body vừa bị 400 BR-KB-03 khi chưa chọn cách xử lý: bấm một nút là gửi lại đúng body này kèm `shortageResolution` (không đọc lại các ô nhập). */
  const [shortage, setShortage] = useState<{ body: AddPrescriptionRequest; message: string } | null>(null);

  const drugs = (products.data?.content ?? []).filter((p) => p.isPrescription);
  const product = drugs.find((p) => p.productId === productId);
  const count = Number(quantity);
  const ready = product !== undefined && Number.isInteger(count) && count >= 1 && dosage.trim() !== '';

  /** Sửa ô nhập thì body đang chờ chọn cách xử lý thiếu tồn không còn là ý của bác sĩ: bỏ khung và câu báo cũ. */
  const edit = (apply: () => void) => {
    apply();
    setShortage(null);
    setError(null);
  };

  const send = async (body: AddPrescriptionRequest) => {
    setError(null);
    setShortage(null);
    try {
      const item = await add.mutateAsync({ visitId, ...body });
      toast.success(`Đã kê ${item.productName} × ${item.quantity}${item.isExternalPurchase ? ' (mua ngoài)' : ''}`);
      setProductId(null);
      setQuantity('1');
      setDosage('');
    } catch (e) {
      const apiError = toApiError(e);
      // Chỉ request CHƯA có cách xử lý mới đưa ra lựa chọn; lỗi của request đã có (vd. giảm khi tồn bằng 0) là lỗi thường.
      if (apiError.statusCode === 400 && apiError.ruleId === 'BR-KB-03' && body.shortageResolution === undefined) setShortage({ body, message: plainMessage(apiError) });
      else setError(visitErrorMessage(e, 'prescribe'));
    }
  };

  const submit = () => {
    if (product && ready) void send({ productId: product.productId, quantity: count, dosageInstructions: dosage.trim() });
  };
  const resolve = (shortageResolution: ShortageResolution) => {
    if (shortage) void send({ ...shortage.body, shortageResolution });
  };

  if (products.isLoading) return <p className={hint}>Đang tải danh mục thuốc…</p>;
  if (products.isError) return <p className="text-sm font-medium text-(--ws-urgent-ink)">{plainMessage(toApiError(products.error))}</p>;
  if (drugs.length === 0) return <p className={hint}>Chưa có thuốc kê đơn nào đang kinh doanh.</p>;

  return (
    <div className="flex flex-col gap-2 border-t border-(--ws-line) pt-3">
      <select aria-label="Chọn thuốc" className={select} value={productId ?? ''} onChange={(e) => edit(() => setProductId(e.target.value === '' ? null : Number(e.target.value)))}>
        <option value="">Chọn thuốc…</option>
        {drugs.map((p) => (
          <option key={p.productId} value={p.productId}>
            {p.name}
          </option>
        ))}
      </select>
      <div className="flex flex-wrap items-center gap-2">
        <label htmlFor={`prescription-quantity-${visitId}`} className="text-sm font-medium">
          Số lượng
        </label>
        <input
          id={`prescription-quantity-${visitId}`}
          type="number"
          inputMode="numeric"
          min={1}
          step={1}
          value={quantity}
          onChange={(e) => edit(() => setQuantity(e.target.value))}
          className={`${fieldClass} ws-num h-9 max-w-24 py-1`}
        />
        {product && <span className={hint}>{product.unit}</span>}
      </div>
      <div className="flex flex-col gap-1.5">
        <div className="flex items-baseline justify-between gap-2">
          <label htmlFor={`prescription-dosage-${visitId}`} className="text-sm font-medium">
            Liều dùng
          </label>
          <span className="ws-num text-xs text-(--ws-ink-3)">
            {dosage.length}/{MAX_DOSAGE_LENGTH}
          </span>
        </div>
        <textarea
          id={`prescription-dosage-${visitId}`}
          rows={2}
          maxLength={MAX_DOSAGE_LENGTH}
          value={dosage}
          onChange={(e) => edit(() => setDosage(e.target.value))}
          className={fieldClass}
        />
      </div>

      {shortage && (
        <div role="alert" className="flex flex-col gap-2 rounded-lg bg-(--ws-wait-bg) px-3 py-2 text-sm text-(--ws-wait-ink)">
          <p className="font-medium">{shortage.message}</p>
          <div className="flex flex-wrap gap-2">
            <WsButton size="sm" variant="primary" disabled={add.isPending} onClick={() => resolve('REDUCE_TO_AVAILABLE')}>
              Giảm xuống vừa tồn
            </WsButton>
            <WsButton size="sm" disabled={add.isPending} onClick={() => resolve('EXTERNAL_PURCHASE')}>
              Mua ngoài, giữ nguyên số lượng
            </WsButton>
            <WsButton size="sm" variant="ghost" onClick={() => setShortage(null)}>
              Hủy
            </WsButton>
          </div>
        </div>
      )}
      {error && (
        <p role="alert" className="text-sm font-medium text-(--ws-urgent-ink)">
          {error}
        </p>
      )}
      <WsButton variant="primary" className="self-start" disabled={add.isPending || !ready} onClick={submit}>
        Kê thuốc
      </WsButton>
    </div>
  );
}
