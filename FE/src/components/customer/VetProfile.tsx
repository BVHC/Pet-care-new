// ===========================================
// VetProfile Component - T13: Hồ sơ giới thiệu VET
// Theo BR-TK-20: ảnh, chuyên môn, mô tả ngắn (tối đa 500 ký tự)
// Chỉ hiển thị với role VET
// ===========================================
import { useState } from 'react';
import { Camera, Save } from 'lucide-react';
import { toast } from 'sonner';
import { updateStaffProfile } from '@/shared/api/profile.api';
import type { StaffProfile } from '@/shared/models/profile.model';
import styles from './VetProfile.module.css';

const MAX_BIO = 500;

interface VetProfileProps {
  /** Hồ sơ staff hiện tại (null nếu chưa có) */
  staffProfile?: StaffProfile;
  /** Callback khi lưu thành công */
  onSaved?: (data: StaffProfile) => void;
}

export function VetProfile({ staffProfile, onSaved }: VetProfileProps) {
  const [specialty, setSpecialty] = useState(staffProfile?.specialty ?? '');
  const [bio, setBio] = useState(staffProfile?.bio ?? '');
  const [saving, setSaving] = useState(false);
  const [bioLen, setBioLen] = useState((staffProfile?.bio ?? '').length);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (bioLen > MAX_BIO) {
      toast.error(`Mô tả ngắn không được vượt quá ${MAX_BIO} ký tự`);
      return;
    }

    try {
      setSaving(true);
      const updated = await updateStaffProfile({
        specialty: specialty.trim() || undefined,
        bio: bio.trim() || undefined,
      });
      toast.success('Cập nhật hồ sơ giới thiệu thành công');
      onSaved?.(updated);
    } catch (err) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      toast.error(msg ?? 'Lưu thất bại');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className={styles.container}>
      <h3 className={styles.title}>Hồ sơ giới thiệu công khai</h3>
      <p className={styles.hint}>
        Thông tin này hiển thị trên trang &quot;Đội ngũ bác sĩ&quot; dành cho khách hàng.
      </p>

      <form onSubmit={handleSubmit} className={styles.form}>
        {/* Avatar preview */}
        <div className={styles.avatarSection}>
          <div className={styles.avatarWrap}>
            {staffProfile?.avatarUrl ? (
              <img
                src={staffProfile.avatarUrl}
                alt="Avatar"
                className={styles.avatarImg}
              />
            ) : (
              <div className={styles.avatarPlaceholder}>
                <Camera size={28} />
              </div>
            )}
            <button type="button" className={styles.avatarBtn} title="Đổi ảnh (sắp ra mắt)">
              <Camera size={14} />
            </button>
          </div>
          <p className={styles.avatarHint}>JPG, PNG · tối đa 2 MB</p>
        </div>

        {/* Fields */}
        <div className={styles.fields}>
          <div className={styles.field}>
            <label className={styles.label}>Chuyên môn</label>
            <input
              type="text"
              className={styles.input}
              value={specialty}
              onChange={(e) => setSpecialty(e.target.value)}
              placeholder="Ví dụ: Nội khoa, Da liễu thú y"
              maxLength={100}
            />
          </div>

          <div className={styles.field}>
            <label className={styles.label}>
              Mô tả ngắn
              <span className={styles.charCount}>
                {bioLen}/{MAX_BIO}
              </span>
            </label>
            <textarea
              className={styles.textarea}
              value={bio}
              onChange={(e) => {
                setBio(e.target.value);
                setBioLen(e.target.value.length);
              }}
              placeholder="Giới thiệu ngắn về bản thân, kinh nghiệm và thế mạnh chuyên môn..."
              rows={4}
            />
          </div>
        </div>

        <div className={styles.actions}>
          <button type="submit" className={styles.saveBtn} disabled={saving}>
            <Save size={16} />
            {saving ? 'Đang lưu...' : 'Lưu hồ sơ giới thiệu'}
          </button>
        </div>
      </form>
    </div>
  );
}
