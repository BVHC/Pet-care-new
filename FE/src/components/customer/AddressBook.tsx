// Sổ địa chỉ của khách (UC06, BR-TK-18): tối đa 5 địa chỉ [CFG], đúng 1 mặc định; không xóa địa chỉ mặc định
// khi còn địa chỉ khác. customer-v1 #3–7.
import { useState, type FormEvent } from 'react';
import { Check, Edit2, MapPin, Plus, Trash2 } from 'lucide-react';
import { ConfirmModal } from '@/components/ui/modal-templates';
import { Checkbox, Field, FormError, NoticeBar, type Notice } from '@/components/admin/form-ui';
import { cn } from '@/lib/utils';
import { fieldErrorsOf, plainMessage, toApiError } from '@/shared/api/api-error';
import { profileApi } from '@/shared/api/profile.api';
import { useAddresses, useProfileMutation } from '@/shared/hooks/useProfile';
import type { Address } from '@/shared/types/profile';
import styles from './AddressBook.module.css';

/** Mặc định của [CFG] `address.max_per_customer`. */
const MAX_ADDRESSES = 5;

const addressText = (a: Address) => [a.addressLine, a.ward, a.province].filter(Boolean).join(', ');

export function AddressBook() {
  const addresses = useAddresses();
  const [editing, setEditing] = useState<Address | 'new' | null>(null);
  const [deleting, setDeleting] = useState<Address | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const setDefault = useProfileMutation((a: Address) => profileApi.setDefaultAddress(a.addressId));
  const remove = useProfileMutation((a: Address) => profileApi.deleteAddress(a.addressId));
  const list = addresses.data ?? [];
  const full = list.length >= MAX_ADDRESSES;

  const run = async (action: () => Promise<unknown>, success: string) => {
    try {
      await action();
      setNotice({ kind: 'success', text: success });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  const confirmDelete = () => {
    const target = deleting!;
    setDeleting(null);
    void run(() => remove.mutateAsync(target), 'Đã xóa địa chỉ');
  };

  return (
    <section className={styles.container}>
      <div className={styles.header}>
        <h2 className={styles.title}>Sổ địa chỉ</h2>
        <span className={styles.count}>
          {list.length}/{MAX_ADDRESSES}
        </span>
      </div>

      <NoticeBar notice={notice} />
      {addresses.isError && <FormError message={plainMessage(toApiError(addresses.error))} />}

      {editing ? (
        <AddressForm
          address={editing === 'new' ? null : editing}
          first={list.length === 0}
          onCancel={() => setEditing(null)}
          onSaved={(text) => {
            setEditing(null);
            setNotice({ kind: 'success', text });
          }}
        />
      ) : (
        <>
          {addresses.isSuccess && list.length === 0 && (
            <div className={styles.empty}>
              <MapPin size={40} className={styles.emptyIcon} />
              <p>Chưa có địa chỉ nào. Địa chỉ đầu tiên sẽ là địa chỉ mặc định.</p>
            </div>
          )}

          <div className={styles.list}>
            {list.map((a) => {
              // BR-TK-18: còn địa chỉ khác thì phải đổi mặc định trước khi xóa.
              const locked = a.isDefault && list.length > 1;
              return (
                <article key={a.addressId} aria-label={a.addressLine} className={cn(styles.card, a.isDefault && styles.default)}>
                  {a.isDefault && (
                    <span className={styles.defaultBadge}>
                      <Check size={12} />
                      Mặc định
                    </span>
                  )}
                  <p className={styles.name}>{a.receiverName}</p>
                  <p className={styles.phone}>{a.receiverPhone}</p>
                  <p className={styles.address}>{addressText(a)}</p>
                  <div className={styles.actions}>
                    <button type="button" className={styles.actionBtn} onClick={() => setEditing(a)} aria-label="Sửa" title="Sửa">
                      <Edit2 size={15} />
                    </button>
                    <button
                      type="button"
                      className={styles.actionBtn}
                      onClick={() => setDeleting(a)}
                      disabled={locked}
                      aria-label="Xóa"
                      title={locked ? 'Đặt địa chỉ khác làm mặc định trước khi xóa' : 'Xóa'}
                    >
                      <Trash2 size={15} />
                    </button>
                    {!a.isDefault && (
                      <button
                        type="button"
                        className={styles.defaultBtn}
                        disabled={setDefault.isPending}
                        onClick={() => void run(() => setDefault.mutateAsync(a), 'Đã đặt địa chỉ mặc định')}
                      >
                        Đặt mặc định
                      </button>
                    )}
                  </div>
                </article>
              );
            })}
          </div>

          <button type="button" className={styles.addBtn} disabled={full || !addresses.isSuccess} onClick={() => setEditing('new')}>
            <Plus size={16} />
            Thêm địa chỉ
          </button>
          {full && <p className={styles.hint}>Sổ địa chỉ đã đủ {MAX_ADDRESSES} địa chỉ. Xóa bớt một địa chỉ để thêm địa chỉ mới.</p>}
        </>
      )}

      <ConfirmModal
        open={!!deleting}
        onOpenChange={(open) => !open && setDeleting(null)}
        type="danger"
        title="Xóa địa chỉ này?"
        description={deleting ? addressText(deleting) : ''}
        confirmText="Xóa"
        onConfirm={confirmDelete}
        loading={remove.isPending}
      />
    </section>
  );
}

function AddressForm({
  address,
  first,
  onCancel,
  onSaved,
}: {
  address: Address | null;
  /** Sổ đang rỗng: địa chỉ thêm vào luôn là mặc định (customer-v1 A6). */
  first: boolean;
  onCancel: () => void;
  onSaved: (notice: string) => void;
}) {
  const title = address ? 'Sửa địa chỉ' : 'Thêm địa chỉ';
  const [form, setForm] = useState({
    receiverName: address?.receiverName ?? '',
    receiverPhone: address?.receiverPhone ?? '',
    addressLine: address?.addressLine ?? '',
    ward: address?.ward ?? '',
    province: address?.province ?? '',
  });
  const [isDefault, setIsDefault] = useState(first);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  const save = useProfileMutation(() =>
    address ? profileApi.updateAddress(address.addressId, form) : profileApi.addAddress({ ...form, isDefault: first || isDefault }),
  );

  const set = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [key]: e.target.value });

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setErrors({});
    setError('');
    try {
      await save.mutateAsync(undefined);
      onSaved(address ? 'Đã cập nhật địa chỉ' : 'Đã thêm địa chỉ');
    } catch (err) {
      const apiError = toApiError(err);
      const fields = fieldErrorsOf(apiError);
      if (Object.keys(fields).length) setErrors(fields);
      else setError(plainMessage(apiError));
    }
  };

  return (
    <form aria-label={title} onSubmit={submit} className={styles.form} noValidate>
      <h3 className={styles.formTitle}>{title}</h3>
      <div className={styles.formGrid}>
        <Field label="Tên người nhận" error={errors.receiverName}>
          {(id) => <input id={id} className={styles.fieldInput} value={form.receiverName} maxLength={100} onChange={set('receiverName')} />}
        </Field>
        <Field label="SĐT người nhận" error={errors.receiverPhone}>
          {(id) => (
            <input id={id} type="tel" className={styles.fieldInput} value={form.receiverPhone} placeholder="0xxxxxxxxx" onChange={set('receiverPhone')} />
          )}
        </Field>
        <div className={styles.wide}>
          <Field label="Địa chỉ" hint="Số nhà, ngõ, đường." error={errors.addressLine}>
            {(id) => <input id={id} className={styles.fieldInput} value={form.addressLine} maxLength={300} onChange={set('addressLine')} />}
          </Field>
        </div>
        <Field label="Phường/xã" hint="Không bắt buộc." error={errors.ward}>
          {(id) => <input id={id} className={styles.fieldInput} value={form.ward} maxLength={100} onChange={set('ward')} />}
        </Field>
        <Field label="Tỉnh/thành phố" error={errors.province}>
          {(id) => <input id={id} className={styles.fieldInput} value={form.province} maxLength={100} onChange={set('province')} />}
        </Field>
        {!address && (
          <div className={styles.wide}>
            <Checkbox
              label={first ? 'Địa chỉ đầu tiên luôn là địa chỉ mặc định' : 'Đặt làm địa chỉ mặc định'}
              checked={first || isDefault}
              disabled={first}
              onChange={setIsDefault}
            />
          </div>
        )}
      </div>
      <FormError message={error} />
      <div className={styles.formActions}>
        <button type="button" className={styles.cancelBtn} onClick={onCancel} disabled={save.isPending}>
          Hủy
        </button>
        <button type="submit" className={styles.saveBtn} disabled={save.isPending}>
          Lưu địa chỉ
        </button>
      </div>
    </form>
  );
}
