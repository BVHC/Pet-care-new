import { useState } from 'react';
import { Edit, Eye, EyeOff, Plus } from 'lucide-react';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { FormModal } from '../../../components/ui/modal-templates';
import { cn, formatCurrency } from '../../../lib/utils';
import { catalogApi } from '../../../shared/api/catalog.api';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useCatalogMutation, useServices } from '../../../shared/hooks/useCatalog';
import {
  MEDICAL_TYPE_LABELS,
  SERVICE_GROUP_LABELS,
  SPECIES_LABELS,
  type MedicalType,
  type Service,
  type ServiceGroup,
  type Species,
} from '../../../shared/types/catalog';
import { ActiveBadge, Checkbox, EmptyRow, Field, FormError, NoticeBar, selectClass, type Notice } from '../../../components/admin/form-ui';

const GROUPS = Object.keys(SERVICE_GROUP_LABELS) as ServiceGroup[];
const SPECIES = Object.keys(SPECIES_LABELS) as Species[];

const priceText = (s: Service) =>
  `${s.priceIsFrom ? 'Từ ' : ''}${formatCurrency(s.price)}${s.group === 'BOARDING' ? '/đêm' : ''}`;

/** UC30 — dịch vụ; loại chuồng là dịch vụ nhóm Lưu trú (BR-SP-04), nhóm Khám/Tiêm có loại (BR-SP-06). */
export function ServicesTab() {
  const [group, setGroup] = useState('');
  const services = useServices((group || undefined) as ServiceGroup | undefined);
  const [editing, setEditing] = useState<Service | 'new' | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const toggle = useCatalogMutation((s: Service) => catalogApi.updateService(s.serviceId, { isActive: !s.isActive }));

  const onToggle = async (s: Service) => {
    try {
      await toggle.mutateAsync(s);
      setNotice({ kind: 'success', text: `${s.isActive ? 'Đã ngừng' : 'Đã mở lại'} ${s.name}` });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="p-4 flex flex-wrap items-center justify-between gap-3">
          <select aria-label="Lọc nhóm dịch vụ" className={cn(selectClass, 'w-56')} value={group} onChange={(e) => setGroup(e.target.value)}>
            <option value="">Mọi nhóm</option>
            {GROUPS.map((g) => (
              <option key={g} value={g}>
                {SERVICE_GROUP_LABELS[g]}
              </option>
            ))}
          </select>
          <Button onClick={() => setEditing('new')}>
            <Plus className="h-4 w-4" />
            Thêm dịch vụ
          </Button>
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Tên dịch vụ</th>
                <th>Nhóm</th>
                <th className="text-right">Giá</th>
                <th>Loại chuồng</th>
                <th>Trạng thái</th>
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {services.data?.length ? (
                services.data.map((s) => (
                  <tr key={s.serviceId}>
                    <td className="font-medium text-(--text-primary)">{s.name}</td>
                    <td className="text-(--text-secondary)">
                      {SERVICE_GROUP_LABELS[s.group]}
                      {s.medicalType && ` · ${MEDICAL_TYPE_LABELS[s.medicalType]}`}
                    </td>
                    <td className="text-right font-medium">{priceText(s)}</td>
                    <td className="text-(--text-secondary)">
                      {s.kennelType ? `${SPECIES_LABELS[s.kennelType.species]} · tối đa ${s.kennelType.maxWeightKg} kg` : '—'}
                    </td>
                    <td>
                      <ActiveBadge active={s.isActive} />
                    </td>
                    <td>
                      <div className="flex justify-end gap-1">
                        <Button size="sm" variant="ghost" onClick={() => setEditing(s)} aria-label={`Sửa ${s.name}`} title="Sửa">
                          <Edit className="h-4 w-4" />
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => onToggle(s)}
                          aria-label={`${s.isActive ? 'Ngừng' : 'Mở lại'} ${s.name}`}
                          title={s.isActive ? 'Ngừng kinh doanh' : 'Mở lại'}
                        >
                          {s.isActive ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))
              ) : (
                <EmptyRow colSpan={6} loading={services.isLoading} />
              )}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {editing && (
        <ServiceForm
          service={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(text) => {
            setEditing(null);
            setNotice({ kind: 'success', text });
          }}
        />
      )}
    </div>
  );
}

interface ServiceDraft {
  name: string;
  group: ServiceGroup | '';
  medicalType: MedicalType | '';
  price: string;
  priceIsFrom: boolean;
  species: Species | '';
  maxWeightKg: string;
  description: string;
}

const draftOf = (s: Service | null): ServiceDraft => ({
  name: s?.name ?? '',
  group: s?.group ?? '',
  medicalType: s?.medicalType ?? '',
  price: s ? String(s.price) : '',
  priceIsFrom: s?.priceIsFrom ?? false,
  species: s?.kennelType?.species ?? '',
  maxWeightKg: s?.kennelType ? String(s.kennelType.maxWeightKg) : '',
  description: s?.description ?? '',
});

function ServiceForm({ service, onClose, onSaved }: { service: Service | null; onClose: () => void; onSaved: (notice: string) => void }) {
  const [form, setForm] = useState(() => draftOf(service));
  const [error, setError] = useState('');
  const set = (patch: Partial<ServiceDraft>) => setForm((f) => ({ ...f, ...patch }));
  const isMedical = form.group === 'MEDICAL';
  const isBoarding = form.group === 'BOARDING';

  const save = useCatalogMutation(async (f: ServiceDraft) => {
    // PATCH: BE giữ nguyên khi vắng / null, chỉ xóa khi nhận "" → sửa mà xóa trắng ô mô tả phải gửi "" (T49 nhóm 2, hàng 4).
    // Mô tả không đổi thì không gửi (null cũ không bị ghi thành ""); tạo mới mà để trống thì bỏ trường.
    const description = f.description.trim();
    const body = {
      name: f.name.trim(),
      medicalType: isMedical ? (f.medicalType as MedicalType) : undefined,
      price: Number(f.price),
      priceIsFrom: f.priceIsFrom,
      description: service ? (description === (service.description ?? '') ? undefined : description) : description || undefined,
      kennelType: isBoarding ? { species: f.species as Species, maxWeightKg: Number(f.maxWeightKg) } : undefined,
    };
    return service
      ? catalogApi.updateService(service.serviceId, body)
      : catalogApi.createService({ ...body, group: f.group as ServiceGroup });
  });

  const submit = async () => {
    const invalid =
      (!form.name.trim() && 'Nhập tên dịch vụ.') ||
      (!form.group && 'Chọn nhóm dịch vụ.') ||
      (isMedical && !form.medicalType && 'Dịch vụ nhóm Khám/Tiêm phải chọn loại Khám hoặc Tiêm') ||
      (isBoarding && !form.species && 'Loại chuồng phải có loài phù hợp') ||
      (isBoarding && !(Number(form.maxWeightKg) > 0) && 'Nhập cân nặng tối đa lớn hơn 0.') ||
      ((form.price === '' || Number(form.price) < 0) && 'Giá phải từ 0 đồng trở lên.');
    if (invalid) return setError(invalid);
    try {
      const saved = await save.mutateAsync(form);
      onSaved(service ? `Đã lưu ${saved.name}` : `Đã thêm ${saved.name}`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title={service ? 'Sửa dịch vụ' : 'Thêm dịch vụ'}
      description={service ? 'Nhóm dịch vụ không đổi được sau khi tạo. Đổi giá không ảnh hưởng đơn đã có.' : 'Loại chuồng là dịch vụ nhóm Lưu trú.'}
      onSubmit={submit}
      submitText={service ? 'Lưu thay đổi' : 'Tạo dịch vụ'}
      loading={save.isPending}
      size="lg"
    >
      <Field label="Tên dịch vụ">
        {(id) => <Input id={id} value={form.name} maxLength={150} onChange={(e) => set({ name: e.target.value })} />}
      </Field>
      <div className="grid grid-cols-2 gap-4">
        <Field label="Nhóm dịch vụ">
          {(id) => (
            <select
              id={id}
              className={selectClass}
              value={form.group}
              disabled={!!service}
              onChange={(e) => set({ group: e.target.value as ServiceGroup, medicalType: '', species: '', maxWeightKg: '' })}
            >
              <option value="">— Chọn nhóm —</option>
              {GROUPS.map((g) => (
                <option key={g} value={g}>
                  {SERVICE_GROUP_LABELS[g]}
                </option>
              ))}
            </select>
          )}
        </Field>
        {isMedical && (
          <Field label="Loại dịch vụ" hint="Quyết định điều kiện hoàn tất lượt (BR-KB-02).">
            {(id) => (
              <select id={id} className={selectClass} value={form.medicalType} onChange={(e) => set({ medicalType: e.target.value as MedicalType })}>
                <option value="">— Chọn Khám hoặc Tiêm —</option>
                {(Object.keys(MEDICAL_TYPE_LABELS) as MedicalType[]).map((t) => (
                  <option key={t} value={t}>
                    {MEDICAL_TYPE_LABELS[t]}
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}
      </div>
      {isBoarding && (
        <div className="grid grid-cols-2 gap-4">
          <Field label="Loài">
            {(id) => (
              <select id={id} className={selectClass} value={form.species} onChange={(e) => set({ species: e.target.value as Species })}>
                <option value="">— Chọn loài —</option>
                {SPECIES.map((s) => (
                  <option key={s} value={s}>
                    {SPECIES_LABELS[s]}
                  </option>
                ))}
              </select>
            )}
          </Field>
          <Field label="Cân nặng tối đa (kg)">
            {(id) => <Input id={id} type="number" min={0.01} step={0.01} value={form.maxWeightKg} onChange={(e) => set({ maxWeightKg: e.target.value })} />}
          </Field>
        </div>
      )}
      <Field label={isBoarding ? 'Giá theo đêm (đ)' : 'Giá (đ)'}>
        {(id) => <Input id={id} type="number" min={0} step={1000} value={form.price} onChange={(e) => set({ price: e.target.value })} />}
      </Field>
      <Checkbox label='Hiện giá dạng "Từ …" trên trang công khai (BR-CK-02)' checked={form.priceIsFrom} onChange={(c) => set({ priceIsFrom: c })} />
      <Field label="Mô tả">
        {(id) => (
          <textarea
            id={id}
            rows={2}
            className="w-full rounded-lg border border-(--color-border-default) px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent"
            value={form.description}
            onChange={(e) => set({ description: e.target.value })}
          />
        )}
      </Field>
      <FormError message={error} />
    </FormModal>
  );
}
