import { useState } from 'react';
import { Plus, Edit2, Trash2, PawPrint, Calendar, Award } from 'lucide-react';

interface Pet {
  id: number;
  name: string;
  type: 'dog' | 'cat';
  breed: string;
  age: string;
  weight: string;
  image: string;
}

const MOCK_PETS: Pet[] = [
  { id: 1, name: 'Milo', type: 'dog', breed: 'Golden Retriever', age: '3 tuổi', weight: '25kg', image: 'https://images.unsplash.com/photo-1552053831-71594a27632a?w=400' },
  { id: 2, name: 'Luna', type: 'cat', breed: 'Maine Coon', age: '2 tuổi', weight: '6kg', image: 'https://images.unsplash.com/photo-1514888286974-6c03e2ca1dba?w=400' },
];

export function PetsPage() {
  const [pets, setPets] = useState<Pet[]>(MOCK_PETS);
  const [showForm, setShowForm] = useState(false);
  const [editingPet, setEditingPet] = useState<Pet | null>(null);
  const [form, setForm] = useState({ name: '', type: 'dog' as 'dog' | 'cat', breed: '', age: '', weight: '' });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (editingPet) {
      setPets(pets.map(p => p.id === editingPet.id ? { ...editingPet, ...form } : p));
    } else {
      setPets([...pets, { id: Date.now(), ...form, image: 'https://images.unsplash.com/photo-1543466835-00a7907e9de1?w=400' }]);
    }
    setShowForm(false);
    setEditingPet(null);
    setForm({ name: '', type: 'dog', breed: '', age: '', weight: '' });
  };

  const handleEdit = (pet: Pet) => {
    setEditingPet(pet);
    setForm({ name: pet.name, type: pet.type, breed: pet.breed, age: pet.age, weight: pet.weight });
    setShowForm(true);
  };

  const handleDelete = (id: number) => {
    if (confirm('Xóa thú cưng này?')) {
      setPets(pets.filter(p => p.id !== id));
    }
  };

  return (
    <div className="mx-auto max-w-[1000px] px-4 py-8 sm:px-6">
      <div className="flex items-center justify-between mb-8">
        <h1 className="font-[var(--font-friendly)] text-3xl font-bold text-gray-900">
          Hồ sơ thú cưng
        </h1>
        <button
          onClick={() => { setShowForm(true); setEditingPet(null); setForm({ name: '', type: 'dog', breed: '', age: '', weight: '' }); }}
          className="flex items-center gap-2 rounded-full bg-[#843122] px-5 py-2.5 text-sm font-bold text-white hover:bg-[#6a2517] transition-colors"
        >
          <Plus size={18} /> Thêm thú cưng
        </button>
      </div>

      {pets.length === 0 ? (
        <div className="text-center py-20">
          <PawPrint size={48} className="mx-auto mb-4 text-gray-300" />
          <p className="text-gray-500">Chưa có thú cưng nào</p>
          <button
            onClick={() => setShowForm(true)}
            className="mt-4 text-[#843122] font-semibold hover:underline"
          >
            Thêm thú cưng đầu tiên
          </button>
        </div>
      ) : (
        <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
          {pets.map((pet) => (
            <div key={pet.id} className="overflow-hidden rounded-xl border border-gray-200 bg-white shadow-sm hover:shadow-md transition-shadow">
              <div className="aspect-[4/3] bg-gray-100">
                <img src={pet.image} alt={pet.name} className="h-full w-full object-cover" />
              </div>
              <div className="p-5">
                <div className="flex items-center justify-between mb-3">
                  <div>
                    <h3 className="font-[var(--font-friendly)] text-xl font-bold text-gray-900">{pet.name}</h3>
                    <span className={`inline-block rounded-full px-2 py-0.5 text-xs font-bold ${pet.type === 'dog' ? 'bg-blue-100 text-blue-600' : 'bg-purple-100 text-purple-600'}`}>
                      {pet.type === 'dog' ? 'Chó' : 'Mèo'}
                    </span>
                  </div>
                  <div className="flex gap-1">
                    <button onClick={() => handleEdit(pet)} className="p-2 text-gray-400 hover:text-[#843122] transition-colors">
                      <Edit2 size={16} />
                    </button>
                    <button onClick={() => handleDelete(pet.id)} className="p-2 text-gray-400 hover:text-red-500 transition-colors">
                      <Trash2 size={16} />
                    </button>
                  </div>
                </div>
                <div className="space-y-2 text-sm text-gray-600">
                  <div className="flex items-center gap-2">
                    <Award size={14} className="text-gray-400" /> {pet.breed}
                  </div>
                  <div className="flex items-center gap-2">
                    <Calendar size={14} className="text-gray-400" /> {pet.age}
                  </div>
                  <div className="flex items-center gap-2">
                    <PawPrint size={14} className="text-gray-400" /> {pet.weight}
                  </div>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Modal Form */}
      {showForm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="w-full max-w-md rounded-xl bg-white p-6 shadow-xl">
            <h2 className="mb-4 font-[var(--font-friendly)] text-xl font-bold text-gray-900">
              {editingPet ? 'Sửa thông tin' : 'Thêm thú cưng mới'}
            </h2>
            <form onSubmit={handleSubmit} className="space-y-4">
              <div>
                <label className="mb-1 block text-sm font-semibold text-gray-700">Tên thú cưng</label>
                <input
                  type="text"
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  required
                  className="w-full rounded-lg border border-gray-200 px-4 py-2.5 focus:border-[#843122] focus:outline-none"
                  placeholder="VD: Milo, Luna"
                />
              </div>
              <div>
                <label className="mb-1 block text-sm font-semibold text-gray-700">Loại</label>
                <select
                  value={form.type}
                  onChange={(e) => setForm({ ...form, type: e.target.value as 'dog' | 'cat' })}
                  className="w-full rounded-lg border border-gray-200 px-4 py-2.5 focus:border-[#843122] focus:outline-none"
                >
                  <option value="dog">Chó</option>
                  <option value="cat">Mèo</option>
                </select>
              </div>
              <div>
                <label className="mb-1 block text-sm font-semibold text-gray-700">Giống</label>
                <input
                  type="text"
                  value={form.breed}
                  onChange={(e) => setForm({ ...form, breed: e.target.value })}
                  className="w-full rounded-lg border border-gray-200 px-4 py-2.5 focus:border-[#843122] focus:outline-none"
                  placeholder="VD: Golden Retriever"
                />
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="mb-1 block text-sm font-semibold text-gray-700">Tuổi</label>
                  <input
                    type="text"
                    value={form.age}
                    onChange={(e) => setForm({ ...form, age: e.target.value })}
                    className="w-full rounded-lg border border-gray-200 px-4 py-2.5 focus:border-[#843122] focus:outline-none"
                    placeholder="VD: 2 tuổi"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-sm font-semibold text-gray-700">Cân nặng</label>
                  <input
                    type="text"
                    value={form.weight}
                    onChange={(e) => setForm({ ...form, weight: e.target.value })}
                    className="w-full rounded-lg border border-gray-200 px-4 py-2.5 focus:border-[#843122] focus:outline-none"
                    placeholder="VD: 5kg"
                  />
                </div>
              </div>
              <div className="flex gap-3 pt-2">
                <button
                  type="button"
                  onClick={() => { setShowForm(false); setEditingPet(null); }}
                  className="flex-1 rounded-lg border border-gray-200 py-2.5 font-semibold text-gray-700 hover:bg-gray-50 transition-colors"
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  className="flex-1 rounded-lg bg-[#843122] py-2.5 font-semibold text-white hover:bg-[#6a2517] transition-colors"
                >
                  {editingPet ? 'Lưu' : 'Thêm'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
