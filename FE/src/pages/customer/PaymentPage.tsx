import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Building2,
  Edit2,
  Plus,
  QrCode,
  Trash2,
  Wallet,
} from 'lucide-react'
import { toast } from 'sonner'
import { AccountSidebar } from '@/components/customer/AccountSidebar'
import styles from './PaymentPage.module.css'

/* ================================================================
   Types.
   ================================================================ */

interface SavedCard {
  id: number
  brand: string
  last4: string
  expiry: string
  isDefault: boolean
}

const MOCK_CARDS: SavedCard[] = [
  { id: 1, brand: 'VISA', last4: '4242', expiry: '12/27', isDefault: true },
  { id: 2, brand: 'MASTER', last4: '8888', expiry: '03/26', isDefault: false },
]

/* ================================================================
   Main.
   ================================================================ */

export function PaymentPage() {
  const [cards, setCards] = useState<SavedCard[]>(MOCK_CARDS)
  const [selectedCard, setSelectedCard] = useState<number>(MOCK_CARDS[0].id)
  const [selectedMethod, setSelectedMethod] = useState<'card' | 'cod' | 'transfer'>('card')
  const [showAddForm, setShowAddForm] = useState(false)
  const [form, setForm] = useState({ number: '', name: '', expiry: '', cvv: '' })

  const set = (patch: Partial<typeof form>) =>
    setForm((f) => ({ ...f, ...patch }))

  const handleSaveCard = (e: React.FormEvent) => {
    e.preventDefault()
    if (!form.number || !form.name || !form.expiry || !form.cvv) {
      toast.error('Vui lòng điền đầy đủ thông tin.')
      return
    }
    toast.success('Thẻ đã được thêm thành công!')
    setForm({ number: '', name: '', expiry: '', cvv: '' })
    setShowAddForm(false)
  }

  const handleDeleteCard = (id: number) => {
    setCards((prev) => prev.filter((c) => c.id !== id))
    toast.success('Đã xóa thẻ.')
  }

  return (
    <div className="bg-(--color-surface-page) pb-24">
      {/* hero slab */}
      <section className={styles.slab}>
        <img src="/imgs/hero-dog-clean.png" alt="" className={styles.slabBg} aria-hidden loading="eager" />
        <div className={styles.slabOverlay} aria-hidden />
        <div className={styles.slabNoise} aria-hidden />

        <div className={`${styles.slabContent} mx-auto max-w-[1280px] px-5 sm:px-8`}>
          <nav aria-label="Đường dẫn" className="mb-5 text-[12.5px] text-white/50">
            <Link to="/" className="hover:text-white hover:underline underline-offset-4 transition-colors">
              Trang chủ
            </Link>
            <span className="mx-1.5">/</span>
            <span className="font-semibold text-white/85">Thanh toán</span>
          </nav>
          <h1 className="font-friendly font-extrabold text-[clamp(22px,3vw,36px)] leading-[1.05] text-white">
            Phương thức thanh toán
          </h1>
        </div>
      </section>

      {/* content */}
      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className={styles.page}>
          <AccountSidebar active="payment" />

          <div className={styles.main}>
            {/* Saved cards */}
            <section className={styles.section}>
              <div className={styles.sectionHeader}>
                <h2 className={styles.sectionTitle}>Thẻ đã lưu</h2>
              </div>
              <div className={styles.sectionBody}>
                {cards.map((card) => (
                  <div
                    key={card.id}
                    className={`${styles.cardItem} ${selectedCard === card.id ? styles.cardItemActive : ''}`}
                    onClick={() => setSelectedCard(card.id)}
                  >
                    <div className={styles.cardLogo}>
                      {card.brand}
                    </div>
                    <div className={styles.cardInfo}>
                      <div className={styles.cardNumber}>
                        •••• •••• •••• {card.last4}
                      </div>
                      <div className={styles.cardExpiry}>
                        Hết hạn {card.expiry}
                      </div>
                    </div>
                    {card.isDefault && (
                      <span className={styles.cardDefault}>Mặc định</span>
                    )}
                    <div
                      className={`${styles.cardRadio} ${selectedCard === card.id ? styles.cardRadioActive : ''}`}
                      onClick={(e) => { e.stopPropagation(); setSelectedCard(card.id) }}
                    >
                      {selectedCard === card.id && (
                        <div className={styles.cardRadioDot} />
                      )}
                    </div>
                    <div className={styles.cardActions}>
                      <button
                        type="button"
                        className={styles.cardActionBtn}
                        aria-label="Sửa thẻ"
                        onClick={(e) => e.stopPropagation()}
                      >
                        <Edit2 size={13} />
                      </button>
                      <button
                        type="button"
                        className={styles.cardActionBtn}
                        aria-label="Xóa thẻ"
                        onClick={(e) => { e.stopPropagation(); handleDeleteCard(card.id) }}
                      >
                        <Trash2 size={13} />
                      </button>
                    </div>
                  </div>
                ))}

                {!showAddForm && (
                  <button
                    type="button"
                    onClick={() => setShowAddForm(true)}
                    className={styles.addCardBtn}
                  >
                    <Plus size={16} />
                    Thêm thẻ mới
                  </button>
                )}

                {showAddForm && (
                  <form onSubmit={handleSaveCard} style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                    <div className={styles.formGrid}>
                      <div className={`${styles.field} ${styles.fieldFull}`}>
                        <label className={styles.fieldLabel}>Số thẻ</label>
                        <input
                          type="text"
                          value={form.number}
                          onChange={(e) => set({ number: e.target.value })}
                          placeholder="0000 0000 0000 0000"
                          className={styles.fieldInput}
                          maxLength={19}
                        />
                      </div>
                      <div className={`${styles.field} ${styles.fieldFull}`}>
                        <label className={styles.fieldLabel}>Tên chủ thẻ</label>
                        <input
                          type="text"
                          value={form.name}
                          onChange={(e) => set({ name: e.target.value })}
                          placeholder="NGUYEN VAN A"
                          className={styles.fieldInput}
                        />
                      </div>
                      <div className={styles.field}>
                        <label className={styles.fieldLabel}>Ngày hết hạn</label>
                        <input
                          type="text"
                          value={form.expiry}
                          onChange={(e) => set({ expiry: e.target.value })}
                          placeholder="MM/YY"
                          className={styles.fieldInput}
                          maxLength={5}
                        />
                      </div>
                      <div className={styles.field}>
                        <label className={styles.fieldLabel}>CVV</label>
                        <input
                          type="password"
                          value={form.cvv}
                          onChange={(e) => set({ cvv: e.target.value })}
                          placeholder="•••"
                          className={styles.fieldInput}
                          maxLength={3}
                        />
                      </div>
                    </div>
                    <div className={styles.formActions}>
                      <button
                        type="button"
                        onClick={() => { setShowAddForm(false); setForm({ number: '', name: '', expiry: '', cvv: '' }) }}
                        className={styles.btnCancel}
                      >
                        Hủy
                      </button>
                      <button type="submit" className={styles.btnSave}>
                        Lưu thẻ
                      </button>
                    </div>
                  </form>
                )}
              </div>
            </section>

            {/* Other methods */}
            <section className={styles.section}>
              <div className={styles.sectionHeader}>
                <h2 className={styles.sectionTitle}>Phương thức khác</h2>
              </div>
              <div className={styles.sectionBody}>
                {[
                  {
                    key: 'cod',
                    icon: Wallet,
                    name: 'Thanh toán khi nhận hàng (COD)',
                    desc: 'Trả tiền mặt khi nhận được đơn hàng',
                  },
                  {
                    key: 'transfer',
                    icon: Building2,
                    name: 'Chuyển khoản ngân hàng',
                    desc: 'Chuyển khoản trực tiếp qua tài khoản ngân hàng',
                  },
                  {
                    key: 'qr',
                    icon: QrCode,
                    name: 'Quét mã QR',
                    desc: 'Thanh toán qua ứng dụng ngân hàng hoặc ví điện tử',
                  },
                ].map((m) => (
                  <div
                    key={m.key}
                    className={`${styles.methodItem} ${selectedMethod === m.key ? styles.methodItemActive : ''}`}
                    onClick={() => setSelectedMethod(m.key as typeof selectedMethod)}
                  >
                    <div className={styles.methodIcon}>
                      <m.icon size={20} />
                    </div>
                    <div>
                      <div className={styles.methodName}>{m.name}</div>
                      <div className={styles.methodDesc}>{m.desc}</div>
                    </div>
                    <div
                      className={`${styles.cardRadio} ${selectedMethod === m.key ? styles.cardRadioActive : ''}`}
                      style={{ marginLeft: 'auto' }}
                    >
                      {selectedMethod === m.key && (
                        <div className={styles.cardRadioDot} />
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </section>
          </div>
        </div>
      </div>
    </div>
  )
}
