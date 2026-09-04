const fs = require('fs');
const content = fs.readFileSync('./docs/diagrams/uc/data.js', 'utf8');

const modules = [
  { id: 1, name: 'Authentication & OTP', key: 'auth' },
  { id: 2, name: 'Identity & Access Management (IAM)', key: 'iam' },
  { id: 3, name: 'Organization & Store Management', key: 'store' },
  { id: 4, name: 'Customer & Pet Management', key: 'caregiver' },
  { id: 5, name: 'Service & Product Catalog', key: 'catalog' },
  { id: 6, name: 'Appointment & Scheduling', key: 'booking' },
  { id: 7, name: 'Walk-in & Queue Management', key: 'queue' },
  { id: 8, name: 'Workforce Management', key: 'workforce' },
  { id: 9, name: 'Veterinary & Clinical (EMR)', key: 'clinical' },
  { id: 10, name: 'Vaccination Management', key: 'vaccination' },
  { id: 11, name: 'Grooming Management', key: 'grooming' },
  { id: 12, name: 'Inventory & Warehouse', key: 'inventory' },
  { id: 13, name: 'Procurement Management', key: 'procurement' },
  { id: 14, name: 'Order Management (Commerce & POS)', key: 'commerce' },
  { id: 15, name: 'Billing & Invoice Management', key: 'billing' },
  { id: 16, name: 'Payment Management', key: 'payment' },
  { id: 17, name: 'Refund Management', key: 'refund' },
  { id: 18, name: 'Promotion & Voucher', key: 'promotion' },
  { id: 19, name: 'Membership & Loyalty', key: 'membership' },
  { id: 20, name: 'Service Package Management', key: 'package' },
  { id: 21, name: 'Incident Management', key: 'incident' },
  { id: 22, name: 'Consent & Privacy Management', key: 'consent' },
  { id: 23, name: 'Notification Management', key: 'notification' },
  { id: 24, name: 'Reporting & Analytics', key: 'reporting' },
  { id: 25, name: 'Audit Management', key: 'audit' }
];

modules.forEach(m => {
  const has = content.includes(`"${m.key}`) || content.includes(`"${m.key}_`);
  console.log(`${m.id.toString().padStart(2, '0')}. ${m.name}: ${has ? 'FOUND' : 'MISSING'}`);
});
