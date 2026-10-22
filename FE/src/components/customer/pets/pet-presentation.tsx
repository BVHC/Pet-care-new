// Ảnh đại diện thú cưng dùng chung cho lễ tân và khách: ảnh nếu có, không thì biểu tượng theo loài.
import { SPECIES_EMOJI, type Species } from '@/domains/pets/labels';

const SPECIES_BG: Record<Species, string> = {
  DOG: 'bg-amber-100',
  CAT: 'bg-orange-100',
  OTHER: 'bg-emerald-100',
};

export function PetAvatar({ pet, size = 'md' }: { pet: { name: string; species: Species; photoUrl?: string | null }; size?: 'md' | 'lg' }) {
  const box = size === 'lg' ? 'h-16 w-16 text-3xl' : 'h-12 w-12 text-2xl';
  if (pet.photoUrl) {
    return <img src={pet.photoUrl} alt={pet.name} className={`${box} shrink-0 rounded-xl object-cover`} loading="lazy" />;
  }
  return (
    <span aria-hidden className={`${box} ${SPECIES_BG[pet.species]} flex shrink-0 items-center justify-center rounded-xl`}>
      {SPECIES_EMOJI[pet.species]}
    </span>
  );
}
