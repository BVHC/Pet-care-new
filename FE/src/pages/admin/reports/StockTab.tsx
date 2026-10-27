// Báo cáo #6 Tồn kho: hàng dưới ngưỡng và lô sắp / đã hết hạn (BR-BC-03, BR-KO-07). Không theo kỳ, tính tại thời điểm xem.
import { useState } from 'react';
import { Field } from '../../../components/admin/form-ui';
import { formatShortDate } from '../../../components/appointments/format';
import { Input } from '../../../components/ui/input';
import { StatusBadge } from '../../../components/ui/modal-templates';
import { useStockAlertReport } from '../../../shared/hooks/useReports';
import { ReportBody } from './ReportBody';
import { formatCount, parseWithinDays } from './report-format';

/** [CFG] hạn dùng cảnh báo mặc định 30 ngày (report-v1 A2, BR-KO-07). */
const DEFAULT_WITHIN_DAYS = '30';

interface Props {
  branchId?: number;
  branchName: (branchId: number) => string;
}

const Empty = ({ children }: { children: string }) => <p className="px-4 py-6 text-sm text-(--text-secondary)">{children}</p>;

export function StockTab({ branchId, branchName }: Props) {
  const [withinText, setWithinText] = useState(DEFAULT_WITHIN_DAYS);
  const withinDays = parseWithinDays(withinText);
  const report = useStockAlertReport({ branchId, withinDays: withinDays ?? undefined }, withinDays !== null);

  return (
    <div className="space-y-4">
      <div className="card max-w-xs p-4">
        <Field label="Lô hết hạn trong vòng (ngày)" error={withinDays === null ? 'Nhập số ngày là số nguyên dương.' : undefined}>
          {(id) => (
            <Input id={id} type="number" min={1} step={1} className="h-10 py-0" value={withinText} onChange={(e) => setWithinText(e.target.value)} />
          )}
        </Field>
      </div>

      <ReportBody
        title="Tồn kho cần chú ý"
        query={report}
        enabled={withinDays !== null}
        idleText="Nhập số ngày hợp lệ để xem cảnh báo tồn kho."
        emptyText="Không có hàng dưới ngưỡng và không có lô sắp hết hạn"
        isEmpty={(d) => d.lowStock.length === 0 && d.expiringLots.length === 0}
      >
        {(d) => (
          <div className="space-y-4 pb-2">
            <div>
              <h3 className="px-4 pt-3 text-sm font-semibold text-(--text-primary)">Hàng dưới tồn tối thiểu</h3>
              {d.lowStock.length === 0 ? (
                <Empty>Không có hàng nào dưới tồn tối thiểu.</Empty>
              ) : (
                <table className="table mt-2" aria-label="Hàng dưới tồn tối thiểu">
                  <thead>
                    <tr>
                      <th>Chi nhánh</th>
                      <th>Sản phẩm</th>
                      <th className="text-right!">Khả dụng</th>
                      <th className="text-right!">Tồn tối thiểu</th>
                    </tr>
                  </thead>
                  <tbody>
                    {d.lowStock.map((r) => (
                      <tr key={`${r.branchId}-${r.productId}`}>
                        <td className="font-medium">{branchName(r.branchId)}</td>
                        <td>{r.productName}</td>
                        <td className="text-right font-semibold tabular-nums">{formatCount(r.availableQuantity)}</td>
                        <td className="text-right tabular-nums">{formatCount(r.minQuantity)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>

            <div>
              <h3 className="px-4 text-sm font-semibold text-(--text-primary)">Lô sắp hết hạn và đã hết hạn</h3>
              {d.expiringLots.length === 0 ? (
                <Empty>Không có lô nào sắp hết hạn.</Empty>
              ) : (
                <table className="table mt-2" aria-label="Lô sắp hết hạn và đã hết hạn">
                  <thead>
                    <tr>
                      <th>Chi nhánh</th>
                      <th>Sản phẩm</th>
                      <th>Số lô</th>
                      <th>Hạn dùng</th>
                      <th className="text-right!">Số lượng</th>
                      <th>Tình trạng</th>
                    </tr>
                  </thead>
                  <tbody>
                    {d.expiringLots.map((r) => (
                      <tr key={r.stockLotId}>
                        <td className="font-medium">{branchName(r.branchId)}</td>
                        <td>{r.productName}</td>
                        <td className="font-mono text-xs!">{r.lotNumber || '—'}</td>
                        <td className="whitespace-nowrap">{formatShortDate(r.expiryDate)}</td>
                        <td className="text-right tabular-nums">{formatCount(r.quantity)}</td>
                        <td>
                          <StatusBadge status={r.expired ? 'Đã hết hạn' : 'Sắp hết hạn'} variant={r.expired ? 'danger' : 'warning'} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        )}
      </ReportBody>
    </div>
  );
}
