import { useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Clock, Check, Dog } from 'lucide-react';
import { cn } from '../../lib/utils';

interface GroomingTask {
  id: string; petName: string; petType: string; customerName: string;
  customerPhone: string; service: string; startTime: string;
  estimatedDuration: number; groomer: string; status: 'WAITING' | 'IN_PROGRESS' | 'COMPLETED';
  checklist: { item: string; done: boolean }[];
}

const MOCK_TASKS: GroomingTask[] = [
  { id: '1', petName: 'Chó Poodle', petType: 'Chó', customerName: 'Lê Văn C', customerPhone: '0923456789', service: 'Cắt tỉa full', startTime: '09:00', estimatedDuration: 90, groomer: 'Nguyễn G1', status: 'IN_PROGRESS', checklist: [{ item: 'Tắm', done: true }, { item: 'Sấy', done: true }, { item: 'Cắt lông', done: false }, { item: 'Nhuộm màu', done: false }] },
  { id: '2', petName: 'Mèo Persian', petType: 'Mèo', customerName: 'Phạm Thị D', customerPhone: '0934567890', service: 'Cắt móng', startTime: '10:30', estimatedDuration: 30, groomer: 'Trần G2', status: 'WAITING', checklist: [{ item: 'Cắt móng', done: false }, { item: 'Chải lông', done: false }] },
  { id: '3', petName: 'Chó Golden', petType: 'Chó', customerName: 'Hoàng Văn E', customerPhone: '0945678901', service: 'Tắm & Cắt lông', startTime: '08:00', estimatedDuration: 60, groomer: 'Nguyễn G1', status: 'COMPLETED', checklist: [{ item: 'Tắm', done: true }, { item: 'Sấy', done: true }, { item: 'Cắt lông', done: true }] },
];

export function AdminGroomingPage() {
  const [tasks, setTasks] = useState(MOCK_TASKS);

  const toggleChecklist = (taskId: string, itemIndex: number) => {
    setTasks(prev => prev.map(t => {
      if (t.id === taskId) {
        const newChecklist = [...t.checklist];
        newChecklist[itemIndex] = { ...newChecklist[itemIndex], done: !newChecklist[itemIndex].done };
        return { ...t, checklist: newChecklist };
      }
      return t;
    }));
  };

  const startTask = (id: string) => {
    setTasks(prev => prev.map(t => t.id === id ? { ...t, status: 'IN_PROGRESS' as const, startTime: new Date().toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }) } : t));
  };

  const completeTask = (id: string) => {
    setTasks(prev => prev.map(t => t.id === id ? { ...t, status: 'COMPLETED' as const } : t));
  };

  const waitingCount = tasks.filter(t => t.status === 'WAITING').length;
  const inProgressCount = tasks.filter(t => t.status === 'IN_PROGRESS').length;
  const completedCount = tasks.filter(t => t.status === 'COMPLETED').length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Bảng Grooming</h1>
        <p className="text-gray-500">Quản lý công việc grooming</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-3 gap-4">
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-gray-100"><Clock className="h-6 w-6 text-gray-600" /></div><div><p className="text-2xl font-bold">{waitingCount}</p><p className="text-sm text-gray-500">Đang chờ</p></div></CardContent></Card>
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-blue-100"><Clock className="h-6 w-6 text-blue-600" /></div><div><p className="text-2xl font-bold">{inProgressCount}</p><p className="text-sm text-gray-500">Đang làm</p></div></CardContent></Card>
        <Card><CardContent className="p-4 flex items-center gap-4"><div className="p-3 rounded-full bg-green-100"><Check className="h-6 w-6 text-green-600" /></div><div><p className="text-2xl font-bold">{completedCount}</p><p className="text-sm text-gray-500">Hoàn tất</p></div></CardContent></Card>
      </div>

      {/* Task Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {tasks.filter(t => t.status !== 'COMPLETED').map(task => (
          <Card key={task.id} className={cn('transition-all', task.status === 'IN_PROGRESS' && 'border-blue-500')}>
            <CardContent className="p-4">
              <div className="flex items-center justify-between mb-3">
                <div className="flex items-center gap-2">
                  <Dog className="h-5 w-5 text-gray-400" />
                  <span className="font-medium">{task.petName}</span>
                  <span className="text-xs text-gray-500">({task.petType})</span>
                </div>
                <Badge className={task.status === 'IN_PROGRESS' ? 'bg-blue-100 text-blue-700' : 'bg-gray-100 text-gray-700'}>
                  {task.status === 'IN_PROGRESS' ? 'Đang làm' : 'Đang chờ'}
                </Badge>
              </div>
              <p className="text-blue-600 font-medium mb-2">{task.service}</p>
              <div className="flex items-center gap-4 text-sm text-gray-500 mb-3">
                <span>{task.customerName}</span>
                <span className="flex items-center gap-1"><Clock className="h-3 w-3" />{task.startTime}</span>
              </div>
              <div className="mb-3">
                <p className="text-sm font-medium mb-2">Checklist:</p>
                <div className="space-y-1">
                  {task.checklist.map((item, idx) => (
                    <label key={idx} className="flex items-center gap-2 cursor-pointer">
                      <input type="checkbox" checked={item.done} onChange={() => toggleChecklist(task.id, idx)} className="rounded" />
                      <span className={cn('text-sm', item.done && 'line-through text-gray-400')}>{item.item}</span>
                    </label>
                  ))}
                </div>
              </div>
              <div className="text-xs text-gray-500 mb-3">
                <p>Người thực hiện: {task.groomer}</p>
                <p>Ước tính: {task.estimatedDuration} phút</p>
              </div>
              <div className="flex gap-2">
                {task.status === 'WAITING' && <Button size="sm" className="flex-1 bg-blue-600 hover:bg-blue-700" onClick={() => startTask(task.id)}>Bắt đầu</Button>}
                {task.status === 'IN_PROGRESS' && <Button size="sm" className="flex-1 bg-green-600 hover:bg-green-700" onClick={() => completeTask(task.id)}><Check className="mr-1 h-3 w-3" /> Hoàn tất</Button>}
              </div>
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  );
}
