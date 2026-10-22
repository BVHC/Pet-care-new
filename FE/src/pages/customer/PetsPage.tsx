/**
 * Thú cưng của tôi (UC24, A02) và hồ sơ sức khỏe (UC25) — customer-v1 #13–21, visit-v1 #21.
 * Route nằm sau RequireLinkDecision: hồ sơ còn chờ quyết định liên kết không vào được trang này (BR-TK-19).
 */
import { useState } from 'react';
import { PawPrint, Plus } from 'lucide-react';
import { AccountSidebar } from '@/components/customer/AccountSidebar';
import { PetCard } from '@/components/customer/pets/PetCard';
import { PetDetailDrawer } from '@/components/customer/pets/PetDetailDrawer';
import { PetFormDialog } from '@/components/customer/pets/PetFormDialog';
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useMyPets } from '@/domains/pets/hooks/useCustomerPet';

export default function PetsPage() {
  const pets = useMyPets();
  const [openPetId, setOpenPetId] = useState<number | null>(null);
  const [adding, setAdding] = useState(false);
  const list = pets.data ?? [];

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero
        eyebrow="Hồ sơ thú cưng"
        title="Thú cưng của tôi"
        subtitle="Thông tin, cân nặng và hồ sơ sức khỏe của từng bé."
        breadcrumbs={[{ label: 'Thú cưng' }]}
      />
      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className="grid gap-8 lg:grid-cols-[260px_1fr] xl:grid-cols-[280px_1fr]">
          <AccountSidebar active="pets" />
          <div className="min-w-0 space-y-4">
            {pets.isError && (
              <p role="alert" className="rounded-xl bg-red-50 px-4 py-3 text-sm text-red-700">
                {plainMessage(toApiError(pets.error))}
              </p>
            )}
            {pets.isPending && <p className="text-sm text-(--color-text-secondary)">Đang tải thú cưng…</p>}
            {pets.isSuccess && list.length === 0 && (
              <div className="flex flex-col items-center rounded-2xl border border-dashed border-(--color-border-default) bg-white py-16 text-center">
                <PawPrint className="mb-3 h-10 w-10 text-(--color-text-secondary) opacity-40" aria-hidden />
                <p className="font-bold">Chưa có thú cưng nào</p>
                <p className="mb-5 text-sm text-(--color-text-secondary)">Thêm thú cưng để đặt lịch và theo dõi sức khỏe.</p>
                <button
                  type="button"
                  onClick={() => setAdding(true)}
                  className="inline-flex items-center gap-2 rounded-xl bg-accent px-5 py-2.5 text-sm font-bold text-white"
                >
                  <Plus className="h-4 w-4" aria-hidden /> Thêm thú cưng đầu tiên
                </button>
              </div>
            )}
            {list.length > 0 && (
              <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4" aria-label="Thú cưng của tôi">
                {list.map((pet) => (
                  <li key={pet.petId}>
                    <PetCard pet={pet} selected={openPetId === pet.petId} onClick={() => setOpenPetId(pet.petId)} />
                  </li>
                ))}
                <li>
                  <button
                    type="button"
                    onClick={() => setAdding(true)}
                    className="flex h-full min-h-[150px] w-full flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed border-(--color-border-default) bg-white text-sm font-bold text-(--color-text-secondary) hover:border-accent hover:text-accent"
                  >
                    <Plus className="h-6 w-6" aria-hidden /> Thêm thú cưng
                  </button>
                </li>
              </ul>
            )}
          </div>
        </div>
      </div>

      <PetDetailDrawer petId={openPetId} onClose={() => setOpenPetId(null)} />
      <PetFormDialog open={adding} onOpenChange={setAdding} mode="create" onSaved={(pet) => setOpenPetId(pet.petId)} />
    </div>
  );
}
