// Chi tiết một ca nội trú (inpatient-v1 #3): thông tin ca, xuất viện (VET), y lệnh, chỉ số theo dõi.
import { useState } from 'react';
import { LogOut } from 'lucide-react';
import { formatVnDateTime } from '@/components/appointments/format';
import { QueryError, RowsSkeleton } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { InfoRow, StatusBadge } from '@/components/ui/modal-templates';
import { formatCurrency } from '@/lib/utils';
import { useAdmission } from '@/shared/hooks/useInpatient';
import type { Role } from '@/shared/types/auth';
import { ADMISSION_STATUS_LABELS, DISCHARGE_OUTCOME_LABELS } from '@/shared/types/inpatient';
import { DischargeDialog } from './AdmissionDialogs';
import { ObservationsSection } from './ObservationsSection';
import { OrdersSection } from './OrdersSection';
import { inpatientPermissions } from './permissions';

export function AdmissionDetail({ admissionId, kennelCode, role, onNotice }: { admissionId: number; kennelCode?: string | null; role: Role | undefined; onNotice: (text: string) => void }) {
  const query = useAdmission(admissionId);
  const [discharging, setDischarging] = useState(false);
  const a = query.data;
  if (!a) return query.isError ? <QueryError error={query.error} onRetry={() => query.refetch()} what="ca nội trú" /> : <RowsSkeleton rows={5} />;
  const perms = inpatientPermissions(role);
  const active = a.status === 'ADMITTED';

  return (
    <div className="space-y-4" aria-label={`Ca nội trú ${a.code}`} role="region">
      <Card className="card">
        <CardContent className="space-y-3 p-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <h2 className="text-xl font-semibold text-(--text-primary)">{a.petName}</h2>
              <StatusBadge status={ADMISSION_STATUS_LABELS[a.status]} variant={active ? 'info' : 'neutral'} />
              <span className="text-sm text-(--text-secondary)">{a.code}</span>
            </div>
            {active && perms.discharge && (
              <Button variant="outline" onClick={() => setDischarging(true)}>
                <LogOut className="h-4 w-4" aria-hidden /> Xuất viện
              </Button>
            )}
          </div>
          <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm">
            <InfoRow label="Dịch vụ" value={`${a.serviceName} · ${formatCurrency(a.unitPrice)}/ngày`} />
            <InfoRow label="Chuồng" value={kennelCode ?? (a.kennelId ? `#${a.kennelId}` : 'Chưa gán')} />
            <InfoRow label="Nhập viện lúc" value={formatVnDateTime(a.admittedAt)} />
            {a.dischargedAt && <InfoRow label="Xuất viện lúc" value={formatVnDateTime(a.dischargedAt)} />}
            {a.dischargeOutcome && <InfoRow label="Lý do xuất viện" value={DISCHARGE_OUTCOME_LABELS[a.dischargeOutcome]} />}
          </dl>
          {a.dischargeSummary && (
            <p className="rounded-lg bg-(--bg-tertiary) px-3 py-2 text-sm">
              <strong>Tóm tắt điều trị:</strong> {a.dischargeSummary}
            </p>
          )}
        </CardContent>
      </Card>
      <OrdersSection admission={a} perms={perms} onNotice={onNotice} />
      <ObservationsSection admission={a} perms={perms} onNotice={onNotice} />
      {discharging && (
        <DischargeDialog
          admission={a}
          onClose={() => setDischarging(false)}
          onDone={(text) => {
            setDischarging(false);
            onNotice(text);
          }}
        />
      )}
    </div>
  );
}
