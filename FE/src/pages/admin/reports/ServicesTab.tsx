// Báo cáo #3 Lượt dịch vụ hoàn tất theo nhóm và theo dịch vụ (BR-BC-03).
import { useCompletedServicesReport } from '../../../shared/hooks/useReports';
import { QUOTA_GROUP_LABELS } from '../../../shared/types/branch';
import type { ReportServiceGroup } from '../../../shared/types/report';
import { ReportBody, Stat, type PeriodTabProps } from './ReportBody';
import { formatCount } from './report-format';

const GROUPS = Object.keys(QUOTA_GROUP_LABELS) as ReportServiceGroup[];

export function ServicesTab({ query, enabled, branchName }: PeriodTabProps) {
  const report = useCompletedServicesReport(query, enabled);
  return (
    <ReportBody title="Lượt dịch vụ hoàn tất" query={report} enabled={enabled} isEmpty={(d) => d.rows.length === 0}>
      {(d) => (
        <>
          <div className="grid grid-cols-2 gap-4 p-4">
            {GROUPS.map((g) => (
              <Stat
                key={g}
                label={`Nhóm ${QUOTA_GROUP_LABELS[g]}`}
                value={formatCount(d.rows.filter((r) => r.serviceGroup === g).reduce((sum, r) => sum + r.count, 0))}
              />
            ))}
          </div>
          <table className="table" aria-label="Lượt dịch vụ theo dịch vụ">
            <thead>
              <tr>
                <th>Chi nhánh</th>
                <th>Nhóm</th>
                <th>Dịch vụ</th>
                <th className="text-right!">Số lượt</th>
              </tr>
            </thead>
            <tbody>
              {d.rows.map((r) => (
                <tr key={`${r.branchId}-${r.serviceId}`}>
                  <td className="font-medium">{branchName(r.branchId)}</td>
                  <td>{QUOTA_GROUP_LABELS[r.serviceGroup]}</td>
                  <td>{r.serviceName}</td>
                  <td className="text-right tabular-nums">{formatCount(r.count)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </ReportBody>
  );
}
