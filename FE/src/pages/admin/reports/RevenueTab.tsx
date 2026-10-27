// Báo cáo #1 Doanh thu + #2 Thất thu (BR-BC-02) trong cùng một tab; hai khối truy vấn riêng nên một khối lỗi không che khối kia.
import { formatVnDateTime } from '../../../components/appointments/format';
import { StatusBadge } from '../../../components/ui/modal-templates';
import { formatCurrency } from '../../../lib/utils';
import { useLostRevenueReport, useRevenueReport } from '../../../shared/hooks/useReports';
import { ReportBody, Stat, type PeriodTabProps } from './ReportBody';
import { formatCount, SOURCE_LABELS } from './report-format';

export function RevenueTab({ query, enabled, branchName }: PeriodTabProps) {
  const revenue = useRevenueReport(query, enabled);
  const lost = useLostRevenueReport(query, enabled);

  return (
    <div className="space-y-4">
      <ReportBody title="Doanh thu" query={revenue} enabled={enabled} isEmpty={(d) => d.rows.length === 0}>
        {(d) => (
          <>
            <p className="px-4 pt-1 text-sm text-(--text-secondary)">Order đã thanh toán trong kỳ, theo đơn giá lúc bán.</p>
            <div className="grid grid-cols-2 gap-4 p-4">
              <Stat label="Tổng doanh thu" value={formatCurrency(d.totalAmount)} />
              <Stat label="Số Order đã thanh toán" value={formatCount(d.rows.reduce((sum, r) => sum + r.orderCount, 0))} />
            </div>
            <table className="table" aria-label="Doanh thu theo chi nhánh và nguồn">
              <thead>
                <tr>
                  <th>Chi nhánh</th>
                  <th>Nguồn</th>
                  <th className="text-right!">Số Order</th>
                  <th className="text-right!">Doanh thu</th>
                </tr>
              </thead>
              <tbody>
                {d.rows.map((r) => (
                  <tr key={`${r.branchId}-${r.source}`}>
                    <td className="font-medium">{r.branchName || branchName(r.branchId)}</td>
                    <td>{SOURCE_LABELS[r.source]}</td>
                    <td className="text-right tabular-nums">{formatCount(r.orderCount)}</td>
                    <td className="text-right tabular-nums">{formatCurrency(r.amount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </ReportBody>

      <ReportBody title="Thất thu" query={lost} enabled={enabled} isEmpty={(d) => d.rows.length === 0}>
        {(d) => (
          <>
            <p className="px-4 pt-1 text-sm text-(--text-secondary)">
              Order bị hủy vì khách không thanh toán. Hủy trong phiên trả thú lưu trú không tính.
            </p>
            <div className="grid grid-cols-2 gap-4 p-4">
              <Stat label="Order thất thu" value={formatCount(d.count)} />
              <Stat label="Giá trị thất thu" value={formatCurrency(d.totalAmount)} />
            </div>
            <table className="table" aria-label="Order thất thu">
              <thead>
                <tr>
                  <th>Mã Order</th>
                  <th>Chi nhánh</th>
                  <th>Nguồn</th>
                  <th className="text-right!">Số tiền</th>
                  <th>Lý do</th>
                  <th>Thời điểm hủy</th>
                </tr>
              </thead>
              <tbody>
                {d.rows.map((r) => (
                  <tr key={r.orderId}>
                    <td className="font-mono text-xs!">{r.orderCode}</td>
                    <td>{branchName(r.branchId)}</td>
                    <td>
                      <StatusBadge status={SOURCE_LABELS[r.source]} variant="neutral" />
                    </td>
                    <td className="text-right tabular-nums">{formatCurrency(r.amount)}</td>
                    <td>{r.reason}</td>
                    <td className="whitespace-nowrap text-(--text-secondary)!">{formatVnDateTime(r.cancelledAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </ReportBody>
    </div>
  );
}
