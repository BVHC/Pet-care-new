import { useState } from 'react';
import { Card, CardContent } from '../../../components/ui/card';
import { StatusBadge } from '../../../components/ui/modal-templates';
import { NoticeBar, type Notice } from '../../../components/admin/form-ui';
import { branchApi } from '../../../shared/api/branch.api';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useBranchMutation, useBranchServices } from '../../../shared/hooks/useBranch';
import type { Branch, BranchServiceItem } from '../../../shared/types/branch';
import { SERVICE_GROUP_LABELS, type ServiceGroup } from '../../../shared/types/catalog';

const GROUPS: ServiceGroup[] = ['MEDICAL', 'GROOMING', 'BOARDING'];

/** UC33 — bật / tắt dịch vụ (kể cả loại chuồng) tại chi nhánh (BR-LH-01, BR-SP-04). */
export function BranchServicesTab({ branch, canEdit }: { branch: Branch; canEdit: boolean }) {
  const services = useBranchServices(branch.branchId);
  const [notice, setNotice] = useState<Notice | null>(null);
  const toggle = useBranchMutation((s: BranchServiceItem) => branchApi.setService(branch.branchId, s.serviceId, !s.enabled));

  const onToggle = async (s: BranchServiceItem) => {
    try {
      await toggle.mutateAsync(s);
      setNotice({ kind: 'success', text: `Đã ${s.enabled ? 'tắt' : 'bật'} ${s.name} tại chi nhánh` });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  return (
    <div className="space-y-4">
      <p className="text-sm text-(--text-secondary)">
        Tắt dịch vụ chỉ chặn đặt lịch mới; lịch hẹn và đặt chỗ đã có giữ nguyên. Dịch vụ đã ngừng kinh doanh không hiện ở đây.
      </p>
      <NoticeBar notice={notice} />
      {services.isLoading && <p className="text-(--text-tertiary)">Đang tải…</p>}
      <div className="grid gap-4 lg:grid-cols-3">
        {GROUPS.map((group) => {
          const items = (services.data ?? []).filter((s) => s.group === group);
          if (!items.length) return null;
          return (
            <Card key={group} className="card">
              <CardContent className="p-5">
                <h3 className="mb-3 font-semibold text-(--text-primary)">{SERVICE_GROUP_LABELS[group]}</h3>
                <ul className="divide-y divide-(--border-color)">
                  {items.map((s) => (
                    <li key={s.serviceId} className="flex items-center justify-between gap-3 py-2">
                      <span className="text-sm text-(--text-primary)">{s.name}</span>
                      <span className="flex items-center gap-2">
                        <StatusBadge status={s.enabled ? 'Đang bật' : 'Đang tắt'} variant={s.enabled ? 'success' : 'neutral'} />
                        <input
                          type="checkbox"
                          aria-label={`Bật ${s.name} tại chi nhánh`}
                          checked={s.enabled}
                          disabled={!canEdit || toggle.isPending}
                          onChange={() => onToggle(s)}
                          className="h-4 w-4 accent-[#a43324]"
                        />
                      </span>
                    </li>
                  ))}
                </ul>
              </CardContent>
            </Card>
          );
        })}
      </div>
    </div>
  );
}
