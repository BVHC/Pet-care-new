/**
 * Nội trú (UC55, UC56) — inpatient-v1 #1–10. Danh sách ca của chi nhánh theo trạng thái (mặc định đang điều trị) và chi tiết ca bên cạnh:
 * y lệnh, chỉ số theo dõi, xuất viện. VET làm mọi việc (kể cả nhập viện); CARETAKER thực hiện y lệnh không phải thuốc và ghi theo dõi; lễ tân chỉ xem.
 * Nút không thuộc vai trò bị ẩn, nút theo trạng thái của ca / y lệnh.
 */
import { useState } from 'react';
import { BedDouble, Plus } from 'lucide-react';
import { NoticeBar, type Notice } from '@/components/admin/form-ui';
import { Pager } from '@/components/admin/Pager';
import { formatVnDateTime } from '@/components/appointments/format';
import { QueryError, RowsSkeleton, StatePanel } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { cn } from '@/lib/utils';
import { useAdmissions } from '@/shared/hooks/useInpatient';
import { useAccount } from '@/shared/stores/session.store';
import { ADMISSION_STATUS_LABELS, ADMISSION_STATUSES, type AdmissionStatus } from '@/shared/types/inpatient';
import { AdmitDialog } from './inpatient/AdmissionDialogs';
import { AdmissionDetail } from './inpatient/AdmissionDetail';
import { inpatientPermissions } from './inpatient/permissions';

const PAGE_SIZE = 15;

export function AdminInpatientPage() {
  const role = useAccount()?.role;
  const perms = inpatientPermissions(role);
  const [status, setStatus] = useState<AdmissionStatus>('ADMITTED');
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [admitting, setAdmitting] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const list = useAdmissions({ status, page, size: PAGE_SIZE });
  const rows = list.data?.content ?? [];
  const selected = rows.find((r) => r.admissionId === selectedId) ?? null;

  const changeStatus = (next: AdmissionStatus) => {
    setStatus(next);
    setPage(0);
    setSelectedId(null);
    setNotice(null);
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-(--text-primary)">Nội trú</h1>
          <p className="text-(--text-secondary)">Thú đang điều trị nội trú tại chi nhánh: y lệnh, chỉ số theo dõi và xuất viện.</p>
        </div>
        {perms.admit && (
          <Button onClick={() => setAdmitting(true)}>
            <Plus className="h-4 w-4" aria-hidden /> Nhập viện
          </Button>
        )}
      </div>

      <NoticeBar notice={notice} />

      <div className="grid items-start gap-4 lg:grid-cols-[360px_minmax(0,1fr)]">
        <Card className="card">
          <CardContent className="p-0">
            <div className="border-b border-(--border-color) p-3">
              <Tabs value={status} onValueChange={(v) => changeStatus(v as AdmissionStatus)}>
                <TabsList aria-label="Lọc trạng thái ca">
                  {ADMISSION_STATUSES.map((s) => (
                    <TabsTrigger key={s} value={s}>
                      {ADMISSION_STATUS_LABELS[s]}
                    </TabsTrigger>
                  ))}
                </TabsList>
              </Tabs>
            </div>
            {list.isError && !list.data ? (
              <QueryError error={list.error} onRetry={() => list.refetch()} what="danh sách ca nội trú" />
            ) : !list.data ? (
              <RowsSkeleton rows={4} />
            ) : rows.length === 0 ? (
              <StatePanel
                icon={BedDouble}
                title={status === 'ADMITTED' ? 'Chưa có thú nào đang nội trú' : 'Chưa có ca nào đã xuất viện'}
                body={status === 'ADMITTED' ? 'Bác sĩ nhập viện từ lượt khám hoặc từ đặt chỗ lưu trú.' : undefined}
              />
            ) : (
              <>
                <ul aria-label="Danh sách ca nội trú" className="divide-y divide-(--border-color)">
                  {rows.map((r) => (
                    <li key={r.admissionId}>
                      <button
                        type="button"
                        aria-label={`Xem ca ${r.code}`}
                        aria-current={r.admissionId === selectedId ? 'true' : undefined}
                        onClick={() => setSelectedId(r.admissionId)}
                        className={cn('flex w-full flex-col items-start gap-0.5 px-4 py-3 text-left hover:bg-(--bg-tertiary)', r.admissionId === selectedId && 'bg-(--bg-tertiary)')}
                      >
                        <span className="font-semibold text-(--text-primary)">{r.petName}</span>
                        <span className="text-xs text-(--text-secondary)">
                          {r.code} · chuồng {r.kennelCode ?? 'chưa gán'}
                        </span>
                        <span className="text-xs text-(--text-secondary)">Nhập viện {formatVnDateTime(r.admittedAt)}</span>
                      </button>
                    </li>
                  ))}
                </ul>
                <Pager
                  data={list.data}
                  unit="ca"
                  onPage={(p) => {
                    setPage(p);
                    setSelectedId(null);
                  }}
                />
              </>
            )}
          </CardContent>
        </Card>

        {selectedId != null ? (
          <AdmissionDetail key={selectedId} admissionId={selectedId} kennelCode={selected?.kennelCode} role={role} onNotice={(text) => setNotice({ kind: 'success', text })} />
        ) : (
          <Card className="card">
            <CardContent className="p-0">
              <StatePanel icon={BedDouble} title="Chọn một ca để xem chi tiết" body="Y lệnh, chỉ số theo dõi và xuất viện nằm ở đây." />
            </CardContent>
          </Card>
        )}
      </div>

      {admitting && (
        <AdmitDialog
          onClose={() => setAdmitting(false)}
          onDone={(admission) => {
            setAdmitting(false);
            setStatus('ADMITTED');
            setPage(0);
            setSelectedId(admission.admissionId);
            setNotice({ kind: 'success', text: `Đã nhập viện ${admission.petName} (${admission.code}).` });
          }}
        />
      )}
    </div>
  );
}
