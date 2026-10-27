// Báo cáo #4 NO_SHOW và hủy muộn trên tổng lịch hẹn đến hạn trong kỳ (BR-BC-03). Tử số / mẫu số kèm phần trăm.
import { useNoShowReport } from '../../../shared/hooks/useReports';
import { ReportBody, type PeriodTabProps } from './ReportBody';
import { formatCount, formatRate } from './report-format';

export function NoShowTab({ query, enabled, branchName }: PeriodTabProps) {
  const report = useNoShowReport(query, enabled);
  return (
    <ReportBody title="NO_SHOW và hủy muộn" query={report} enabled={enabled} isEmpty={(d) => d.rows.length === 0}>
      {(d) => (
        <table className="table mt-3" aria-label="NO_SHOW và hủy muộn theo chi nhánh">
          <thead>
            <tr>
              <th>Chi nhánh</th>
              <th className="text-right!">Lịch hẹn đến hạn</th>
              <th className="text-right!">Không đến (NO_SHOW)</th>
              <th className="text-right!">Tỷ lệ không đến</th>
              <th className="text-right!">Hủy muộn</th>
              <th className="text-right!">Tỷ lệ hủy muộn</th>
            </tr>
          </thead>
          <tbody>
            {d.rows.map((r) => (
              <tr key={r.branchId}>
                <td className="font-medium">{branchName(r.branchId)}</td>
                <td className="text-right tabular-nums">{formatCount(r.dueCount)}</td>
                <td className="text-right tabular-nums">
                  {formatCount(r.noShowCount)} / {formatCount(r.dueCount)}
                </td>
                <td className="text-right font-semibold tabular-nums">{formatRate(r.noShowRate, r.dueCount)}</td>
                <td className="text-right tabular-nums">
                  {formatCount(r.lateCancelCount)} / {formatCount(r.dueCount)}
                </td>
                <td className="text-right font-semibold tabular-nums">{formatRate(r.lateCancelRate, r.dueCount)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </ReportBody>
  );
}
