// ===========================================
// AddressBook Component - T13: Sổ địa chỉ
// Theo BR-TK-18: tối đa 5 địa chỉ, 1 mặc định
// ===========================================
import { useState, useEffect } from 'react';
import { Plus, Edit2, Trash2, MapPin, Check } from 'lucide-react';
import { toast } from 'sonner';
import {
  getAddresses,
  createAddress,
  updateAddress,
  deleteAddress,
  setDefaultAddress,
} from '@/shared/api/profile.api';
import type { Address, CreateAddressRequest } from '@/shared/models/profile.model';
import styles from './AddressBook.module.css';

const MAX_ADDRESSES = 5;

interface AddressBookProps {
  onAddressChange?: () => void;
}

export function AddressBook({ onAddressChange }: AddressBookProps) {
  const [addresses, setAddresses] = useState<Address[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [form, setForm] = useState<CreateAddressRequest>({
    label: '',
    recipientName: '',
    phone: '',
    province: '',
    district: '',
    ward: '',
    street: '',
    isDefault: false,
  });
  const [saving, setSaving] = useState(false);
  const [deletingId, setDeletingId] = useState<number | null>(null);

  // Load addresses
  useEffect(() => {
    loadAddresses();
  }, []);

  async function loadAddresses() {
    try {
      setLoading(true);
      const data = await getAddresses();
      setAddresses(data);
    } catch {
      toast.error('Không tải được danh sách địa chỉ');
    } finally {
      setLoading(false);
    }
  }

  function openAddForm() {
    if (addresses.length >= MAX_ADDRESSES) {
      toast.error(`Tối đa ${MAX_ADDRESSES} địa chỉ`);
      return;
    }
    setForm({
      label: '',
      recipientName: '',
      phone: '',
      province: '',
      district: '',
      ward: '',
      street: '',
      isDefault: addresses.length === 0,
    });
    setEditingId(null);
    setShowForm(true);
  }

  function openEditForm(addr: Address) {
    setForm({
      label: addr.label ?? '',
      recipientName: addr.recipientName,
      phone: addr.phone,
      province: addr.province,
      district: addr.district,
      ward: addr.ward,
      street: addr.street,
      isDefault: addr.isDefault,
    });
    setEditingId(addr.id);
    setShowForm(true);
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!form.recipientName.trim()) {
      toast.error('Vui lòng nhập tên người nhận');
      return;
    }
    if (!form.phone.trim()) {
      toast.error('Vui lòng nhập số điện thoại');
      return;
    }
    if (!form.province.trim() || !form.district.trim() || !form.ward.trim()) {
      toast.error('Vui lòng nhập đầy đủ địa chỉ');
      return;
    }

    try {
      setSaving(true);
      if (editingId) {
        await updateAddress(editingId, form);
        toast.success('Cập nhật địa chỉ thành công');
      } else {
        await createAddress(form);
        toast.success('Thêm địa chỉ thành công');
      }
      setShowForm(false);
      await loadAddresses();
      onAddressChange?.();
    } catch (err) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      toast.error(msg ?? 'Lưu địa chỉ thất bại');
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete(id: number, isDefault: boolean) {
    if (isDefault && addresses.length > 1) {
      toast.error('Không thể xóa địa chỉ mặc định. Vui lòng đặt địa chỉ khác làm mặc định trước.');
      return;
    }
    if (!confirm('Xóa địa chỉ này?')) return;

    try {
      setDeletingId(id);
      await deleteAddress(id);
      toast.success('Xóa địa chỉ thành công');
      await loadAddresses();
      onAddressChange?.();
    } catch {
      toast.error('Xóa địa chỉ thất bại');
    } finally {
      setDeletingId(null);
    }
  }

  async function handleSetDefault(id: number) {
    try {
      await setDefaultAddress(id);
      toast.success('Đặt địa chỉ mặc định thành công');
      await loadAddresses();
      onAddressChange?.();
    } catch {
      toast.error('Đặt mặc định thất bại');
    }
  }

  if (loading) {
    return <div className={styles.loading}>Đang tải...</div>;
  }

  return (
    <div className={styles.container}>
      {/* Header */}
      <div className={styles.header}>
        <h3 className={styles.title}>Sổ địa chỉ</h3>
        <span className={styles.count}>
          {addresses.length}/{MAX_ADDRESSES}
        </span>
      </div>

      {/* List */}
      {addresses.length === 0 && !showForm && (
        <div className={styles.empty}>
          <MapPin size={40} className={styles.emptyIcon} />
          <p>Chưa có địa chỉ nào</p>
          <button type="button" className={styles.addBtn} onClick={openAddForm}>
            <Plus size={16} />
            Thêm địa chỉ
          </button>
        </div>
      )}

      {addresses.length > 0 && !showForm && (
        <>
          <div className={styles.list}>
            {addresses.map((addr) => (
              <div key={addr.id} className={`${styles.card} ${addr.isDefault ? styles.default : ''}`}>
                {addr.isDefault && (
                  <span className={styles.defaultBadge}>
                    <Check size={12} />
                    Mặc định
                  </span>
                )}
                {addr.label && <span className={styles.label}>{addr.label}</span>}
                <p className={styles.name}>{addr.recipientName}</p>
                <p className={styles.phone}>{addr.phone}</p>
                <p className={styles.address}>
                  {addr.street}, {addr.ward}, {addr.district}, {addr.province}
                </p>
                <div className={styles.actions}>
                  <button
                    type="button"
                    className={styles.actionBtn}
                    onClick={() => openEditForm(addr)}
                    title="Sửa"
                  >
                    <Edit2 size={15} />
                  </button>
                  <button
                    type="button"
                    className={styles.actionBtn}
                    onClick={() => handleDelete(addr.id, addr.isDefault)}
                    disabled={deletingId === addr.id}
                    title="Xóa"
                  >
                    <Trash2 size={15} />
                  </button>
                  {!addr.isDefault && (
                    <button
                      type="button"
                      className={styles.defaultBtn}
                      onClick={() => handleSetDefault(addr.id)}
                    >
                      Đặt mặc định
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
          {addresses.length < MAX_ADDRESSES && (
            <button type="button" className={styles.addBtn} onClick={openAddForm}>
              <Plus size={16} />
              Thêm địa chỉ
            </button>
          )}
        </>
      )}

      {/* Form */}
      {showForm && (
        <form onSubmit={handleSubmit} className={styles.form}>
          <h4 className={styles.formTitle}>
            {editingId ? 'Sửa địa chỉ' : 'Thêm địa chỉ mới'}
          </h4>

          <div className={styles.formGrid}>
            <div className={styles.field}>
              <label className={styles.fieldLabel}>Nhãn</label>
              <select
                className={styles.fieldInput}
                value={form.label}
                onChange={(e) => setForm({ ...form, label: e.target.value })}
              >
                <option value="">Chọn nhãn</option>
                <option value="Nhà">Nhà</option>
                <option value="Cơ quan">Cơ quan</option>
                <option value="Khác">Khác</option>
              </select>
            </div>

            <div className={styles.field}>
              <label className={styles.fieldLabel}>Tên người nhận *</label>
              <input
                type="text"
                className={styles.fieldInput}
                value={form.recipientName}
                onChange={(e) => setForm({ ...form, recipientName: e.target.value })}
                placeholder="Họ tên người nhận"
              />
            </div>

            <div className={styles.field}>
              <label className={styles.fieldLabel}>SĐT người nhận *</label>
              <input
                type="tel"
                className={styles.fieldInput}
                value={form.phone}
                onChange={(e) => setForm({ ...form, phone: e.target.value })}
                placeholder="0xxx xxx xxx"
              />
            </div>

            <div className={styles.field}>
              <label className={styles.fieldLabel}>Tỉnh/Thành phố *</label>
              <input
                type="text"
                className={styles.fieldInput}
                value={form.province}
                onChange={(e) => setForm({ ...form, province: e.target.value })}
                placeholder="Ví dụ: Hà Nội"
              />
            </div>

            <div className={styles.field}>
              <label className={styles.fieldLabel}>Quận/Huyện *</label>
              <input
                type="text"
                className={styles.fieldInput}
                value={form.district}
                onChange={(e) => setForm({ ...form, district: e.target.value })}
                placeholder="Ví dụ: Cầu Giấy"
              />
            </div>

            <div className={styles.field}>
              <label className={styles.fieldLabel}>Phường/Xã *</label>
              <input
                type="text"
                className={styles.fieldInput}
                value={form.ward}
                onChange={(e) => setForm({ ...form, ward: e.target.value })}
                placeholder="Ví dụ: Dịch Vọng"
              />
            </div>

            <div className={styles.field} style={{ gridColumn: '1 / -1' }}>
              <label className={styles.fieldLabel}>Số nhà, đường *</label>
              <input
                type="text"
                className={styles.fieldInput}
                value={form.street}
                onChange={(e) => setForm({ ...form, street: e.target.value })}
                placeholder="Ví dụ: 123 Nguyễn Trãi"
              />
            </div>

            <div className={styles.field} style={{ gridColumn: '1 / -1' }}>
              <label className={styles.checkbox}>
                <input
                  type="checkbox"
                  checked={form.isDefault}
                  onChange={(e) => setForm({ ...form, isDefault: e.target.checked })}
                />
                <span>Đặt làm địa chỉ mặc định</span>
              </label>
            </div>
          </div>

          <div className={styles.formActions}>
            <button
              type="button"
              className={styles.cancelBtn}
              onClick={() => setShowForm(false)}
              disabled={saving}
            >
              Hủy
            </button>
            <button type="submit" className={styles.saveBtn} disabled={saving}>
              {saving ? 'Đang lưu...' : editingId ? 'Cập nhật' : 'Thêm mới'}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}
