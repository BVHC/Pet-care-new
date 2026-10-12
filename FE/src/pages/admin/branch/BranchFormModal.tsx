import { useState } from 'react';
import { Input } from '../../../components/ui/input';
import { FormModal } from '../../../components/ui/modal-templates';
import { Checkbox, Field, FormError } from '../../../components/admin/form-ui';
import { branchApi } from '../../../shared/api/branch.api';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useBranchMutation } from '../../../shared/hooks/useBranch';
import type { Branch } from '../../../shared/types/branch';

/** SĐT chi nhánh theo hợp đồng branch-v1 (BE chưa kiểm định dạng). */
const PHONE_RE = /^0\d{9,10}$/;

/** UC12 — tạo / sửa chi nhánh, bật cờ cấp cứu ngoài giờ (BR-CN-01, 05). Chỉ SUPER_MANAGER. */
export function BranchFormModal({
  branch,
  onClose,
  onSaved,
}: {
  branch: Branch | null;
  onClose: () => void;
  onSaved: (notice: string) => void;
}) {
  const [form, setForm] = useState({
    name: branch?.name ?? '',
    address: branch?.address ?? '',
    phone: branch?.phone ?? '',
    latitude: branch ? String(branch.latitude) : '',
    longitude: branch ? String(branch.longitude) : '',
    emergency: branch?.acceptsAfterHoursEmergency ?? false,
  });
  const [error, setError] = useState('');
  const set = (patch: Partial<typeof form>) => setForm((f) => ({ ...f, ...patch }));

  const save = useBranchMutation(() => {
    const body = {
      name: form.name.trim(),
      address: form.address.trim(),
      phone: form.phone.trim(),
      latitude: Number(form.latitude),
      longitude: Number(form.longitude),
      acceptsAfterHoursEmergency: form.emergency,
    };
    return branch ? branchApi.updateBranch(branch.branchId, body) : branchApi.createBranch(body);
  });

  const inRange = (v: string, max: number) => v.trim() !== '' && Number.isFinite(Number(v)) && Math.abs(Number(v)) <= max;

  const submit = async () => {
    const invalid =
      (!form.name.trim() && 'Nhập tên chi nhánh.') ||
      (!form.address.trim() && 'Nhập địa chỉ.') ||
      (!PHONE_RE.test(form.phone.trim()) && 'Số điện thoại gồm 10–11 số, bắt đầu bằng 0.') ||
      (!inRange(form.latitude, 90) && 'Vĩ độ phải từ -90 đến 90.') ||
      (!inRange(form.longitude, 180) && 'Kinh độ phải từ -180 đến 180.');
    if (invalid) return setError(invalid);
    try {
      const saved = await save.mutateAsync(undefined);
      onSaved(branch ? `Đã lưu chi nhánh ${saved.name}` : `Đã tạo chi nhánh ${saved.name} (nháp)`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title={branch ? 'Sửa chi nhánh' : 'Thêm chi nhánh'}
      description={branch ? undefined : 'Chi nhánh mới ở trạng thái Nháp; kích hoạt khi đã có quản lý và giờ mở cửa.'}
      onSubmit={submit}
      submitText={branch ? 'Lưu thay đổi' : 'Tạo chi nhánh'}
      loading={save.isPending}
      size="lg"
    >
      <Field label="Tên chi nhánh">
        {(id) => <Input id={id} value={form.name} maxLength={150} onChange={(e) => set({ name: e.target.value })} />}
      </Field>
      <Field label="Địa chỉ">
        {(id) => <Input id={id} value={form.address} maxLength={300} onChange={(e) => set({ address: e.target.value })} />}
      </Field>
      <Field label="Số điện thoại">
        {(id) => <Input id={id} value={form.phone} maxLength={11} placeholder="0901234567" onChange={(e) => set({ phone: e.target.value })} />}
      </Field>
      <div className="grid grid-cols-2 gap-4">
        <Field label="Vĩ độ" hint="Tọa độ bản đồ, ví dụ 21.0285">
          {(id) => <Input id={id} type="number" step="any" value={form.latitude} onChange={(e) => set({ latitude: e.target.value })} />}
        </Field>
        <Field label="Kinh độ" hint="Ví dụ 105.8542">
          {(id) => <Input id={id} type="number" step="any" value={form.longitude} onChange={(e) => set({ longitude: e.target.value })} />}
        </Field>
      </div>
      <Checkbox
        label="Nhận cấp cứu ngoài giờ (BR-CN-05)"
        checked={form.emergency}
        onChange={(checked) => set({ emergency: checked })}
      />
      <FormError message={error} />
    </FormModal>
  );
}
