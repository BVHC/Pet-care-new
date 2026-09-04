/**
 * Pet Care Ecosystem · High-End Editorial SVG Renderers
 * Supports: Database ERD, Use Case, Sequence, and FSM State Machines
 */

const ROW_HEIGHT = 24;
const HEADER_HEIGHT = 30;

function calcTableHeight(table) {
  return HEADER_HEIGHT + (table.columns.length * ROW_HEIGHT) + 8;
}

// 1. Database Schema ERD Renderer
function renderSchema(container) {
  if (!container) container = document.getElementById('canvas-content');
  if (!container || !currentData) return;
  container.innerHTML = '';

      // 1. Draw Orthogonal Connection Lines
      const linesGroup = document.createElementNS("http://www.w3.org/2000/svg", "g");
      linesGroup.id = "lines-layer";

      currentData.relations.forEach(rel => {
        const fromTable = currentData.tables.find(t => t.id === rel.from);
        const toTable = currentData.tables.find(t => t.id === rel.to);

        if (!fromTable || !toTable) return;

        const fromColIdx = fromTable.columns.findIndex(c => c.n === rel.fromCol);
        const toColIdx = toTable.columns.findIndex(c => c.n === rel.toCol);

        const fromY = fromTable.y + HEADER_HEIGHT + (fromColIdx >= 0 ? fromColIdx * ROW_HEIGHT + 12 : 20);
        const toY = toTable.y + HEADER_HEIGHT + (toColIdx >= 0 ? toColIdx * ROW_HEIGHT + 12 : 20);

        let pathData = '';
        const isAccent = rel.type === 'CASCADE';
        const strokeColor = isAccent ? '#eb6c36' : (rel.type === 'RESTRICT' ? '#4f5d75' : '#2e5aa8');
        const markerUrl = isAccent ? 'url(#arr-acc)' : 'url(#arr)';

        // Clean Orthogonal Manhattan Routing
        if (fromTable.x >= toTable.x + toTable.w) {
          const startX = fromTable.x;
          const endX = toTable.x + toTable.w;
          const midX = startX - (startX - endX) / 2;
          pathData = `M ${startX} ${fromY} H ${midX} V ${toY} H ${endX}`;
        } else if (fromTable.x + fromTable.w <= toTable.x) {
          const startX = fromTable.x + fromTable.w;
          const endX = toTable.x;
          const midX = startX + (endX - startX) / 2;
          pathData = `M ${startX} ${fromY} H ${midX} V ${toY} H ${endX}`;
        } else {
          const startX = fromTable.x + fromTable.w;
          const endX = toTable.x + toTable.w;
          const sideX = Math.max(startX, endX) + 24;
          pathData = `M ${startX} ${fromY} H ${sideX} V ${toY} H ${endX}`;
        }

        const pathEl = document.createElementNS("http://www.w3.org/2000/svg", "path");
        pathEl.setAttribute("d", pathData);
        pathEl.setAttribute("fill", "none");
        pathEl.setAttribute("stroke", strokeColor);
        pathEl.setAttribute("stroke-width", isAccent ? "1.8" : "1.2");
        pathEl.setAttribute("marker-end", markerUrl);
        pathEl.setAttribute("stroke-linecap", "round");
        pathEl.setAttribute("stroke-linejoin", "round");
        if (isAccent) {
          pathEl.setAttribute("stroke-dasharray", "4,3");
        }
        linesGroup.appendChild(pathEl);
      });

      container.appendChild(linesGroup);

      // 2. Draw Tables
      const tablesGroup = document.createElementNS("http://www.w3.org/2000/svg", "g");
      tablesGroup.id = "tables-layer";

      currentData.tables.forEach(table => {
        const totalHeight = calcTableHeight(table);

        const g = document.createElementNS("http://www.w3.org/2000/svg", "g");
        g.setAttribute("class", `table-group ${table.focal ? 'is-focal' : ''}`);
        g.setAttribute("id", `table-${table.id}`);
        g.setAttribute("transform", `translate(${table.x}, ${table.y})`);

        // Table Background
        const bg = document.createElementNS("http://www.w3.org/2000/svg", "rect");
        bg.setAttribute("class", "table-bg");
        bg.setAttribute("width", table.w);
        bg.setAttribute("height", totalHeight);
        bg.setAttribute("rx", "6");
        bg.setAttribute("fill", "#ffffff");
        bg.setAttribute("stroke", table.focal ? "#eb6c36" : "#2d3142");
        bg.setAttribute("stroke-width", table.focal ? "1.8" : "1");
        g.appendChild(bg);

        // Header Background
        const hbg = document.createElementNS("http://www.w3.org/2000/svg", "path");
        hbg.setAttribute("class", "table-header-bg");
        hbg.setAttribute("d", `M 0,6 Q 0,0 6,0 L ${table.w - 6},0 Q ${table.w},0 ${table.w},6 L ${table.w},${HEADER_HEIGHT} L 0,${HEADER_HEIGHT} Z`);
        hbg.setAttribute("fill", table.focal ? "rgba(235,108,54,0.12)" : "rgba(45,49,66,0.05)");
        g.appendChild(hbg);

        // Header divider
        const hline = document.createElementNS("http://www.w3.org/2000/svg", "line");
        hline.setAttribute("x1", "0");
        hline.setAttribute("y1", HEADER_HEIGHT);
        hline.setAttribute("x2", table.w);
        hline.setAttribute("y2", HEADER_HEIGHT);
        hline.setAttribute("stroke", table.focal ? "rgba(235,108,54,0.4)" : "rgba(45,49,66,0.2)");
        hline.setAttribute("stroke-width", "1");
        g.appendChild(hline);

        // Table Title
        const titleText = document.createElementNS("http://www.w3.org/2000/svg", "text");
        titleText.setAttribute("x", "12");
        titleText.setAttribute("y", "20");
        titleText.setAttribute("font-family", "'Geist', sans-serif");
        titleText.setAttribute("font-size", "12");
        titleText.setAttribute("font-weight", "600");
        titleText.setAttribute("fill", "#2d3142");
        titleText.textContent = table.name;
        g.appendChild(titleText);

        // Chip TABLE tag
        const tagBox = document.createElementNS("http://www.w3.org/2000/svg", "rect");
        tagBox.setAttribute("x", table.w - 48);
        tagBox.setAttribute("y", "9");
        tagBox.setAttribute("width", "38");
        tagBox.setAttribute("height", "13");
        tagBox.setAttribute("rx", "2");
        tagBox.setAttribute("fill", "none");
        tagBox.setAttribute("stroke", "rgba(45,49,66,0.35)");
        tagBox.setAttribute("stroke-width", "0.8");
        g.appendChild(tagBox);

        const tagText = document.createElementNS("http://www.w3.org/2000/svg", "text");
        tagText.setAttribute("x", table.w - 29);
        tagText.setAttribute("y", "18");
        tagText.setAttribute("font-family", "'Geist Mono', monospace");
        tagText.setAttribute("font-size", "7.5");
        tagText.setAttribute("fill", "#2d3142");
        tagText.setAttribute("opacity", "0.8");
        tagText.setAttribute("text-anchor", "middle");
        tagText.setAttribute("letter-spacing", "0.06em");
        tagText.textContent = "TABLE";
        g.appendChild(tagText);

        // Column Rows
        table.columns.forEach((col, idx) => {
          const rowY = HEADER_HEIGHT + idx * ROW_HEIGHT;

          // Alternating row stripe
          if (idx % 2 === 0) {
            const stripe = document.createElementNS("http://www.w3.org/2000/svg", "rect");
            stripe.setAttribute("x", "0");
            stripe.setAttribute("y", rowY);
            stripe.setAttribute("width", table.w);
            stripe.setAttribute("height", ROW_HEIGHT);
            stripe.setAttribute("fill", "rgba(45,49,66,0.02)");
            g.appendChild(stripe);
          }

          // Column Name
          const nameText = document.createElementNS("http://www.w3.org/2000/svg", "text");
          nameText.setAttribute("x", "12");
          nameText.setAttribute("y", rowY + 16);
          nameText.setAttribute("font-family", "'Geist', sans-serif");
          nameText.setAttribute("font-size", "11.5");
          nameText.setAttribute("fill", "#2d3142");
          nameText.textContent = col.n;
          g.appendChild(nameText);

          // Constraint Badges
          let chipX = table.w - 85;
          if (col.pk) {
            const pkChip = document.createElementNS("http://www.w3.org/2000/svg", "rect");
            pkChip.setAttribute("x", chipX);
            pkChip.setAttribute("y", rowY + 6);
            pkChip.setAttribute("width", "18");
            pkChip.setAttribute("height", "12");
            pkChip.setAttribute("rx", "2");
            pkChip.setAttribute("fill", "none");
            pkChip.setAttribute("stroke", "rgba(235,108,54,0.7)");
            pkChip.setAttribute("stroke-width", "0.8");
            g.appendChild(pkChip);

            const pkTxt = document.createElementNS("http://www.w3.org/2000/svg", "text");
            pkTxt.setAttribute("x", chipX + 9);
            pkTxt.setAttribute("y", rowY + 15);
            pkTxt.setAttribute("font-family", "'Geist Mono', monospace");
            pkTxt.setAttribute("font-size", "7.5");
            pkTxt.setAttribute("font-weight", "600");
            pkTxt.setAttribute("fill", "#eb6c36");
            pkTxt.setAttribute("text-anchor", "middle");
            pkTxt.textContent = "PK";
            g.appendChild(pkTxt);
          }

          if (col.fk) {
            const fkChip = document.createElementNS("http://www.w3.org/2000/svg", "rect");
            fkChip.setAttribute("x", chipX);
            fkChip.setAttribute("y", rowY + 6);
            fkChip.setAttribute("width", "18");
            fkChip.setAttribute("height", "12");
            fkChip.setAttribute("rx", "2");
            fkChip.setAttribute("fill", "none");
            fkChip.setAttribute("stroke", "rgba(46,90,168,0.7)");
            fkChip.setAttribute("stroke-width", "0.8");
            g.appendChild(fkChip);

            const fkTxt = document.createElementNS("http://www.w3.org/2000/svg", "text");
            fkTxt.setAttribute("x", chipX + 9);
            fkTxt.setAttribute("y", rowY + 15);
            fkTxt.setAttribute("font-family", "'Geist Mono', monospace");
            fkTxt.setAttribute("font-size", "7.5");
            fkTxt.setAttribute("font-weight", "600");
            fkTxt.setAttribute("fill", "#2e5aa8");
            fkTxt.setAttribute("text-anchor", "middle");
            fkTxt.textContent = "FK";
            g.appendChild(fkTxt);
          }

          if (col.uq) {
            const uqChip = document.createElementNS("http://www.w3.org/2000/svg", "rect");
            uqChip.setAttribute("x", chipX - 22);
            uqChip.setAttribute("y", rowY + 6);
            uqChip.setAttribute("width", "18");
            uqChip.setAttribute("height", "12");
            uqChip.setAttribute("rx", "2");
            uqChip.setAttribute("fill", "none");
            uqChip.setAttribute("stroke", "rgba(123,74,199,0.7)");
            uqChip.setAttribute("stroke-width", "0.8");
            g.appendChild(uqChip);

            const uqTxt = document.createElementNS("http://www.w3.org/2000/svg", "text");
            uqTxt.setAttribute("x", chipX - 13);
            uqTxt.setAttribute("y", rowY + 15);
            uqTxt.setAttribute("font-family", "'Geist Mono', monospace");
            uqTxt.setAttribute("font-size", "7.5");
            uqTxt.setAttribute("font-weight", "600");
            uqTxt.setAttribute("fill", "#7b4ac7");
            uqTxt.setAttribute("text-anchor", "middle");
            uqTxt.textContent = "UQ";
            g.appendChild(uqTxt);
          }

          // Column Type
          const typeText = document.createElementNS("http://www.w3.org/2000/svg", "text");
          typeText.setAttribute("x", table.w - 12);
          typeText.setAttribute("y", rowY + 16);
          typeText.setAttribute("font-family", "'Geist Mono', monospace");
          typeText.setAttribute("font-size", "9.5");
          typeText.setAttribute("fill", "#5b6579");
          typeText.setAttribute("text-anchor", "end");
          typeText.textContent = col.t;
          g.appendChild(typeText);
        });

        // Mouse Drag on Table
        g.addEventListener('mousedown', (e) => {
          if (interactionMode === 'drag') {
            e.stopPropagation();
            draggingTable = table;
            const pt = getSvgCoordinates(e);
            dragOffsetX = pt.x - table.x;
            dragOffsetY = pt.y - table.y;
          }
        });

        tablesGroup.appendChild(g);
      });

      container.appendChild(tablesGroup);
    }

// 2. Use Case Diagram Renderer (UML Visual Paradigm Standard: Ellipses, Extension Points, Stick Figures)
function ensureUmlMarkers(svg) {
  let rootSvg = svg;
  while (rootSvg && rootSvg.tagName && rootSvg.tagName.toLowerCase() !== 'svg') {
    rootSvg = rootSvg.parentElement || rootSvg.parentNode;
  }
  if (!rootSvg) return;

  let defs = rootSvg.querySelector('defs');
  if (!defs) {
    defs = document.createElementNS("http://www.w3.org/2000/svg", "defs");
    rootSvg.insertBefore(defs, rootSvg.firstChild);
  }

  // UML Generalization Hollow Triangle Marker
  if (!defs.querySelector('#uml-generalization')) {
    const m = document.createElementNS("http://www.w3.org/2000/svg", "marker");
    m.setAttribute('id', 'uml-generalization');
    m.setAttribute('viewBox', '0 0 14 14');
    m.setAttribute('refX', '13');
    m.setAttribute('refY', '7');
    m.setAttribute('markerWidth', '14');
    m.setAttribute('markerHeight', '14');
    m.setAttribute('orient', 'auto');
    const p = document.createElementNS("http://www.w3.org/2000/svg", "path");
    p.setAttribute('d', 'M 1 1 L 13 7 L 1 13 Z');
    p.setAttribute('fill', '#ffffff');
    p.setAttribute('stroke', '#1a1d2e');
    p.setAttribute('stroke-width', '1.3');
    p.setAttribute('stroke-linejoin', 'miter');
    m.appendChild(p);
    defs.appendChild(m);
  }

  // UML Include Open Arrowhead Marker
  if (!defs.querySelector('#uml-include')) {
    const m = document.createElementNS("http://www.w3.org/2000/svg", "marker");
    m.setAttribute('id', 'uml-include');
    m.setAttribute('viewBox', '0 0 12 12');
    m.setAttribute('refX', '11');
    m.setAttribute('refY', '6');
    m.setAttribute('markerWidth', '12');
    m.setAttribute('markerHeight', '12');
    m.setAttribute('orient', 'auto');
    const p = document.createElementNS("http://www.w3.org/2000/svg", "path");
    p.setAttribute('d', 'M 1 1 L 11 6 L 1 11');
    p.setAttribute('fill', 'none');
    p.setAttribute('stroke', '#2e5aa8');
    p.setAttribute('stroke-width', '1.3');
    m.appendChild(p);
    defs.appendChild(m);
  }

  // UML Extend Open Arrowhead Marker
  if (!defs.querySelector('#uml-extend')) {
    const m = document.createElementNS("http://www.w3.org/2000/svg", "marker");
    m.setAttribute('id', 'uml-extend');
    m.setAttribute('viewBox', '0 0 12 12');
    m.setAttribute('refX', '11');
    m.setAttribute('refY', '6');
    m.setAttribute('markerWidth', '12');
    m.setAttribute('markerHeight', '12');
    m.setAttribute('orient', 'auto');
    const p = document.createElementNS("http://www.w3.org/2000/svg", "path");
    p.setAttribute('d', 'M 1 1 L 11 6 L 1 11');
    p.setAttribute('fill', 'none');
    p.setAttribute('stroke', '#c8501e');
    p.setAttribute('stroke-width', '1.3');
    m.appendChild(p);
    defs.appendChild(m);
  }
}

function renderUseCase(svg, data) {
  ensureUmlMarkers(svg);

  const actors = data.actors || [];
  const useCases = data.useCases || [];
  const relations = data.relations || [];
  const actorGenerals = data.actorGeneralizations || [];

  // Classify actors
  const isSecondary = (name) => ['System', 'Supplier', 'Payment Gateway', 'Bank', 'OTP Service', 'Notification Service'].includes(name);
  const leftActors = actors.filter(a => !isSecondary(a.name));
  const rightActors = actors.filter(a => isSecondary(a.name));

  // Determine geometry & layout
  const topY = 60;
  const headerH = 55;
  const ucGap = 24;
  const ucRowHeight = 68;
  const boundaryPadBottom = 40;
  const boundaryHeight = Math.max(480, headerH + useCases.length * ucRowHeight + boundaryPadBottom);
  const boundaryWidth = 620;
  const boundaryX = 330;
  const boundaryY = topY;

  const actorLeftX = 140;
  const actorRightX = 1080;

  // 1. Position Left Actors
  leftActors.forEach((actor, idx) => {
    let y;
    if (leftActors.length === 1) {
      y = boundaryY + boundaryHeight / 2 - 40;
    } else {
      const start = boundaryY + 50;
      const end = boundaryY + boundaryHeight - 70;
      const step = (end - start) / (leftActors.length - 1);
      y = start + idx * step;
    }
    actor.x = actorLeftX;
    actor.y = y;
    actor.anchorX = actorLeftX + 32;
    actor.anchorY = y + 36;
  });

  // 2. Position Right Actors
  rightActors.forEach((actor, idx) => {
    let y;
    if (rightActors.length === 1) {
      y = boundaryY + boundaryHeight / 2 - 40;
    } else {
      const start = boundaryY + 50;
      const end = boundaryY + boundaryHeight - 70;
      const step = (end - start) / (rightActors.length - 1);
      y = start + idx * step;
    }
    actor.x = actorRightX;
    actor.y = y;
    actor.anchorX = actorRightX - 32;
    actor.anchorY = y + 36;
  });

  // 3. Position Use Cases (Center inside System Boundary)
  const ucCenterX = boundaryX + boundaryWidth / 2;
  useCases.forEach((uc, idx) => {
    const hasExtensionPoints = uc.focal || (uc.extensionPoints && uc.extensionPoints.length > 0);
    const rx = hasExtensionPoints ? Math.max(140, Math.min(185, uc.name.length * 6.6 + 25)) : Math.max(105, Math.min(145, uc.name.length * 5.8 + 18));
    const ry = hasExtensionPoints ? 38 : 25;

    const cy = boundaryY + headerH + idx * ucRowHeight + ucRowHeight / 2;
    uc.x = ucCenterX;
    uc.y = cy;
    uc.rx = rx;
    uc.ry = ry;
    uc.leftAnchor = { x: ucCenterX - rx, y: cy };
    uc.rightAnchor = { x: ucCenterX + rx, y: cy };
    uc.topAnchor = { x: ucCenterX, y: cy - ry };
    uc.bottomAnchor = { x: ucCenterX, y: cy + ry };
  });

  // LAYER 1: System Boundary Box
  const boundaryGroup = document.createElementNS("http://www.w3.org/2000/svg", "g");
  boundaryGroup.setAttribute("class", "system-boundary-group");

  const boundaryRect = document.createElementNS("http://www.w3.org/2000/svg", "rect");
  boundaryRect.setAttribute("x", boundaryX);
  boundaryRect.setAttribute("y", boundaryY);
  boundaryRect.setAttribute("width", boundaryWidth);
  boundaryRect.setAttribute("height", boundaryHeight);
  boundaryRect.setAttribute("rx", "8");
  boundaryRect.setAttribute("fill", "#ffffff");
  boundaryRect.setAttribute("stroke", "#1a1d2e");
  boundaryRect.setAttribute("stroke-width", "1.3");
  boundaryGroup.appendChild(boundaryRect);

  // System tag banner
  const tagRect = document.createElementNS("http://www.w3.org/2000/svg", "rect");
  tagRect.setAttribute("x", boundaryX);
  tagRect.setAttribute("y", boundaryY);
  tagRect.setAttribute("width", boundaryWidth);
  tagRect.setAttribute("height", "38");
  tagRect.setAttribute("rx", "8");
  tagRect.setAttribute("fill", "#f3f6fa");
  boundaryGroup.appendChild(tagRect);

  const headerLine = document.createElementNS("http://www.w3.org/2000/svg", "line");
  headerLine.setAttribute("x1", boundaryX);
  headerLine.setAttribute("y1", boundaryY + 38);
  headerLine.setAttribute("x2", boundaryX + boundaryWidth);
  headerLine.setAttribute("y2", boundaryY + 38);
  headerLine.setAttribute("stroke", "rgba(26,29,46,0.15)");
  headerLine.setAttribute("stroke-width", "1");
  boundaryGroup.appendChild(headerLine);

  const sysTag = document.createElementNS("http://www.w3.org/2000/svg", "text");
  sysTag.setAttribute("x", boundaryX + 16);
  sysTag.setAttribute("y", boundaryY + 24);
  sysTag.setAttribute("font-family", "'Geist Mono', monospace");
  sysTag.setAttribute("font-size", "10.5");
  sysTag.setAttribute("font-weight", "600");
  sysTag.setAttribute("fill", "#2e5aa8");
  sysTag.setAttribute("letter-spacing", "0.06em");
  sysTag.textContent = "«system»";
  boundaryGroup.appendChild(sysTag);

  const titleText = document.createElementNS("http://www.w3.org/2000/svg", "text");
  titleText.setAttribute("x", boundaryX + 85);
  titleText.setAttribute("y", boundaryY + 24);
  titleText.setAttribute("font-family", "'Geist', sans-serif");
  titleText.setAttribute("font-size", "12");
  titleText.setAttribute("font-weight", "700");
  titleText.setAttribute("fill", "#1a1d2e");
  const cleanDesc = (data.description || 'Pet Care Ecosystem').split(':')[0].replace(/^Use Case\s*/i, '');
  titleText.textContent = cleanDesc;
  boundaryGroup.appendChild(titleText);

  svg.appendChild(boundaryGroup);

  // LAYER 2: Relations (Lines, Include/Extend, Generalization)
  const relGroup = document.createElementNS("http://www.w3.org/2000/svg", "g");
  relGroup.setAttribute("class", "relations-layer");

  const allActors = [...leftActors, ...rightActors];

  // Draw Actor-to-Actor Generalizations
  actorGenerals.forEach(ag => {
    const fromAct = allActors.find(a => a.name === ag.from);
    const toAct = allActors.find(a => a.name === ag.to);
    if (!fromAct || !toAct) return;

    const path = document.createElementNS("http://www.w3.org/2000/svg", "line");
    path.setAttribute("x1", fromAct.x);
    path.setAttribute("y1", fromAct.y - 12);
    path.setAttribute("x2", toAct.x);
    path.setAttribute("y2", toAct.y + 70);
    path.setAttribute("stroke", "#1a1d2e");
    path.setAttribute("stroke-width", "1.2");
    path.setAttribute("marker-end", "url(#uml-generalization)");
    relGroup.appendChild(path);
  });

  // Draw Relations
  relations.forEach((rel) => {
    // Check if it's a UC-to-UC relation or Actor-to-UC relation
    if (rel.fromUc && rel.toUc) {
      // UC to UC (include / extend)
      const u1 = useCases.find(u => u.id === rel.fromUc);
      const u2 = useCases.find(u => u.id === rel.toUc);
      if (!u1 || !u2) return;

      const isInclude = rel.type === 'include';
      const isExtend = rel.type === 'extend';

      const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
      line.setAttribute("x1", u1.bottomAnchor ? u1.bottomAnchor.x : u1.x);
      line.setAttribute("y1", u1.bottomAnchor ? u1.bottomAnchor.y : u1.y);
      line.setAttribute("x2", u2.topAnchor ? u2.topAnchor.x : u2.x);
      line.setAttribute("y2", u2.topAnchor ? u2.topAnchor.y : u2.y);
      line.setAttribute("stroke", isInclude ? "#2e5aa8" : "#c8501e");
      line.setAttribute("stroke-width", "1.3");
      line.setAttribute("stroke-dasharray", "6,4");
      line.setAttribute("marker-end", isInclude ? "url(#uml-include)" : "url(#uml-extend)");
      relGroup.appendChild(line);

      // Stereotype text
      const midX = (u1.x + u2.x) / 2 + 14;
      const midY = (u1.y + u2.y) / 2;
      const txt = document.createElementNS("http://www.w3.org/2000/svg", "text");
      txt.setAttribute("x", midX);
      txt.setAttribute("y", midY);
      txt.setAttribute("font-family", "'Geist Mono', monospace");
      txt.setAttribute("font-size", "8.5");
      txt.setAttribute("font-weight", "600");
      txt.setAttribute("fill", isInclude ? "#2e5aa8" : "#c8501e");
      txt.textContent = isInclude ? "«include»" : "«extend»";
      relGroup.appendChild(txt);
      return;
    }

    // Actor to UC relation
    const actorObj = allActors.find(a => a.name === rel.actor);
    const ucObj = useCases.find(u => u.id === rel.uc);
    if (!actorObj || !ucObj) return;

    const isLeft = leftActors.includes(actorObj);
    const startX = actorObj.anchorX;
    const startY = actorObj.anchorY;
    const endX = isLeft ? ucObj.leftAnchor.x : ucObj.rightAnchor.x;
    const endY = isLeft ? ucObj.leftAnchor.y : ucObj.rightAnchor.y;

    const dx = Math.abs(endX - startX);
    const c1x = isLeft ? startX + dx * 0.42 : startX - dx * 0.42;
    const c1y = startY;
    const c2x = isLeft ? endX - dx * 0.42 : endX + dx * 0.42;
    const c2y = endY;

    const pathData = `M ${startX} ${startY} C ${c1x} ${c1y}, ${c2x} ${c2y}, ${endX} ${endY}`;
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    path.setAttribute("d", pathData);
    path.setAttribute("fill", "none");
    path.setAttribute("class", `uc-line actor-rel-${actorObj.name.replace(/\\s+/g, '-')} uc-rel-${ucObj.id}`);

    const isInclude = rel.type === "include";
    const isExtend = rel.type === "extend";

    if (isInclude) {
      path.setAttribute("stroke", "#2e5aa8");
      path.setAttribute("stroke-width", "1.3");
      path.setAttribute("stroke-dasharray", "6,4");
      path.setAttribute("marker-end", "url(#uml-include)");
    } else if (isExtend) {
      path.setAttribute("stroke", "#c8501e");
      path.setAttribute("stroke-width", "1.3");
      path.setAttribute("stroke-dasharray", "6,4");
      path.setAttribute("marker-end", "url(#uml-extend)");
    } else {
      path.setAttribute("stroke", "#1a1d2e");
      path.setAttribute("stroke-width", "1.2");
      path.setAttribute("stroke-opacity", "0.75");
    }
    relGroup.appendChild(path);

    // Add stereotype pill badge at curve midpoint (t=0.5)
    if (isInclude || isExtend) {
      const midX = 0.125 * startX + 0.375 * c1x + 0.375 * c2x + 0.125 * endX;
      const midY = 0.125 * startY + 0.375 * c1y + 0.375 * c2y + 0.125 * endY;

      const pillBg = document.createElementNS("http://www.w3.org/2000/svg", "rect");
      pillBg.setAttribute("x", midX - 25);
      pillBg.setAttribute("y", midY - 9);
      pillBg.setAttribute("width", "50");
      pillBg.setAttribute("height", "16");
      pillBg.setAttribute("rx", "3");
      pillBg.setAttribute("fill", "#ffffff");
      pillBg.setAttribute("stroke", isInclude ? "#2e5aa8" : "#c8501e");
      pillBg.setAttribute("stroke-width", "0.8");
      relGroup.appendChild(pillBg);

      const pillTxt = document.createElementNS("http://www.w3.org/2000/svg", "text");
      pillTxt.setAttribute("x", midX);
      pillTxt.setAttribute("y", midY + 3);
      pillTxt.setAttribute("text-anchor", "middle");
      pillTxt.setAttribute("font-family", "'Geist Mono', monospace");
      pillTxt.setAttribute("font-size", "8");
      pillTxt.setAttribute("font-weight", "600");
      pillTxt.setAttribute("fill", isInclude ? "#2e5aa8" : "#c8501e");
      pillTxt.textContent = `«${rel.type}»`;
      relGroup.appendChild(pillTxt);
    }
  });

  svg.appendChild(relGroup);

  // LAYER 3: UML Ellipse Use Cases
  const ucLayer = document.createElementNS("http://www.w3.org/2000/svg", "g");
  ucLayer.setAttribute("class", "usecases-layer");

  useCases.forEach((uc) => {
    const g = document.createElementNS("http://www.w3.org/2000/svg", "g");
    g.setAttribute("class", `usecase-pill ${uc.focal ? 'is-focal' : ''}`);
    g.setAttribute("id", `uc-node-${uc.id}`);

    const hasExtensionPoints = uc.focal || (uc.extensionPoints && uc.extensionPoints.length > 0);
    const rx = uc.rx;
    const ry = uc.ry;

    // Use Case Ellipse
    const ellipse = document.createElementNS("http://www.w3.org/2000/svg", "ellipse");
    ellipse.setAttribute("class", "uc-ellipse");
    ellipse.setAttribute("cx", uc.x);
    ellipse.setAttribute("cy", uc.y);
    ellipse.setAttribute("rx", rx);
    ellipse.setAttribute("ry", ry);
    ellipse.setAttribute("fill", uc.focal ? "rgba(200, 80, 30, 0.04)" : "#ffffff");
    ellipse.setAttribute("stroke", uc.focal ? "#c8501e" : "#1a1d2e");
    ellipse.setAttribute("stroke-width", uc.focal ? "1.6" : "1.2");
    ellipse.setAttribute("style", "filter: drop-shadow(0 1px 3px rgba(0,0,0,0.03));");
    g.appendChild(ellipse);

    if (hasExtensionPoints) {
      // Horizontal divider line
      const divLine = document.createElementNS("http://www.w3.org/2000/svg", "line");
      divLine.setAttribute("x1", uc.x - rx + 14);
      divLine.setAttribute("y1", uc.y - 4);
      divLine.setAttribute("x2", uc.x + rx - 14);
      divLine.setAttribute("y2", uc.y - 4);
      divLine.setAttribute("stroke", uc.focal ? "#c8501e" : "#1a1d2e");
      divLine.setAttribute("stroke-width", "0.9");
      g.appendChild(divLine);

      // Name above divider
      const nameText = document.createElementNS("http://www.w3.org/2000/svg", "text");
      nameText.setAttribute("x", uc.x);
      nameText.setAttribute("y", uc.y - 14);
      nameText.setAttribute("text-anchor", "middle");
      nameText.setAttribute("font-family", "'Geist', sans-serif");
      nameText.setAttribute("font-size", "11.5");
      nameText.setAttribute("font-weight", "700");
      nameText.setAttribute("fill", uc.focal ? "#c8501e" : "#1a1d2e");
      nameText.textContent = uc.name;
      g.appendChild(nameText);

      // "extension points" label
      const extLabel = document.createElementNS("http://www.w3.org/2000/svg", "text");
      extLabel.setAttribute("x", uc.x);
      extLabel.setAttribute("y", uc.y + 10);
      extLabel.setAttribute("text-anchor", "middle");
      extLabel.setAttribute("font-family", "'Geist Mono', monospace");
      extLabel.setAttribute("font-size", "8.5");
      extLabel.setAttribute("font-weight", "700");
      extLabel.setAttribute("fill", uc.focal ? "#c8501e" : "#4f5d75");
      extLabel.textContent = "extension points";
      g.appendChild(extLabel);

      // Extension point description
      const extPoints = uc.extensionPoints || ['thay đổi thông tin / ngoại lệ'];
      const epText = document.createElementNS("http://www.w3.org/2000/svg", "text");
      epText.setAttribute("x", uc.x);
      epText.setAttribute("y", uc.y + 23);
      epText.setAttribute("text-anchor", "middle");
      epText.setAttribute("font-family", "'Geist', sans-serif");
      epText.setAttribute("font-size", "9");
      epText.setAttribute("fill", "#5b6579");
      epText.textContent = extPoints[0];
      g.appendChild(epText);
    } else {
      // Standard Use Case Name
      const nameText = document.createElementNS("http://www.w3.org/2000/svg", "text");
      nameText.setAttribute("x", uc.x);
      nameText.setAttribute("y", uc.y + 4);
      nameText.setAttribute("text-anchor", "middle");
      nameText.setAttribute("font-family", "'Geist', sans-serif");
      nameText.setAttribute("font-size", "11.5");
      nameText.setAttribute("font-weight", "600");
      nameText.setAttribute("fill", "#1a1d2e");
      nameText.textContent = uc.name;
      g.appendChild(nameText);
    }

    // Hover effect
    g.addEventListener('mouseenter', () => {
      document.querySelectorAll('.uc-line').forEach(l => l.classList.add('is-dimmed'));
      document.querySelectorAll(`.uc-rel-${uc.id}`).forEach(l => {
        l.classList.remove('is-dimmed');
        l.classList.add('is-highlighted');
      });
    });
    g.addEventListener('mouseleave', () => {
      document.querySelectorAll('.uc-line').forEach(l => {
        l.classList.remove('is-dimmed');
        l.classList.remove('is-highlighted');
      });
    });

    ucLayer.appendChild(g);
  });

  svg.appendChild(ucLayer);

  // LAYER 4: UML Stick Figure Actors
  const actorsLayer = document.createElementNS("http://www.w3.org/2000/svg", "g");
  actorsLayer.setAttribute("class", "actors-layer");

  allActors.forEach((actor) => {
    const cx = actor.x;
    const cy = actor.y;
    const isSec = isSecondary(actor.name);

    const g = document.createElementNS("http://www.w3.org/2000/svg", "g");
    g.setAttribute("class", "actor-group");
    g.setAttribute("id", `actor-node-${actor.name.replace(/\\s+/g, '-')}`);

    const headR = 12;
    const headCy = cy + headR;

    // Head circle (Sky blue fill #70c5e8 matching docx UML sample)
    const head = document.createElementNS("http://www.w3.org/2000/svg", "circle");
    head.setAttribute("class", "actor-head");
    head.setAttribute("cx", cx);
    head.setAttribute("cy", headCy);
    head.setAttribute("r", headR);
    head.setAttribute("fill", isSec ? "#e2e8f0" : "#70c5e8");
    head.setAttribute("stroke", "#1a1d2e");
    head.setAttribute("stroke-width", "1.5");
    g.appendChild(head);

    // Body
    const body = document.createElementNS("http://www.w3.org/2000/svg", "line");
    body.setAttribute("class", "actor-body");
    body.setAttribute("x1", cx);
    body.setAttribute("y1", headCy + headR);
    body.setAttribute("x2", cx);
    body.setAttribute("y2", headCy + headR + 26);
    body.setAttribute("stroke", "#1a1d2e");
    body.setAttribute("stroke-width", "1.5");
    body.setAttribute("stroke-linecap", "round");
    g.appendChild(body);

    // Arms
    const arms = document.createElementNS("http://www.w3.org/2000/svg", "line");
    arms.setAttribute("class", "actor-arm");
    arms.setAttribute("x1", cx - 18);
    arms.setAttribute("y1", headCy + headR + 8);
    arms.setAttribute("x2", cx + 18);
    arms.setAttribute("y2", headCy + headR + 8);
    arms.setAttribute("stroke", "#1a1d2e");
    arms.setAttribute("stroke-width", "1.5");
    arms.setAttribute("stroke-linecap", "round");
    g.appendChild(arms);

    // Left Leg
    const legL = document.createElementNS("http://www.w3.org/2000/svg", "line");
    legL.setAttribute("class", "actor-leg");
    legL.setAttribute("x1", cx);
    legL.setAttribute("y1", headCy + headR + 26);
    legL.setAttribute("x2", cx - 14);
    legL.setAttribute("y2", headCy + headR + 54);
    legL.setAttribute("stroke", "#1a1d2e");
    legL.setAttribute("stroke-width", "1.5");
    legL.setAttribute("stroke-linecap", "round");
    g.appendChild(legL);

    // Right Leg
    const legR = document.createElementNS("http://www.w3.org/2000/svg", "line");
    legR.setAttribute("class", "actor-leg");
    legR.setAttribute("x1", cx);
    legR.setAttribute("y1", headCy + headR + 26);
    legR.setAttribute("x2", cx + 14);
    legR.setAttribute("y2", headCy + headR + 54);
    legR.setAttribute("stroke", "#1a1d2e");
    legR.setAttribute("stroke-width", "1.5");
    legR.setAttribute("stroke-linecap", "round");
    g.appendChild(legR);

    // Stereotype tag
    if (isSec) {
      const sysTag = document.createElementNS("http://www.w3.org/2000/svg", "text");
      sysTag.setAttribute("x", cx);
      sysTag.setAttribute("y", cy - 4);
      sysTag.setAttribute("text-anchor", "middle");
      sysTag.setAttribute("font-family", "'Geist Mono', monospace");
      sysTag.setAttribute("font-size", "8.5");
      sysTag.setAttribute("font-weight", "600");
      sysTag.setAttribute("fill", "#4f5d75");
      sysTag.textContent = "«system»";
      g.appendChild(sysTag);
    }

    // Actor Name
    const nameLbl = document.createElementNS("http://www.w3.org/2000/svg", "text");
    nameLbl.setAttribute("x", cx);
    nameLbl.setAttribute("y", headCy + headR + 70);
    nameLbl.setAttribute("text-anchor", "middle");
    nameLbl.setAttribute("font-family", "'Geist', sans-serif");
    nameLbl.setAttribute("font-size", "12");
    nameLbl.setAttribute("font-weight", "600");
    nameLbl.setAttribute("fill", "#1a1d2e");
    nameLbl.textContent = actor.name;
    g.appendChild(nameLbl);

    // Hover effect
    const safeActorName = actor.name.replace(/\\s+/g, '-');
    g.addEventListener('mouseenter', () => {
      document.querySelectorAll('.uc-line').forEach(l => l.classList.add('is-dimmed'));
      document.querySelectorAll(`.actor-rel-${safeActorName}`).forEach(l => {
        l.classList.remove('is-dimmed');
        l.classList.add('is-highlighted');
      });
    });
    g.addEventListener('mouseleave', () => {
      document.querySelectorAll('.uc-line').forEach(l => {
        l.classList.remove('is-dimmed');
        l.classList.remove('is-highlighted');
      });
    });

    actorsLayer.appendChild(g);
  });

  svg.appendChild(actorsLayer);
}

// 3. Sequence & 4. FSM Diagram Renderers
    function renderSequence(svg, data, reqWidth, reqHeight) {
      const participants = data.participants || [];
      const steps = data.steps || [];

      const pCount = participants.length;
      const w = reqWidth || 1160;
      const cardPad = 30;
      const boundaryW = w - cardPad * 2;
      const boundaryH = (reqHeight || 740) - cardPad * 2;
      const boundaryX = cardPad;
      const boundaryY = cardPad;

      // 1. Boundary Container Card
      const boundaryGroup = document.createElementNS("http://www.w3.org/2000/svg", "g");

      const boundaryRect = document.createElementNS("http://www.w3.org/2000/svg", "rect");
      boundaryRect.setAttribute("x", boundaryX);
      boundaryRect.setAttribute("y", boundaryY);
      boundaryRect.setAttribute("width", boundaryW);
      boundaryRect.setAttribute("height", boundaryH);
      boundaryRect.setAttribute("rx", "12");
      boundaryRect.setAttribute("fill", "#ffffff");
      boundaryRect.setAttribute("stroke", "rgba(45,49,66,0.16)");
      boundaryRect.setAttribute("stroke-width", "1.2");
      boundaryRect.setAttribute("style", "filter: drop-shadow(0 4px 18px rgba(45,49,66,0.04));");
      boundaryGroup.appendChild(boundaryRect);

      // Header strip
      const headerPath = document.createElementNS("http://www.w3.org/2000/svg", "path");
      headerPath.setAttribute("d", `M ${boundaryX},${boundaryY+12} Q ${boundaryX},${boundaryY} ${boundaryX+12},${boundaryY} L ${boundaryX+boundaryW-12},${boundaryY} Q ${boundaryX+boundaryW},${boundaryY} ${boundaryX+boundaryW},${boundaryY+12} L ${boundaryX+boundaryW},${boundaryY+46} L ${boundaryX},${boundaryY+46} Z`);
      headerPath.setAttribute("fill", "rgba(45,49,66,0.025)");
      boundaryGroup.appendChild(headerPath);

      const headerLine = document.createElementNS("http://www.w3.org/2000/svg", "line");
      headerLine.setAttribute("x1", boundaryX);
      headerLine.setAttribute("y1", boundaryY + 46);
      headerLine.setAttribute("x2", boundaryX + boundaryW);
      headerLine.setAttribute("y2", boundaryY + 46);
      headerLine.setAttribute("stroke", "rgba(45,49,66,0.1)");
      headerLine.setAttribute("stroke-width", "1");
      boundaryGroup.appendChild(headerLine);

      // Tag Pill
      const tagRect = document.createElementNS("http://www.w3.org/2000/svg", "rect");
      tagRect.setAttribute("x", boundaryX + 20);
      tagRect.setAttribute("y", boundaryY + 14);
      tagRect.setAttribute("width", "110");
      tagRect.setAttribute("height", "16");
      tagRect.setAttribute("rx", "3");
      tagRect.setAttribute("fill", "rgba(46,90,168,0.1)");
      boundaryGroup.appendChild(tagRect);

      const tagText = document.createElementNS("http://www.w3.org/2000/svg", "text");
      tagText.setAttribute("x", boundaryX + 75);
      tagText.setAttribute("y", boundaryY + 25.5);
      tagText.setAttribute("text-anchor", "middle");
      tagText.setAttribute("font-family", "'Geist Mono', monospace");
      tagText.setAttribute("font-size", "7.5");
      tagText.setAttribute("font-weight", "600");
      tagText.setAttribute("fill", "#2e5aa8");
      tagText.setAttribute("letter-spacing", "0.12em");
      tagText.textContent = "SEQUENCE TRACE";
      boundaryGroup.appendChild(tagText);

      // Scenario Title
      const titleText = document.createElementNS("http://www.w3.org/2000/svg", "text");
      titleText.setAttribute("x", boundaryX + 140);
      titleText.setAttribute("y", boundaryY + 26);
      titleText.setAttribute("font-family", "'Geist', sans-serif");
      titleText.setAttribute("font-size", "11.5");
      titleText.setAttribute("font-weight", "600");
      titleText.setAttribute("fill", "#232733");
      const cleanSeqTitle = (data.description || 'Execution Sequence').split(':')[0].replace(/^Sequence\s*/i, 'Scenario: ');
      titleText.textContent = cleanSeqTitle;
      boundaryGroup.appendChild(titleText);

      svg.appendChild(boundaryGroup);

      // Calculate participant X positions
      const padLeft = boundaryX + 80;
      const padRight = boundaryX + boundaryW - 80;
      const colWidth = pCount > 1 ? (padRight - padLeft) / (pCount - 1) : 0;
      const partY = boundaryY + 68;
      const partBoxW = 126;
      const partBoxH = 40;
      const rowHeight = 48;
      const lifelineStartY = partY + partBoxH;
      const lifelineEndY = boundaryY + boundaryH - 30;

      const partPositions = participants.map((p, i) => {
        const cx = pCount > 1 ? padLeft + i * colWidth : boundaryX + boundaryW / 2;
        return { ...p, cx };
      });

      // 2. Lifelines Layer
      const lifelinesGroup = document.createElementNS("http://www.w3.org/2000/svg", "g");
      lifelinesGroup.setAttribute("class", "seq-lifelines-layer");

      partPositions.forEach((p) => {
        const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
        line.setAttribute("x1", p.cx);
        line.setAttribute("y1", lifelineStartY);
        line.setAttribute("x2", p.cx);
        line.setAttribute("y2", lifelineEndY);
        line.setAttribute("stroke", "rgba(79,93,117,0.35)");
        line.setAttribute("stroke-width", "1.2");
        line.setAttribute("stroke-dasharray", "4,4");
        lifelinesGroup.appendChild(line);
      });
      svg.appendChild(lifelinesGroup);

      // 3. Activations Layer
      const actGroup = document.createElementNS("http://www.w3.org/2000/svg", "g");
      actGroup.setAttribute("class", "seq-activations-layer");

      steps.forEach((step, i) => {
        const y = lifelineStartY + 30 + i * rowHeight;
        [step.from, step.to].forEach(pIdx => {
          if (pIdx !== undefined && partPositions[pIdx]) {
            const cx = partPositions[pIdx].cx;
            const bar = document.createElementNS("http://www.w3.org/2000/svg", "rect");
            bar.setAttribute("x", cx - 5);
            bar.setAttribute("y", y - 10);
            bar.setAttribute("width", 10);
            bar.setAttribute("height", 20);
            bar.setAttribute("rx", 2);
            bar.setAttribute("fill", "#f6f6f8");
            bar.setAttribute("stroke", "#4f5d75");
            bar.setAttribute("stroke-width", "0.8");
            actGroup.appendChild(bar);
          }
        });
      });
      svg.appendChild(actGroup);

      // 4. Steps Layer
      const stepsGroup = document.createElementNS("http://www.w3.org/2000/svg", "g");
      stepsGroup.setAttribute("class", "seq-steps-layer");

      steps.forEach((step, i) => {
        const y = lifelineStartY + 30 + i * rowHeight;
        const pFrom = partPositions[step.from];
        const pTo = partPositions[step.to];
        if (!pFrom || !pTo) return;

        const isSelf = step.from === step.to;
        const isReturn = step.type === "return";
        const isAsync = step.type === "async";
        const isSuccess = step.label && (step.label.includes("Success") || step.label.includes("confirmed"));

        const g = document.createElementNS("http://www.w3.org/2000/svg", "g");
        g.setAttribute("class", "seq-step");

        if (isSelf) {
          const fromX = pFrom.cx + 5;
          const loopW = 38;
          const loopPath = document.createElementNS("http://www.w3.org/2000/svg", "path");
          loopPath.setAttribute("class", "step-line");
          loopPath.setAttribute("d", `M ${fromX} ${y - 8} H ${fromX + loopW} V ${y + 12} H ${fromX}`);
          loopPath.setAttribute("fill", "none");
          loopPath.setAttribute("stroke", "#232733");
          loopPath.setAttribute("stroke-width", "1.4");
          loopPath.setAttribute("marker-end", "url(#arr-dark)");
          g.appendChild(loopPath);

          const labelX = fromX + loopW + 8;
          const textW = step.label.length * 6.5 + 24;
          const lblBg = document.createElementNS("http://www.w3.org/2000/svg", "rect");
          lblBg.setAttribute("class", "step-pill-bg");
          lblBg.setAttribute("x", labelX);
          lblBg.setAttribute("y", y - 7);
          lblBg.setAttribute("width", textW);
          lblBg.setAttribute("height", 18);
          lblBg.setAttribute("rx", 4);
          lblBg.setAttribute("fill", "#ffffff");
          lblBg.setAttribute("stroke", "rgba(45,49,66,0.18)");
          lblBg.setAttribute("stroke-width", "0.8");
          lblBg.setAttribute("style", "filter: drop-shadow(0 1px 3px rgba(0,0,0,0.03));");
          g.appendChild(lblBg);

          const lblTxt = document.createElementNS("http://www.w3.org/2000/svg", "text");
          lblTxt.setAttribute("x", labelX + 10);
          lblTxt.setAttribute("y", y + 5);
          lblTxt.setAttribute("font-family", "'Geist Mono', monospace");
          lblTxt.setAttribute("font-size", "8.5");
          lblTxt.setAttribute("fill", "#232733");
          lblTxt.textContent = `${i + 1}. ${step.label}`;
          g.appendChild(lblTxt);
        } else {
          const fromX = pFrom.cx + (pFrom.cx < pTo.cx ? 5 : -5);
          const toX = pTo.cx + (pFrom.cx < pTo.cx ? -5 : 5);

          const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
          line.setAttribute("class", "step-line");
          line.setAttribute("x1", fromX);
          line.setAttribute("y1", y);
          line.setAttribute("x2", toX);
          line.setAttribute("y2", y);

          let strokeColor = "#232733";
          let marker = "url(#arr-dark)";

          if (isSuccess) {
            strokeColor = "#eb6c36";
            marker = "url(#arr-acc)";
          } else if (isAsync) {
            strokeColor = "#2e5aa8";
            marker = "url(#arr-blue)";
          } else if (isReturn) {
            strokeColor = "#4f5d75";
            marker = "url(#arr)";
          }

          line.setAttribute("stroke", strokeColor);
          line.setAttribute("stroke-width", isSuccess ? "1.8" : "1.4");
          if (isReturn || isAsync) {
            line.setAttribute("stroke-dasharray", "5,4");
          }
          line.setAttribute("marker-end", marker);
          g.appendChild(line);

          // Step Number Chip
          const chipX = fromX < toX ? fromX + 8 : fromX - 26;
          const numRect = document.createElementNS("http://www.w3.org/2000/svg", "rect");
          numRect.setAttribute("x", chipX);
          numRect.setAttribute("y", y - 7);
          numRect.setAttribute("width", "18");
          numRect.setAttribute("height", "14");
          numRect.setAttribute("rx", "3");
          numRect.setAttribute("fill", isSuccess ? "#eb6c36" : "rgba(45,49,66,0.08)");
          g.appendChild(numRect);

          const numTxt = document.createElementNS("http://www.w3.org/2000/svg", "text");
          numTxt.setAttribute("x", chipX + 9);
          numTxt.setAttribute("y", y + 3);
          numTxt.setAttribute("text-anchor", "middle");
          numTxt.setAttribute("font-family", "'Geist Mono', monospace");
          numTxt.setAttribute("font-size", "7.5");
          numTxt.setAttribute("font-weight", "600");
          numTxt.setAttribute("fill", isSuccess ? "#ffffff" : "#4f5d75");
          numTxt.textContent = `${i + 1}`;
          g.appendChild(numTxt);

          // Message Label Floating Pill
          const midX = (fromX + toX) / 2;
          const textW = Math.max(70, step.label.length * 6.5);

          const pillBg = document.createElementNS("http://www.w3.org/2000/svg", "rect");
          pillBg.setAttribute("class", "step-pill-bg");
          pillBg.setAttribute("x", midX - textW / 2);
          pillBg.setAttribute("y", y - 9);
          pillBg.setAttribute("width", textW);
          pillBg.setAttribute("height", "18");
          pillBg.setAttribute("rx", "4");
          pillBg.setAttribute("fill", "#ffffff");
          pillBg.setAttribute("stroke", isSuccess ? "rgba(235,108,54,0.4)" : "rgba(45,49,66,0.18)");
          pillBg.setAttribute("stroke-width", "0.8");
          pillBg.setAttribute("style", "filter: drop-shadow(0 1px 3px rgba(0,0,0,0.03));");
          g.appendChild(pillBg);

          const pillTxt = document.createElementNS("http://www.w3.org/2000/svg", "text");
          pillTxt.setAttribute("x", midX);
          pillTxt.setAttribute("y", y + 3.5);
          pillTxt.setAttribute("text-anchor", "middle");
          pillTxt.setAttribute("font-family", "'Geist Mono', monospace");
          pillTxt.setAttribute("font-size", "8.5");
          pillTxt.setAttribute("font-weight", isSuccess ? "600" : "500");
          pillTxt.setAttribute("fill", isSuccess ? "#eb6c36" : (isAsync ? "#2e5aa8" : "#232733"));
          pillTxt.textContent = step.label;
          g.appendChild(pillTxt);
        }

        stepsGroup.appendChild(g);
      });
      svg.appendChild(stepsGroup);

      // 5. Participants Top Cards Layer
      const partGroup = document.createElementNS("http://www.w3.org/2000/svg", "g");
      partGroup.setAttribute("class", "seq-participants-layer");

      partPositions.forEach((p) => {
        const g = document.createElementNS("http://www.w3.org/2000/svg", "g");
        g.setAttribute("class", "seq-participant");

        const box = document.createElementNS("http://www.w3.org/2000/svg", "rect");
        box.setAttribute("class", "part-box");
        box.setAttribute("x", p.cx - partBoxW / 2);
        box.setAttribute("y", partY);
        box.setAttribute("width", partBoxW);
        box.setAttribute("height", partBoxH);
        box.setAttribute("rx", "6");
        box.setAttribute("fill", p.system ? "rgba(46,90,168,0.06)" : "#ffffff");
        box.setAttribute("stroke", p.system ? "#2e5aa8" : "#232733");
        box.setAttribute("stroke-width", "1.4");
        box.setAttribute("style", "filter: drop-shadow(0 2px 6px rgba(0,0,0,0.03));");
        g.appendChild(box);

        const tag = document.createElementNS("http://www.w3.org/2000/svg", "text");
        tag.setAttribute("x", p.cx);
        tag.setAttribute("y", partY + 14);
        tag.setAttribute("text-anchor", "middle");
        tag.setAttribute("font-family", "'Geist Mono', monospace");
        tag.setAttribute("font-size", "7.5");
        tag.setAttribute("font-weight", "500");
        tag.setAttribute("fill", p.system ? "#2e5aa8" : "#7a8399");
        tag.setAttribute("letter-spacing", "0.08em");
        tag.textContent = p.system ? "«system»" : "«actor»";
        g.appendChild(tag);

        const name = document.createElementNS("http://www.w3.org/2000/svg", "text");
        name.setAttribute("x", p.cx);
        name.setAttribute("y", partY + 29);
        name.setAttribute("text-anchor", "middle");
        name.setAttribute("font-family", "'Geist', sans-serif");
        name.setAttribute("font-size", "11");
        name.setAttribute("font-weight", "600");
        name.setAttribute("fill", "#232733");
        name.textContent = p.name;
        g.appendChild(name);

        partGroup.appendChild(g);
      });
      svg.appendChild(partGroup);
    }

    function renderFSM(svg, data) {
      const states = data.states || [];
      const transitions = data.transitions || [];

      // Balanced hand-crafted non-crossing layout coordinates for each lifecycle
      const FSM_LAYOUTS = {
        fsm1: {
          s1: { x: 80, y: 150 },
          s2: { x: 340, y: 150 },
          s3: { x: 600, y: 150 },
          s4: { x: 860, y: 150 },
          s7: { x: 210, y: 350 },
          s5: { x: 600, y: 350 },
          s6: { x: 860, y: 350 },
          s8: { x: 860, y: 500 }
        },
        fsm2: {
          s1: { x: 80, y: 150 },
          s2: { x: 340, y: 150 },
          s3: { x: 600, y: 150 },
          s4: { x: 860, y: 150 },
          s6: { x: 80, y: 360 },
          s7: { x: 260, y: 360 },
          s8: { x: 600, y: 360 },
          s5: { x: 860, y: 360 }
        },
        fsm3: {
          s1: { x: 120, y: 180 },
          s2: { x: 480, y: 180 },
          s3: { x: 840, y: 180 },
          s4: { x: 300, y: 380 }
        },
        fsm4: {
          s1: { x: 120, y: 180 },
          s2: { x: 460, y: 180 },
          s4: { x: 820, y: 180 },
          s5: { x: 260, y: 380 },
          s3: { x: 580, y: 380 }
        },
        fsm5: {
          s1: { x: 100, y: 160 },
          s2: { x: 380, y: 160 },
          s3: { x: 660, y: 160 },
          s4: { x: 920, y: 160 },
          s6: { x: 240, y: 370 },
          s5: { x: 520, y: 370 }
        },
        fsm6: {
          s1: { x: 120, y: 160 },
          s2: { x: 460, y: 160 },
          s3: { x: 800, y: 160 },
          s4: { x: 290, y: 370 },
          s5: { x: 640, y: 370 },
          s6: { x: 920, y: 370 }
        },
        fsm7: {
          s1: { x: 100, y: 160 },
          s2: { x: 370, y: 160 },
          s3: { x: 640, y: 160 },
          s4: { x: 910, y: 160 },
          s7: { x: 100, y: 370 },
          s5: { x: 640, y: 370 },
          s6: { x: 910, y: 370 }
        },
        fsm8: {
          s1: { x: 80, y: 240 },
          s2: { x: 290, y: 240 },
          s3: { x: 510, y: 240 },
          s4: { x: 730, y: 240 },
          s5: { x: 940, y: 240 }
        },
        fsm9: {
          s1: { x: 140, y: 180 },
          s2: { x: 500, y: 180 },
          s4: { x: 840, y: 180 },
          s5: { x: 240, y: 380 },
          s3: { x: 640, y: 380 }
        },
        fsm10: {
          s1: { x: 80, y: 140 },
          s2: { x: 300, y: 140 },
          s3: { x: 520, y: 140 },
          s4: { x: 740, y: 140 },
          s5: { x: 960, y: 140 },
          s6: { x: 1160, y: 140 },
          s7: { x: 1160, y: 360 },
          s8: { x: 80, y: 360 },
          s9: { x: 640, y: 360 }
        }
      };

      const layout = FSM_LAYOUTS[currentKey] || {};
      const stateW = 146;
      const stateH = 50;

      // Calculate state positions
      states.forEach((s, idx) => {
        if (layout[s.id]) {
          s.x = layout[s.id].x;
          s.y = layout[s.id].y;
        } else {
          const col = idx % 4;
          const row = Math.floor(idx / 4);
          s.x = 100 + col * 260;
          s.y = 150 + row * 190;
        }
      });

      // Bounding card
      let maxX = 1050;
      let maxY = 560;
      states.forEach(s => {
        if (s.x + stateW > maxX) maxX = s.x + stateW;
        if (s.y + stateH > maxY) maxY = s.y + stateH;
      });

      const boundaryW = Math.max(1060, maxX + 50);
      const boundaryH = Math.max(580, maxY + 60);

      svg.setAttribute("viewBox", `0 0 ${boundaryW + 40} ${boundaryH + 40}`);

      const boundaryGroup = document.createElementNS("http://www.w3.org/2000/svg", "g");

      const boundaryRect = document.createElementNS("http://www.w3.org/2000/svg", "rect");
      boundaryRect.setAttribute("x", "20");
      boundaryRect.setAttribute("y", "20");
      boundaryRect.setAttribute("width", boundaryW);
      boundaryRect.setAttribute("height", boundaryH);
      boundaryRect.setAttribute("rx", "12");
      boundaryRect.setAttribute("fill", "#ffffff");
      boundaryRect.setAttribute("stroke", "rgba(45,49,66,0.16)");
      boundaryRect.setAttribute("stroke-width", "1.2");
      boundaryRect.setAttribute("style", "filter: drop-shadow(0 4px 18px rgba(45,49,66,0.04));");
      boundaryGroup.appendChild(boundaryRect);

      // Header strip
      const headerPath = document.createElementNS("http://www.w3.org/2000/svg", "path");
      headerPath.setAttribute("d", `M 20,32 Q 20,20 32,20 L ${boundaryW+8},20 Q ${boundaryW+20},20 ${boundaryW+20},32 L ${boundaryW+20},66 L 20,66 Z`);
      headerPath.setAttribute("fill", "rgba(45,49,66,0.025)");
      boundaryGroup.appendChild(headerPath);

      const headerLine = document.createElementNS("http://www.w3.org/2000/svg", "line");
      headerLine.setAttribute("x1", "20");
      headerLine.setAttribute("y1", "66");
      headerLine.setAttribute("x2", boundaryW + 20);
      headerLine.setAttribute("y2", "66");
      headerLine.setAttribute("stroke", "rgba(45,49,66,0.1)");
      headerLine.setAttribute("stroke-width", "1");
      boundaryGroup.appendChild(headerLine);

      // Tag Pill
      const tagRect = document.createElementNS("http://www.w3.org/2000/svg", "rect");
      tagRect.setAttribute("x", "40");
      tagRect.setAttribute("y", "34");
      tagRect.setAttribute("width", "130");
      tagRect.setAttribute("height", "16");
      tagRect.setAttribute("rx", "3");
      tagRect.setAttribute("fill", "rgba(235,108,54,0.1)");
      boundaryGroup.appendChild(tagRect);

      const tagText = document.createElementNS("http://www.w3.org/2000/svg", "text");
      tagText.setAttribute("x", "105");
      tagText.setAttribute("y", "45.5");
      tagText.setAttribute("text-anchor", "middle");
      tagText.setAttribute("font-family", "'Geist Mono', monospace");
      tagText.setAttribute("font-size", "7.5");
      tagText.setAttribute("font-weight", "600");
      tagText.setAttribute("fill", "#eb6c36");
      tagText.setAttribute("letter-spacing", "0.12em");
      tagText.textContent = "FINITE STATE MACHINE";
      boundaryGroup.appendChild(tagText);

      // Title
      const titleText = document.createElementNS("http://www.w3.org/2000/svg", "text");
      titleText.setAttribute("x", "185");
      titleText.setAttribute("y", "46");
      titleText.setAttribute("font-family", "'Geist', sans-serif");
      titleText.setAttribute("font-size", "11.5");
      titleText.setAttribute("font-weight", "600");
      titleText.setAttribute("fill", "#232733");
      const cleanFsmTitle = (data.description || 'FSM Lifecycle').split(':')[0].replace(/^FSM\s*/i, 'Lifecycle: ');
      titleText.textContent = cleanFsmTitle;
      boundaryGroup.appendChild(titleText);

      svg.appendChild(boundaryGroup);

      // 1. Transitions Layer
      const transGroup = document.createElementNS("http://www.w3.org/2000/svg", "g");
      transGroup.setAttribute("class", "fsm-transitions-layer");

      transitions.forEach((trans) => {
        const sFrom = states.find(s => s.id === trans.from);
        const sTo = states.find(s => s.id === trans.to);
        if (!sFrom || !sTo) return;

        const g = document.createElementNS("http://www.w3.org/2000/svg", "g");
        g.setAttribute("class", `fsm-trans-group ${trans.focal ? 'is-focal' : ''}`);

        let pathData = "";
        let midX = 0;
        let midY = 0;

        const isHorizontal = Math.abs(sFrom.y - sTo.y) < 30;
        const isVertical = Math.abs(sFrom.x - sTo.x) < 30;
        const isSelf = trans.from === trans.to;

        if (isSelf) {
          const lx = sFrom.x + stateW / 2;
          const ly = sFrom.y;
          pathData = `M ${lx - 25} ${ly} C ${lx - 40} ${ly - 45}, ${lx + 40} ${ly - 45}, ${lx + 25} ${ly}`;
          midX = lx;
          midY = ly - 36;
        } else if (isHorizontal) {
          const isLeftToRight = sFrom.x < sTo.x;
          const hasReverse = transitions.some(t => t.from === trans.to && t.to === trans.from);
          const startX = isLeftToRight ? sFrom.x + stateW : sFrom.x;
          const endX = isLeftToRight ? sTo.x : sTo.x + stateW;
          const offset = hasReverse ? (isLeftToRight ? -14 : 14) : 0;
          const startY = sFrom.y + stateH / 2 + offset;
          const endY = sTo.y + stateH / 2 + offset;

          if (hasReverse) {
            const arcY = startY + (isLeftToRight ? -25 : 25);
            pathData = `M ${startX} ${startY} Q ${(startX + endX)/2} ${arcY} ${endX} ${endY}`;
            midX = (startX + endX) / 2;
            midY = arcY;
          } else {
            pathData = `M ${startX} ${startY} L ${endX} ${endY}`;
            midX = (startX + endX) / 2;
            midY = startY;
          }
        } else if (isVertical) {
          const isTopToBottom = sFrom.y < sTo.y;
          const startX = sFrom.x + stateW / 2;
          const startY = isTopToBottom ? sFrom.y + stateH : sFrom.y;
          const endX = sTo.x + stateW / 2;
          const endY = isTopToBottom ? sTo.y : sTo.y + stateH;
          pathData = `M ${startX} ${startY} L ${endX} ${endY}`;
          midX = startX;
          midY = (startY + endY) / 2;
        } else {
          const isDown = sFrom.y < sTo.y;
          const startX = sFrom.x + stateW / 2;
          const startY = isDown ? sFrom.y + stateH : sFrom.y;
          const endX = sTo.x + stateW / 2;
          const endY = isDown ? sTo.y : sTo.y + stateH;
          const dx = endX - startX;
          const dy = endY - startY;

          pathData = `M ${startX} ${startY} C ${startX} ${startY + dy*0.4}, ${endX - dx*0.2} ${endY - dy*0.4}, ${endX} ${endY}`;
          midX = (startX + endX) / 2;
          midY = (startY + endY) / 2;
        }

        const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
        path.setAttribute("class", "trans-path");
        path.setAttribute("d", pathData);
        path.setAttribute("fill", "none");
        path.setAttribute("stroke", trans.focal ? "#eb6c36" : "#4f5d75");
        path.setAttribute("stroke-width", trans.focal ? "1.8" : "1.3");
        path.setAttribute("marker-end", trans.focal ? "url(#arr-acc)" : "url(#arr)");
        g.appendChild(path);

        // Transition Label Pill
        const textW = Math.max(64, trans.event.length * 6.5);
        const pillBg = document.createElementNS("http://www.w3.org/2000/svg", "rect");
        pillBg.setAttribute("class", "trans-pill-bg");
        pillBg.setAttribute("x", midX - textW / 2);
        pillBg.setAttribute("y", midY - 9);
        pillBg.setAttribute("width", textW);
        pillBg.setAttribute("height", "18");
        pillBg.setAttribute("rx", "4");
        pillBg.setAttribute("fill", "#ffffff");
        pillBg.setAttribute("stroke", trans.focal ? "rgba(235,108,54,0.4)" : "rgba(45,49,66,0.18)");
        pillBg.setAttribute("stroke-width", "0.8");
        pillBg.setAttribute("style", "filter: drop-shadow(0 1px 3px rgba(0,0,0,0.04));");
        g.appendChild(pillBg);

        const pillTxt = document.createElementNS("http://www.w3.org/2000/svg", "text");
        pillTxt.setAttribute("x", midX);
        pillTxt.setAttribute("y", midY + 3.5);
        pillTxt.setAttribute("text-anchor", "middle");
        pillTxt.setAttribute("font-family", "'Geist Mono', monospace");
        pillTxt.setAttribute("font-size", "8.5");
        pillTxt.setAttribute("font-weight", trans.focal ? "600" : "500");
        pillTxt.setAttribute("fill", trans.focal ? "#eb6c36" : "#232733");
        pillTxt.textContent = trans.event;
        g.appendChild(pillTxt);

        transGroup.appendChild(g);
      });
      svg.appendChild(transGroup);

      // 2. States Layer
      const statesGroup = document.createElementNS("http://www.w3.org/2000/svg", "g");
      statesGroup.setAttribute("class", "fsm-states-layer");

      states.forEach((state) => {
        const g = document.createElementNS("http://www.w3.org/2000/svg", "g");
        g.setAttribute("class", `fsm-state-node ${state.focal ? 'is-focal' : ''}`);

        const isInitial = state.type === "initial";
        const isFinal = state.type === "final";

        // Initial entry indicator (filled dot + arrow)
        if (isInitial) {
          const dot = document.createElementNS("http://www.w3.org/2000/svg", "circle");
          dot.setAttribute("cx", state.x - 24);
          dot.setAttribute("cy", state.y + stateH / 2);
          dot.setAttribute("r", "5.5");
          dot.setAttribute("fill", "#232733");
          g.appendChild(dot);

          const dotLine = document.createElementNS("http://www.w3.org/2000/svg", "line");
          dotLine.setAttribute("x1", state.x - 24);
          dotLine.setAttribute("y1", state.y + stateH / 2);
          dotLine.setAttribute("x2", state.x);
          dotLine.setAttribute("y2", state.y + stateH / 2);
          dotLine.setAttribute("stroke", "#232733");
          dotLine.setAttribute("stroke-width", "1.4");
          dotLine.setAttribute("marker-end", "url(#arr-dark)");
          g.appendChild(dotLine);
        }

        // State Card Rectangle
        const card = document.createElementNS("http://www.w3.org/2000/svg", "rect");
        card.setAttribute("class", "state-card");
        card.setAttribute("x", state.x);
        card.setAttribute("y", state.y);
        card.setAttribute("width", stateW);
        card.setAttribute("height", stateH);
        card.setAttribute("rx", isFinal ? "10" : "8");
        card.setAttribute("fill", state.focal ? "rgba(235,108,54,0.08)" : (isFinal ? "rgba(45,49,66,0.03)" : "#ffffff"));
        card.setAttribute("stroke", state.focal ? "#eb6c36" : (isInitial ? "#2e5aa8" : "rgba(45,49,66,0.22)"));
        card.setAttribute("stroke-width", state.focal ? "2" : "1.3");
        card.setAttribute("style", state.focal ? "filter: drop-shadow(0 3px 10px rgba(235,108,54,0.14));" : "filter: drop-shadow(0 2px 6px rgba(0,0,0,0.03));");
        g.appendChild(card);

        // Double border for final states
        if (isFinal) {
          const innerBorder = document.createElementNS("http://www.w3.org/2000/svg", "rect");
          innerBorder.setAttribute("x", state.x + 4);
          innerBorder.setAttribute("y", state.y + 4);
          innerBorder.setAttribute("width", stateW - 8);
          innerBorder.setAttribute("height", stateH - 8);
          innerBorder.setAttribute("rx", "7");
          innerBorder.setAttribute("fill", "none");
          innerBorder.setAttribute("stroke", "rgba(45,49,66,0.25)");
          innerBorder.setAttribute("stroke-width", "1");
          g.appendChild(innerBorder);
        }

        // State ID Badge
        const idChip = document.createElementNS("http://www.w3.org/2000/svg", "rect");
        idChip.setAttribute("x", state.x + 10);
        idChip.setAttribute("y", state.y + 7);
        idChip.setAttribute("width", "20");
        idChip.setAttribute("height", "13");
        idChip.setAttribute("rx", "3");
        idChip.setAttribute("fill", state.focal ? "#eb6c36" : "rgba(45,49,66,0.08)");
        g.appendChild(idChip);

        const idText = document.createElementNS("http://www.w3.org/2000/svg", "text");
        idText.setAttribute("x", state.x + 20);
        idText.setAttribute("y", state.y + 16.5);
        idText.setAttribute("text-anchor", "middle");
        idText.setAttribute("font-family", "'Geist Mono', monospace");
        idText.setAttribute("font-size", "7.5");
        idText.setAttribute("font-weight", "600");
        idText.setAttribute("fill", state.focal ? "#ffffff" : "#4f5d75");
        idText.textContent = state.id;
        g.appendChild(idText);

        // State Type Indicator
        if (isInitial || isFinal) {
          const typeTxt = document.createElementNS("http://www.w3.org/2000/svg", "text");
          typeTxt.setAttribute("x", state.x + stateW - 12);
          typeTxt.setAttribute("y", state.y + 16.5);
          typeTxt.setAttribute("text-anchor", "end");
          typeTxt.setAttribute("font-family", "'Geist Mono', monospace");
          typeTxt.setAttribute("font-size", "7.5");
          typeTxt.setAttribute("fill", isInitial ? "#2e5aa8" : "#7a8399");
          typeTxt.textContent = isInitial ? "INIT" : "FINAL";
          g.appendChild(typeTxt);
        }

        // State Name
        const name = document.createElementNS("http://www.w3.org/2000/svg", "text");
        name.setAttribute("x", state.x + stateW / 2);
        name.setAttribute("y", state.y + 36);
        name.setAttribute("text-anchor", "middle");
        name.setAttribute("font-family", "'Geist', sans-serif");
        name.setAttribute("font-size", "11.5");
        name.setAttribute("font-weight", state.focal ? "700" : "600");
        name.setAttribute("fill", state.focal ? "#eb6c36" : "#232733");
        name.textContent = state.name;
        g.appendChild(name);

        statesGroup.appendChild(g);
      });
      svg.appendChild(statesGroup);
    }