// Báo cáo #5 Tỷ lệ quay lại tái chủng theo loại vaccine (BR-BC-04): đã quay lại / đến hạn.
import { useRevaccinationReport } from '../../../shared/hooks/useReports';
import { ReportBody, type PeriodTabProps } from './ReportBody';
import { formatCount, formatRate } from './report-format';

export function RevaccinationTab({ query, enabled, branchName }: PeriodTabProps) {
  const report = useRevaccinationReport(query, enabled);
  return (
    <ReportBody title="Tái chủng" query={report} enabled={enabled} isEmpty={(d) => d.rows.length === 0}>
      {(d) => (
        <>
          <p className="px-4 pt-1 text-sm text-(--text-secondary)">
            Đo khách quay lại tiêm đúng hạn tại hệ thống; mũi tiêm ở nơi khác không được ghi nhận.
          </p>
          <table className="table mt-3" aria-label="Tái chủng theo loại vaccine">
            <thead>
              <tr>
                <th>Chi nhánh</th>
                <th>Loại vaccine</th>
                <th className="text-right!">Đã quay lại / đến hạn</th>
                <th className="text-right!">Tỷ lệ quay lại</th>
              </tr>
            </thead>
            <tbody>
              {d.rows.map((r) => (
                <tr key={`${r.branchId}-${r.vaccineTypeId}`}>
                  <td className="font-medium">{branchName(r.branchId)}</td>
                  <td>{r.vaccineTypeName}</td>
                  <td className="text-right tabular-nums">
                    {formatCount(r.returnedCount)} / {formatCount(r.dueCount)}
                  </td>
                  <td className="text-right font-semibold tabular-nums">{formatRate(r.rate, r.dueCount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </ReportBody>
  );
}
