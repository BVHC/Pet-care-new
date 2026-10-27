// Báo cáo #7 Feedback: số lượng và điểm trung bình theo chi nhánh (BR-BC-03). Dòng không gắn chi nhánh là góp ý về website.
import { useFeedbackReport } from '../../../shared/hooks/useReports';
import type { FeedbackStatRow } from '../../../shared/types/report';
import { ReportBody, type PeriodTabProps } from './ReportBody';
import { formatCount, formatRating } from './report-format';

export function FeedbackTab({ query, enabled, branchName }: PeriodTabProps) {
  const report = useFeedbackReport(query, enabled);
  const label = (r: FeedbackStatRow) => r.branchName || (r.branchId == null ? 'Không gắn chi nhánh' : branchName(r.branchId));
  return (
    <ReportBody title="Feedback" query={report} enabled={enabled} isEmpty={(d) => d.rows.length === 0}>
      {(d) => (
        <table className="table mt-3" aria-label="Feedback theo chi nhánh">
          <thead>
            <tr>
              <th>Chi nhánh</th>
              <th className="text-right!">Số feedback</th>
              <th className="text-right!">Điểm trung bình</th>
            </tr>
          </thead>
          <tbody>
            {d.rows.map((r) => (
              <tr key={r.branchId ?? 'none'}>
                <td className="font-medium">{label(r)}</td>
                <td className="text-right tabular-nums">{formatCount(r.count)}</td>
                <td className="text-right tabular-nums">{formatRating(r.averageRating)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </ReportBody>
  );
}
