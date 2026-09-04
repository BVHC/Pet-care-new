const fs = require('fs');
const content = fs.readFileSync('./docs/diagrams/uc/data.js', 'utf8');
const regex = /^  "([a-zA-Z0-9_-]+)":\s*\{/gm;
let match;
const keys = [];
while ((match = regex.exec(content)) !== null) {
  keys.push(match[1]);
}
console.log('Total diagrams count:', keys.length);
console.log('Keys:', keys);
