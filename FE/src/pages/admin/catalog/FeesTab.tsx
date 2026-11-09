import { useState } from 'react';
import { Inbox } from 'lucide-react';
import { Field, FormError, NoticeBar, type Notice } from '../../../components/admin/form-ui';
import { QueryError, RowsSkeleton, StatePanel } from '../../../components/staff/ui';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { formatVnDateTime } from '../../../components/appointments/format';
import { formatCurrency } from '../../../lib/utils';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { feesApi } from '../../../shared/api/fees.api';
import { useFeesMutation, useShippingFeeConfig } from '../../../shared/hooks/useFees';
import { useAccount } from '../../../shared/stores/session.store';
import type { ShippingFeeConfig } from '../../../shared/types/fees';
import { MaterialNormsCard } from './MaterialNormsCard';

/** Ô tiền: chuỗi rỗng không thành 0 âm thầm. Số âm / thập phân để máy chủ từ chối (400 BR-SP-08) và hiện đúng lời của nó. */
const moneyOf = (text: string) => (text.trim() === '' ? NaN : Number(text));

/** #2 Phí vận chuyển: một bộ chung cho cả chuỗi. Phí = 0 khi tiền hàng ≥ ngưỡng miễn phí và ngưỡng > 0 (BR-SP-08); đơn đã đặt giữ phí lúc đặt (BR-SP-11). */
function ShippingFeeCard({ config, onNotice }: { config: ShippingFeeConfig; onNotice: (notice: Notice | null) => void }) {
  // `noValidate` ở form: để máy chủ trả lời số âm / thập phân (BR-SP-08) bằng câu của nó thay vì bóng nói của trình duyệt.
  // Nhập một lần từ bản của máy chủ: trang cha gắn `key` theo `updatedAt` nên chỉ nhập lại khi bản đã lưu đổi.
  const [baseFee, setBaseFee] = useState(String(config.baseFee));
  const [freeThreshold, setFreeThreshold] = useState(String(config.freeThreshold));
  const [error, setError] = useState('');
  const save = useFeesMutation(feesApi.saveShippingFeeConfig);

  const submit = async () => {
    const body = { baseFee: moneyOf(baseFee), freeThreshold: moneyOf(freeThreshold) };
    if (Number.isNaN(body.baseFee) || Number.isNaN(body.freeThreshold)) return setError('Nhập phí cơ bản và ngưỡng miễn phí (nhập 0 nếu không miễn phí).');
    setError('');
    onNotice(null);
    try {
      await save.mutateAsync(body);
      onNotice({ kind: 'success', text: 'Đã lưu phí vận chuyển. Đơn đặt từ bây giờ tính theo mức mới.' });
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <Card className="card">
      <CardContent className="space-y-4 p-5">
        <div>
          <h2 className="text-lg font-semibold text-(--text-primary)">Phí vận chuyển</h2>
          <p className="text-sm text-(--text-secondary)">
            Áp dụng cho mọi chi nhánh. Hiện tại: {formatCurrency(config.baseFee)}
            {config.freeThreshold > 0 ? `, miễn phí từ ${formatCurrency(config.freeThreshold)}` : ', không miễn phí'} · cập nhật {formatVnDateTime(config.updatedAt)}.
          </p>
        </div>
        <form
          noValidate
          className="grid max-w-xl gap-4 sm:grid-cols-2"
          onSubmit={(e) => {
            e.preventDefault();
            submit();
          }}
        >
          <Field label="Phí vận chuyển cơ bản (₫)">
            {(id) => <Input id={id} type="number" min={0} step={1} value={baseFee} onChange={(e) => setBaseFee(e.target.value)} />}
          </Field>
          <Field label="Miễn phí từ tiền hàng (₫)" hint="0 = không miễn phí">
            {(id) => <Input id={id} type="number" min={0} step={1} value={freeThreshold} onChange={(e) => setFreeThreshold(e.target.value)} />}
          </Field>
          <div className="space-y-3 sm:col-span-2">
            <FormError message={error} />
            <Button type="submit" disabled={save.isPending}>
              {save.isPending ? 'Đang lưu...' : 'Lưu phí vận chuyển'}
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  );
}

function FeesBody() {
  const shipping = useShippingFeeConfig();
  const [notice, setNotice] = useState<Notice | null>(null);
  return (
    <div className="space-y-4">
      <NoticeBar notice={notice} />
      {shipping.data ? (
        <ShippingFeeCard key={shipping.data.updatedAt} config={shipping.data} onNotice={setNotice} />
      ) : shipping.isError ? (
        <QueryError error={shipping.error} onRetry={() => shipping.refetch()} what="phí vận chuyển" />
      ) : (
        <RowsSkeleton rows={2} />
      )}
      <MaterialNormsCard onNotice={setNotice} />
    </div>
  );
}

/** UC32 — phí & định mức (chỉ SUPER_MANAGER). */
export function FeesTab() {
  const role = useAccount()?.role;
  if (role !== 'SUPER_MANAGER') return <StatePanel icon={Inbox} title="Chỉ quản lý chuỗi cấu hình phí và định mức" />;
  return <FeesBody />;
}
