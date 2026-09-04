/**
 * Pet Care Ecosystem · Shared Interactive Canvas Engine
 * Manages Canvas Pan/Zoom, Coordinates, Mode Switching, HD Export, Dynamic Legend & Routing
 */

// Global State Variables
let currentKey = 'overview';
let currentData = null;
let zoomLevel = 0.75;
let panX = 40;
let panY = 30;
let isPanning = false;
let startMouseX = 0;
let startMouseY = 0;
let interactionMode = 'pan';
let draggingTable = null;
let dragOffsetX = 0;
let dragOffsetY = 0;
let toastTimeout = null;

// Toggle Accordion Group
function toggleGroup(groupId) {
  const el = document.getElementById(groupId);
  if (el) el.classList.toggle('open');
}

// Quick Search & Filter in Sidebar
function filterDiagrams(query) {
  const q = (query || '').toLowerCase().trim();
  const clearBtn = document.getElementById('search-clear-btn');
  if (clearBtn) clearBtn.style.display = q ? 'block' : 'none';

  const items = document.querySelectorAll('.menu-item');
  if (!q) {
    items.forEach(item => item.style.display = '');
    document.querySelectorAll('.sub-nav-group').forEach(sub => {
      sub.style.display = '';
    });
    document.querySelectorAll('.nav-group').forEach(group => {
      group.style.display = '';
    });
    return;
  }

  // Filter menu items
  items.forEach(item => {
    const text = item.textContent.toLowerCase();
    const isMatch = text.includes(q);
    item.style.display = isMatch ? 'flex' : 'none';
  });

  // Handle Sub-Nav Groups
  document.querySelectorAll('.sub-nav-group').forEach(sub => {
    const visibleChildren = Array.from(sub.querySelectorAll('.menu-item')).filter(it => it.style.display !== 'none');
    if (visibleChildren.length > 0) {
      sub.style.display = '';
      sub.classList.add('open');
    } else {
      sub.style.display = 'none';
    }
  });

  // Handle Level 1 Nav Groups
  document.querySelectorAll('.nav-group').forEach(group => {
    const visibleChildren = Array.from(group.querySelectorAll('.menu-item')).filter(it => it.style.display !== 'none');
    if (visibleChildren.length > 0) {
      group.style.display = '';
      group.classList.add('open');
    } else {
      group.style.display = 'none';
    }
  });
}

function clearSearch() {
  const input = document.getElementById('sidebar-search-input');
  if (input) {
    input.value = '';
    filterDiagrams('');
    input.focus();
  }
}

// Render Diagram Dispatcher
function renderDiagram() {
  const container = document.getElementById('canvas-content');
  if (!container) return;
  container.innerHTML = '';

  if (!currentData) return;

  const type = currentData._type;

  // For UC, SQ, FSM diagrams, render directly into container (<g id="canvas-content">)
  // This avoids Chrome collapsing nested <svg width="100%"> inside <g> into 300px x 150px!
  if (type === 'usecase') {
    if (typeof renderUseCase === 'function') renderUseCase(container, currentData);
    return;
  }
  if (type === 'sequence') {
    const stepCount = (currentData.steps || []).length;
    const pCount = (currentData.participants || []).length;
    const reqHeight = Math.max(720, 68 + 40 + 30 + stepCount * 48 + 60);
    const reqWidth = Math.max(1160, 60 + pCount * 190);
    if (typeof renderSequence === 'function') renderSequence(container, currentData, reqWidth, reqHeight);
    return;
  }
  if (type === 'fsm') {
    if (typeof renderFSM === 'function') renderFSM(container, currentData);
    return;
  }

  // Default: renderSchema (DB schema ERD)
  if (typeof renderSchema === 'function') {
    renderSchema(container);
  }
}

function updateTransform() {
  const content = document.getElementById('canvas-content');
  if (content) {
    content.setAttribute('transform', `translate(${panX}, ${panY}) scale(${zoomLevel})`);
  }
  const zoomText = document.getElementById('zoom-text');
  if (zoomText) {
    zoomText.textContent = `${Math.round(zoomLevel * 100)}%`;
  }
}

function getSvgCoordinates(e) {
  const svg = document.getElementById('canvas-svg');
  if (!svg) return { x: 0, y: 0 };
  const rect = svg.getBoundingClientRect();
  const x = (e.clientX - rect.left - panX) / zoomLevel;
  const y = (e.clientY - rect.top - panY) / zoomLevel;
  return { x, y };
}

function setInteractionMode(mode) {
  interactionMode = mode;
  const btnPan = document.getElementById('btn-mode-pan');
  const btnDrag = document.getElementById('btn-mode-drag');
  const viewport = document.getElementById('viewport');

  if (btnPan) btnPan.classList.toggle('active', mode === 'pan');
  if (btnDrag) btnDrag.classList.toggle('active', mode === 'drag');
  if (viewport) viewport.classList.toggle('drag-tables-mode', mode === 'drag');
}

function zoomIn() {
  zoomLevel = Math.min(zoomLevel * 1.2, 3.0);
  updateTransform();
}

function zoomOut() {
  zoomLevel = Math.max(zoomLevel / 1.2, 0.2);
  updateTransform();
}

function resetZoom() {
  const viewport = document.getElementById('viewport');
  const content = document.getElementById('canvas-content');

  if (viewport && content) {
    try {
      const bbox = content.getBBox();
      if (bbox && bbox.width > 40 && bbox.height > 40) {
        const vpRect = viewport.getBoundingClientRect();
        const padX = 70;
        const padY = 70;
        const availW = Math.max(240, vpRect.width - padX * 2);
        const availH = Math.max(240, vpRect.height - padY * 2);

        const scaleX = availW / bbox.width;
        const scaleY = availH / bbox.height;
        let targetZoom = Math.min(scaleX, scaleY);

        // Clamp zoom level comfortably between 0.45 and 1.45
        targetZoom = Math.min(Math.max(targetZoom, 0.45), 1.45);
        zoomLevel = Math.round(targetZoom * 100) / 100;

        // Perfectly center the diagram inside viewport
        panX = Math.round((vpRect.width - bbox.width * zoomLevel) / 2 - bbox.x * zoomLevel);
        panY = Math.round((vpRect.height - bbox.height * zoomLevel) / 2 - bbox.y * zoomLevel);

        updateTransform();
        return;
      }
    } catch (e) {
      console.warn('Smart auto-fit fallback:', e);
    }
  }

  // Fallback defaults if bbox not ready
  if (currentKey === 'overview') {
    zoomLevel = 0.55;
    panX = 30;
    panY = 20;
  } else {
    zoomLevel = 0.85;
    panX = 40;
    panY = 30;
  }
  updateTransform();
}

// loadDiagram automatically finds the diagram across ALL data sets
function loadDiagram(key, eyebrow, title) {
  currentKey = key;

  // Auto detect from available global datasets
  if (typeof SCHEMAS !== 'undefined' && SCHEMAS[key]) {
    currentData = SCHEMAS[key];
    currentData._type = 'schema';
  } else if (typeof UC_DIAGRAMS !== 'undefined' && UC_DIAGRAMS[key]) {
    currentData = UC_DIAGRAMS[key];
    currentData._type = 'usecase';
  } else if (typeof SQ_DIAGRAMS !== 'undefined' && SQ_DIAGRAMS[key]) {
    currentData = SQ_DIAGRAMS[key];
    currentData._type = 'sequence';
  } else if (typeof FSM_DIAGRAMS !== 'undefined' && FSM_DIAGRAMS[key]) {
    currentData = FSM_DIAGRAMS[key];
    currentData._type = 'fsm';
  } else {
    console.warn('Diagram not found for key:', key);
    currentData = null;
  }

  // Update active state in sidebar menu
  document.querySelectorAll('.menu-item').forEach(el => el.classList.remove('active'));
  const activeItem = Array.from(document.querySelectorAll('.menu-item')).find(el => {
    const onclickAttr = el.getAttribute('onclick') || '';
    return onclickAttr.includes("'" + key + "'") || onclickAttr.includes('"' + key + '"');
  });
  if (activeItem) {
    activeItem.classList.add('active');
    // Ensure all ancestor groups (nav-group, sub-nav-group) are open
    let parent = activeItem.parentElement;
    while (parent && parent !== document.body) {
      if (parent.classList && (parent.classList.contains('nav-group') || parent.classList.contains('sub-nav-group'))) {
        parent.classList.add('open');
      }
      parent = parent.parentElement;
    }
  }

  // Update top bar titles
  const topEyebrow = document.getElementById('top-eyebrow');
  if (topEyebrow && eyebrow) topEyebrow.textContent = eyebrow;

  const topTitle = document.getElementById('top-title');
  if (topTitle && title) topTitle.textContent = title;

  const footerDesc = document.getElementById('footer-desc');
  if (footerDesc) {
    footerDesc.innerHTML = `<strong>Domain Note:</strong> ${currentData?.description || ''}`;
  }

  // Update Legend Card based on diagram type
  const legendCard = document.querySelector('.legend-card');
  if (legendCard && currentData) {
    if (currentData._type === 'usecase') {
      legendCard.innerHTML = `
        <div class="legend-item"><span style="display:inline-block;width:12px;height:2px;background:#eb6c36;"></span> Focal Use Case</div>
        <div class="legend-item"><span style="display:inline-block;width:14px;height:0;border-top:1.5px dashed #2e5aa8;"></span> &laquo;include&raquo;</div>
        <div class="legend-item"><span style="display:inline-block;width:14px;height:0;border-top:1.5px dashed #7b4ac7;"></span> &laquo;extend&raquo;</div>
        <div class="legend-item"><span class="chip-sample" style="border-color:#4f5d75;color:#4f5d75">UC#</span> ID Chip</div>
      `;
    } else if (currentData._type === 'sequence') {
      legendCard.innerHTML = `
        <div class="legend-item"><span style="display:inline-block;width:12px;height:2px;background:#2d3142;"></span> Sync Call</div>
        <div class="legend-item"><span style="display:inline-block;width:12px;height:2px;background:#2e5aa8;"></span> Async Event</div>
        <div class="legend-item"><span style="display:inline-block;width:14px;height:0;border-top:1.5px dashed #4f5d75;"></span> Return</div>
      `;
    } else if (currentData._type === 'fsm') {
      legendCard.innerHTML = `
        <div class="legend-item"><span style="display:inline-block;width:12px;height:2px;background:#eb6c36;"></span> Focal State</div>
        <div class="legend-item"><span style="display:inline-block;width:12px;height:2px;background:#2e5aa8;"></span> Initial State</div>
        <div class="legend-item"><span style="display:inline-block;width:12px;height:2px;background:#4f5d75;"></span> Final State</div>
      `;
    } else {
      legendCard.innerHTML = `
        <div class="legend-item"><span class="chip-sample pk">PK</span> Primary Key</div>
        <div class="legend-item"><span class="chip-sample fk">FK</span> Foreign Key</div>
        <div class="legend-item"><span class="chip-sample uq">UQ</span> Unique</div>
        <div class="legend-item"><span style="display:inline-block;width:12px;height:2px;background:#eb6c36;"></span> Focal Cascade</div>
      `;
    }
  }

  // Update URL hash without reload
  if (history.replaceState) {
    history.replaceState(null, null, `#${key}`);
  }

  renderDiagram();
  resetZoom();
}

// Diagram Bounding Box Calculation for HD Export
function getDiagramBounds(padding = 50) {
  if (!currentData) {
    return { minX: 0, minY: 0, width: 1400, height: 900 };
  }
  if (currentData._type === 'usecase') {
    const ucCount = (currentData.useCases || []).length;
    const totalH = Math.max(760, 60 + 65 + ucCount * 68 + 50);
    return {
      minX: 20,
      minY: 20,
      width: 1160,
      height: totalH + 20
    };
  }
  if (currentData._type === 'sequence') {
    const stepCount = (currentData.steps || []).length;
    const pCount = (currentData.participants || []).length;
    const totalH = Math.max(720, 68 + 40 + 30 + stepCount * 48 + 60);
    const totalW = Math.max(1160, 60 + pCount * 190);
    return {
      minX: 20,
      minY: 20,
      width: totalW - 20,
      height: totalH - 20
    };
  }
  if (currentData._type === 'fsm') {
    return {
      minX: 10,
      minY: 10,
      width: 1120,
      height: 640
    };
  }
  if (!currentData.tables || currentData.tables.length === 0) {
    return { minX: 0, minY: 0, width: 1400, height: 900 };
  }
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  currentData.tables.forEach(t => {
    const h = typeof calcTableHeight === 'function' ? calcTableHeight(t) : 100;
    if (t.x < minX) minX = t.x;
    if (t.y < minY) minY = t.y;
    if (t.x + t.w > maxX) maxX = t.x + t.w;
    if (t.y + h > maxY) maxY = t.y + h;
  });

  minX = Math.max(0, minX - padding);
  minY = Math.max(0, minY - padding);
  maxX = maxX + padding + 30;
  maxY = maxY + padding;

  return {
    minX,
    minY,
    width: maxX - minX,
    height: maxY - minY
  };
}

// Build Self-Contained SVG String
function buildStandaloneSvg() {
  const bounds = getDiagramBounds(60);
  const defsEl = document.querySelector('#canvas-svg defs');
  const defs = defsEl ? defsEl.outerHTML : '';
  const content = document.getElementById('canvas-content').innerHTML;
  const title = document.getElementById('top-title')?.textContent || 'Diagram';

  const svgString = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${bounds.minX} ${bounds.minY} ${bounds.width} ${bounds.height}" width="${bounds.width}" height="${bounds.height}">
  <style>
    @import url('https://fonts.googleapis.com/css2?family=Geist:wght@400;500;600;700&amp;family=Geist+Mono:wght@400;500;600&amp;display=swap');
    text { font-family: 'Geist', system-ui, -apple-system, sans-serif; }
    .table-group { cursor: default; }
  </style>
  <rect x="${bounds.minX}" y="${bounds.minY}" width="${bounds.width}" height="${bounds.height}" fill="#f6f6f8" />
  ${defs}
  <g id="export-content">
    ${content}
  </g>
</svg>`;
  return { svgString, bounds, title };
}

// Toast Notification
function showToast(msg) {
  const toast = document.getElementById('toast');
  if (!toast) return;
  toast.textContent = msg;
  toast.classList.add('show');
  if (toastTimeout) clearTimeout(toastTimeout);
  toastTimeout = setTimeout(() => {
    toast.classList.remove('show');
  }, 2500);
}

// Download SVG File
function downloadSvg() {
  const { svgString, title } = buildStandaloneSvg();
  const blob = new Blob([svgString], { type: 'image/svg+xml;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  const safeFilename = (title || 'diagram').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
  a.href = url;
  a.download = `${safeFilename}.svg`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
  showToast('✅ Đã tải xuống file SVG thành công!');
}

// Download PNG File (HD 2x)
function downloadPng() {
  const { svgString, bounds, title } = buildStandaloneSvg();
  const blob = new Blob([svgString], { type: 'image/svg+xml;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const img = new Image();

  showToast('⏳ Đang xuất ảnh PNG (HD 2x)...');

  img.onload = () => {
    const scale = 2; // 2x Retina Resolution
    const canvas = document.createElement('canvas');
    canvas.width = bounds.width * scale;
    canvas.height = bounds.height * scale;
    const ctx = canvas.getContext('2d');

    // Draw solid background
    ctx.fillStyle = '#f6f6f8';
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    ctx.scale(scale, scale);
    ctx.drawImage(img, 0, 0);

    canvas.toBlob((pngBlob) => {
      if (!pngBlob) {
        alert('Không thể tạo file ảnh PNG.');
        return;
      }
      const pngUrl = URL.createObjectURL(pngBlob);
      const a = document.createElement('a');
      const safeFilename = (title || 'diagram').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
      a.href = pngUrl;
      a.download = `${safeFilename}.png`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(pngUrl);
      URL.revokeObjectURL(url);
      showToast('✅ Đã tải xuống ảnh PNG (HD 2x) thành công!');
    }, 'image/png');
  };

  img.onerror = () => {
    URL.revokeObjectURL(url);
    alert('Lỗi tạo ảnh PNG. Bạn có thể bấm Tải SVG thay thế.');
  };

  img.src = url;
}

// Initialize Canvas Interactions (Mouse Drag Pan, Wheel Zoom)
function initCanvasInteractions() {
  const viewport = document.getElementById('viewport');
  if (!viewport) return;

  viewport.addEventListener('mousedown', (e) => {
    if (interactionMode === 'pan') {
      isPanning = true;
      startMouseX = e.clientX - panX;
      startMouseY = e.clientY - panY;
      viewport.classList.add('dragging-canvas');
    }
  });

  window.addEventListener('mousemove', (e) => {
    if (isPanning && interactionMode === 'pan') {
      panX = e.clientX - startMouseX;
      panY = e.clientY - startMouseY;
      updateTransform();
    } else if (draggingTable && interactionMode === 'drag') {
      const pt = getSvgCoordinates(e);
      draggingTable.x = Math.round(pt.x - dragOffsetX);
      draggingTable.y = Math.round(pt.y - dragOffsetY);
      renderDiagram();
    }
  });

  window.addEventListener('mouseup', () => {
    isPanning = false;
    draggingTable = null;
    viewport.classList.remove('dragging-canvas');
  });

  // Wheel Zoom centered on mouse
  viewport.addEventListener('wheel', (e) => {
    e.preventDefault();
    const zoomFactor = e.deltaY < 0 ? 1.1 : 0.9;
    const rect = viewport.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;

    const newZoom = Math.min(Math.max(zoomLevel * zoomFactor, 0.2), 3.5);
    panX = mouseX - (mouseX - panX) * (newZoom / zoomLevel);
    panY = mouseY - (mouseY - panY) * (newZoom / zoomLevel);
    zoomLevel = newZoom;

    updateTransform();
  }, { passive: false });
}

// Global Initialization Helper
function initEngine(defaultKey, defaultEyebrow, defaultTitle) {
  initCanvasInteractions();

  const hash = (location.hash || '').replace('#', '');
  const targetKey = hash || defaultKey;

  const targetItem = Array.from(document.querySelectorAll('.menu-item')).find(el => {
    const attr = el.getAttribute('onclick') || '';
    return attr.includes("'" + targetKey + "'") || attr.includes('"' + targetKey + '"');
  });

  if (targetItem) {
    targetItem.click();
  } else {
    loadDiagram(defaultKey, defaultEyebrow, defaultTitle);
  }
}
