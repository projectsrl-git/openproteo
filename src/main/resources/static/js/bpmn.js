/*
 * bpmn.js - renders a workflow definition as a BPMN 2.0-style diagram in pure SVG.
 *
 * Elements:
 *   - start event   : thin circle
 *   - task          : rounded rectangle
 *   - XOR gateway   : diamond with X marker (decision gate)
 *   - end events    : thick circle, one per END:* outcome (+ implicit final end)
 *   - sequence flow : arrows; gate branches labelled yes/no; loop back-edges
 *                     routed below, forward skips routed above
 *
 * Usage:
 *   var bpmn = renderBpmn(containerEl, def);   // def = /api/workflows/{id}/definition
 *   bpmn.setStatus(nodeId, statusText, metaText);
 *
 * No external libraries. Built with createElementNS only (no string templates),
 * so there are no escape-sequence pitfalls on corporate proxies.
 */
function renderBpmn(container, def) {
    'use strict';
    var NS = 'http://www.w3.org/2000/svg';

    var TASK_W = 170, TASK_H = 74;
    var GATE_R = 46;                 // half-diagonal of the diamond
    var EVT_R = 17;                  // event circle radius
    var GAP = 86;                    // horizontal gap between nodes
    var MIDY = 130;                  // main flow lane
    var START_X = 56;

    var nodes = (def && def.nodes) ? def.nodes : [];
    var idx = {};                    // nodeId -> index
    nodes.forEach(function (n, i) { idx[n.id] = i; });

    // ---- geometry: center x of each node ----
    var cx = [];
    var x = START_X + EVT_R + GAP;
    nodes.forEach(function (n) {
        var w = (n.kind === 'GATE') ? GATE_R * 2 : TASK_W;
        cx.push(x + w / 2);
        x += w + GAP;
    });
    var afterLastX = x;

    function halfW(i) { return nodes[i].kind === 'GATE' ? GATE_R : TASK_W / 2; }
    function halfH(i) { return nodes[i].kind === 'GATE' ? GATE_R : TASK_H / 2; }

    // ---- resolve edges ----
    // edge: {fromIdx, toIdx | endNode, label}
    var edges = [];
    var endNodes = [];               // {x, y, label, key}
    var finalEnd = null;

    function endFor(i, outcome, slot) {
        // dedicated end event below the gate/step that references it
        var ex = cx[i] + (slot === 1 ? 64 : 0);
        var ey = MIDY + halfH(i) + 92;
        var e = { x: ex, y: ey, label: outcome, key: 'end_' + i + '_' + outcome };
        endNodes.push(e);
        return e;
    }
    function finalEndNode() {
        if (!finalEnd) finalEnd = { x: afterLastX + EVT_R, y: MIDY, label: 'END', key: 'end_final' };
        return finalEnd;
    }
    function resolveTarget(t, i, label, slot) {
        if (!t) {
            if (i + 1 < nodes.length) edges.push({ fromIdx: i, toIdx: i + 1, label: label });
            else edges.push({ fromIdx: i, end: finalEndNode(), label: label });
        } else if (t.indexOf('END:') === 0) {
            edges.push({ fromIdx: i, end: endFor(i, t.substring(4), slot), label: label });
        } else if (idx[t] !== undefined) {
            edges.push({ fromIdx: i, toIdx: idx[t], label: label });
        }
    }

    nodes.forEach(function (n, i) {
        if (n.kind === 'GATE') {
            resolveTarget(n.onTrue, i, 'yes', 0);
            resolveTarget(n.onFalse, i, 'no', 1);
        } else {
            if (i + 1 < nodes.length) edges.push({ fromIdx: i, toIdx: i + 1, label: null });
            else edges.push({ fromIdx: i, end: finalEndNode(), label: null });
        }
    });

    // ---- lanes for skip/back edges so they do not overlap ----
    var backLane = 0, topLane = 0;
    edges.forEach(function (e) {
        if (e.toIdx !== undefined) {
            if (e.toIdx <= e.fromIdx) { backLane++; e.lane = backLane; }
            else if (e.toIdx > e.fromIdx + 1) { topLane++; e.lane = topLane; }
        }
    });

    var height = MIDY + Math.max(halfH(0) || 0, TASK_H / 2) + 92 + 46 + backLane * 22;
    var width = afterLastX + EVT_R * 2 + 40;

    // ---- svg scaffold ----
    container.innerHTML = '';
    var svg = document.createElementNS(NS, 'svg');
    svg.setAttribute('width', width);
    svg.setAttribute('height', height);
    svg.setAttribute('class', 'bpmn');

    var defs = document.createElementNS(NS, 'defs');
    var marker = document.createElementNS(NS, 'marker');
    marker.setAttribute('id', 'bpArrow');
    marker.setAttribute('viewBox', '0 0 10 10');
    marker.setAttribute('refX', '9'); marker.setAttribute('refY', '5');
    marker.setAttribute('markerWidth', '7'); marker.setAttribute('markerHeight', '7');
    marker.setAttribute('orient', 'auto-start-reverse');
    var mpath = document.createElementNS(NS, 'path');
    mpath.setAttribute('d', 'M 0 0 L 10 5 L 0 10 z');
    mpath.setAttribute('class', 'bp-arrowhead');
    marker.appendChild(mpath);
    defs.appendChild(marker);
    var lmarker = document.createElementNS(NS, 'marker');
    lmarker.setAttribute('id', 'bpLoopArrow');
    lmarker.setAttribute('viewBox', '0 0 10 10');
    lmarker.setAttribute('refX', '9'); lmarker.setAttribute('refY', '5');
    lmarker.setAttribute('markerWidth', '8'); lmarker.setAttribute('markerHeight', '8');
    lmarker.setAttribute('orient', 'auto-start-reverse');
    var lpath = document.createElementNS(NS, 'path');
    lpath.setAttribute('d', 'M 0 0 L 10 5 L 0 10 z');
    lpath.setAttribute('class', 'bp-loop-arrowhead');
    lmarker.appendChild(lpath);
    defs.appendChild(lmarker);
    svg.appendChild(defs);

    function el(tag, attrs, parent) {
        var e = document.createElementNS(NS, tag);
        if (attrs) Object.keys(attrs).forEach(function (k) { e.setAttribute(k, attrs[k]); });
        (parent || svg).appendChild(e);
        return e;
    }
    function txt(parent, x, y, cls, content, anchor) {
        var t = el('text', { x: x, y: y, 'class': cls, 'text-anchor': anchor || 'middle' }, parent);
        t.textContent = content;
        return t;
    }
    function trunc(s, n) {
        s = s == null ? '' : String(s);
        return s.length > n ? s.substring(0, n - 1) + '…' : s;
    }
    function addCls(elm, c) { var v = elm.getAttribute('class') || ''; if (v.indexOf(c) < 0) elm.setAttribute('class', v + ' ' + c); }
    function rmCls(elm, c) { var v = elm.getAttribute('class') || ''; elm.setAttribute('class', v.replace(new RegExp('\\s*' + c + '\\b', 'g'), '')); }

    // ---- edges (drawn first, under the nodes) ----
    var edgeEls = {};                // fromId|label -> path element
    edges.forEach(function (e) {
        var fi = e.fromIdx;
        var fx = cx[fi], d;
        var labelX, labelY;
        if (e.end && e.end.key === 'end_final') {
            d = 'M ' + (fx + halfW(fi)) + ' ' + MIDY + ' L ' + (e.end.x - EVT_R) + ' ' + MIDY;
            labelX = (fx + halfW(fi) + e.end.x - EVT_R) / 2; labelY = MIDY - 8;
        } else if (e.end) {
            // drop to a dedicated end event below
            var bottom = MIDY + halfH(fi);
            if (e.end.x === fx) {
                d = 'M ' + fx + ' ' + bottom + ' L ' + fx + ' ' + (e.end.y - EVT_R);
            } else {
                var midY2 = (bottom + e.end.y) / 2;
                d = 'M ' + fx + ' ' + bottom + ' L ' + fx + ' ' + midY2 +
                    ' L ' + e.end.x + ' ' + midY2 + ' L ' + e.end.x + ' ' + (e.end.y - EVT_R);
            }
            labelX = e.end.x + 8; labelY = (bottom + e.end.y - EVT_R) / 2;
        } else if (e.toIdx === fi + 1) {
            d = 'M ' + (fx + halfW(fi)) + ' ' + MIDY + ' L ' + (cx[e.toIdx] - halfW(e.toIdx)) + ' ' + MIDY;
            labelX = (fx + halfW(fi) + cx[e.toIdx] - halfW(e.toIdx)) / 2; labelY = MIDY - 8;
        } else if (e.toIdx > fi + 1) {
            // forward skip: route above
            var laneY = MIDY - Math.max.apply(null, [TASK_H / 2, GATE_R]) - 18 - (e.lane - 1) * 20;
            d = 'M ' + fx + ' ' + (MIDY - halfH(fi)) + ' L ' + fx + ' ' + laneY +
                ' L ' + cx[e.toIdx] + ' ' + laneY + ' L ' + cx[e.toIdx] + ' ' + (MIDY - halfH(e.toIdx));
            labelX = fx + 16; labelY = laneY - 5;
        } else {
            // back-edge (loop): route below
            var laneY2 = MIDY + Math.max.apply(null, [TASK_H / 2, GATE_R]) + 26 + (e.lane - 1) * 22;
            d = 'M ' + fx + ' ' + (MIDY + halfH(fi)) + ' L ' + fx + ' ' + laneY2 +
                ' L ' + cx[e.toIdx] + ' ' + laneY2 + ' L ' + cx[e.toIdx] + ' ' + (MIDY + halfH(e.toIdx));
            labelX = fx - 16; labelY = laneY2 - 5;
        }
        var p = el('path', { d: d, 'class': 'bp-edge', 'marker-end': 'url(#bpArrow)' });
        if (e.label) {
            txt(svg, labelX, labelY, 'bp-edge-label', e.label, 'middle');
            edgeEls[nodes[fi].id + '|' + e.label] = p;
        }
    });

    // ---- start event ----
    var gStart = el('g', { 'class': 'bp-node bp-event' });
    el('circle', { cx: START_X, cy: MIDY, r: EVT_R, 'class': 'shape start' }, gStart);
    txt(gStart, START_X, MIDY + EVT_R + 16, 'bp-id', 'start');
    el('path', {
        d: 'M ' + (START_X + EVT_R) + ' ' + MIDY + ' L ' + (cx[0] - halfW(0)) + ' ' + MIDY,
        'class': 'bp-edge', 'marker-end': 'url(#bpArrow)'
    });

    // ---- nodes ----
    var nodeEls = {};
    nodes.forEach(function (n, i) {
        var loopCls = (n.kind === 'LOOP' || n.kind === 'ENDLOOP') ? ' bp-loopmarker' : '';
        var g = el('g', { 'class': 'bp-node' + loopCls, id: 'bp-' + n.id });
        var title = document.createElementNS(NS, 'title');
        title.textContent = n.name + ' [' + n.id + ']' + (n.condition ? '  —  ' + n.condition : '');
        g.appendChild(title);

        if (n.kind === 'GATE') {
            var pts = cx[i] + ',' + (MIDY - GATE_R) + ' ' + (cx[i] + GATE_R) + ',' + MIDY + ' ' +
                      cx[i] + ',' + (MIDY + GATE_R) + ' ' + (cx[i] - GATE_R) + ',' + MIDY;
            el('polygon', { points: pts, 'class': 'shape' }, g);
            // X marker = BPMN exclusive (XOR) gateway
            txt(g, cx[i], MIDY - GATE_R + 26, 'bp-gw-marker', '✕');
            txt(g, cx[i], MIDY - 4, 'bp-name', trunc(n.name, 16));
            txt(g, cx[i], MIDY + 12, 'bp-id', trunc(n.id, 18));
            var st = txt(g, cx[i], MIDY + 30, 'bp-status', 'PENDING');
            txt(g, cx[i], MIDY + GATE_R + 18, 'bp-meta',
                trunc(n.type === 'manual' ? 'manual decision' : n.condition, 30));
            nodeEls[n.id] = { g: g, status: st, meta: g.lastChild };
        } else {
            el('rect', {
                x: cx[i] - TASK_W / 2, y: MIDY - TASK_H / 2, width: TASK_W, height: TASK_H,
                rx: 9, 'class': 'shape'
            }, g);
            txt(g, cx[i], MIDY - 14, 'bp-name', trunc(n.name, 22));
            txt(g, cx[i], MIDY + 3, 'bp-id', trunc(n.id, 24));
            var st2 = txt(g, cx[i], MIDY + 23, 'bp-status', 'PENDING');
            var meta = txt(g, cx[i], MIDY + TASK_H / 2 + 16, 'bp-meta', '');
            nodeEls[n.id] = { g: g, status: st2, meta: meta, cx: cx[i], top: MIDY - TASK_H / 2, w: TASK_W, h: TASK_H };
        }
    });

    // ---- end events ----
    var finalEndEls = null;
    function drawEnd(e) {
        var g = el('g', { 'class': 'bp-node bp-event' });
        var circle = el('circle', { cx: e.x, cy: e.y, r: EVT_R, 'class': 'shape end' }, g);
        var label = txt(g, e.x, e.y + EVT_R + 16, 'bp-id', e.label);
        if (e.key === 'end_final') finalEndEls = { g: g, circle: circle, label: label };
    }
    endNodes.forEach(drawEnd);
    if (finalEnd) drawEnd(finalEnd);

    // ---- loop back-edges: ENDLOOP -> matching LOOP (by nesting), arched over the top ----
    var loops = {};   // loopNodeId -> { edge, bodyIds:[], lastIter:0, lx, ly, labelEl, badges:{} }
    (function () {
        var stack = [], pairs = [];
        nodes.forEach(function (n, i) {
            if (n.kind === 'LOOP') stack.push(i);
            else if (n.kind === 'ENDLOOP' && stack.length) pairs.push({ loop: stack.pop(), end: i, depth: stack.length });
        });
        // each body STEP/GATE belongs to its innermost enclosing loop
        var owner = {}, ostack = [];
        nodes.forEach(function (n) {
            if (n.kind === 'LOOP') ostack.push(n.id);
            else if (n.kind === 'ENDLOOP') ostack.pop();
            else if (ostack.length) owner[n.id] = ostack[ostack.length - 1];
        });
        pairs.forEach(function (pr) {
            var lx = cx[pr.loop], ex = cx[pr.end];
            var loopTop = MIDY - halfH(pr.loop);
            var endTop = MIDY - halfH(pr.end);
            var peak = (MIDY - TASK_H / 2 - 34) - pr.depth * 16;   // arch height (nested loops sit higher)
            if (peak < 8) peak = 8;
            var d = 'M ' + ex + ' ' + endTop +
                    ' C ' + ex + ' ' + peak + ' ' + lx + ' ' + peak + ' ' + lx + ' ' + loopTop;
            var edge = el('path', { d: d, 'class': 'bp-loop-edge', 'marker-end': 'url(#bpLoopArrow)' });
            txt(svg, (lx + ex) / 2, peak - 4, 'bp-loop-label', '↺ loop');
            var loopId = nodes[pr.loop].id;
            var bodyIds = [];
            nodes.forEach(function (n) { if (owner[n.id] === loopId) bodyIds.push(n.id); });
            loops[loopId] = { edge: edge, bodyIds: bodyIds, lastIter: 0, lx: lx, ly: MIDY + TASK_H / 2 + 32, badges: {}, labelEl: null };
        });
    })();

    container.appendChild(svg);

    // ---- public API ----
    return {
        // colour the implicit final END event with the run outcome (SUCCESS/FAILED)
        setFinalOutcome: function (status) {
            if (!finalEndEls) return;
            finalEndEls.g.setAttribute('class', 'bp-node bp-event st-' + status);
            finalEndEls.label.textContent = status;
        },
        // Live loop animation: show "iteration N / total" near the LOOP and a "xN" badge
        // on each body block; pulse the back-edge and flash the body when the pass advances.
        // Pass iter=0 (or count=0) to clear when the loop is no longer active.
        setLoopState: function (loopId, iter, count) {
            var lp = loops[loopId];
            if (!lp) return;
            var active = iter > 0 && count > 0;
            if (active) {
                if (!lp.labelEl) lp.labelEl = txt(svg, lp.lx, lp.ly, 'bp-loop-iter', '');
                lp.labelEl.textContent = 'iteration ' + iter + ' / ' + count;
                lp.labelEl.setAttribute('display', '');
            } else if (lp.labelEl) {
                lp.labelEl.setAttribute('display', 'none');
            }
            lp.bodyIds.forEach(function (bid) {
                var ne = nodeEls[bid];
                if (!ne || ne.cx === undefined) return;
                var b = lp.badges[bid];
                if (active) {
                    if (!b) {
                        var g = el('g', { 'class': 'bp-iterbadge' }, ne.g);
                        var bw = 30, bx = ne.cx + ne.w / 2 - bw + 4, by = ne.top - 9;
                        el('rect', { x: bx, y: by, width: bw, height: 17, rx: 8, 'class': 'bp-iterbadge-bg' }, g);
                        var t = txt(g, bx + bw / 2, by + 12, 'bp-iterbadge-tx', '');
                        b = { g: g, t: t }; lp.badges[bid] = b;
                    }
                    b.t.textContent = '\u00d7' + iter;
                    b.g.setAttribute('display', '');
                } else if (b) {
                    b.g.setAttribute('display', 'none');
                }
            });
            if (active && iter > lp.lastIter && lp.lastIter > 0) {
                addCls(lp.edge, 'pulse');
                setTimeout(function () { rmCls(lp.edge, 'pulse'); }, 900);
                lp.bodyIds.forEach(function (bid) {
                    var ne = nodeEls[bid]; if (!ne) return;
                    addCls(ne.g, 'bp-reloop');
                    setTimeout(function () { rmCls(ne.g, 'bp-reloop'); }, 650);
                });
            }
            lp.lastIter = active ? iter : 0;
        },
        // validation checklist rendered two ways on the task:
        //  (a) compact colour segments inside the rectangle, and
        //  (b) an expanded stack of labeled sub-nodes below it (real sub-steps).
        // statuses: PENDING (grey) RUNNING (accent) PASS (green) FAIL (red) SKIP (dim)
        setChecks: function (id, checks) {
            var ne = nodeEls[id];
            if (!ne || ne.cx === undefined || !checks || !checks.length) return;

            // (a) compact segments inside the box
            if (!ne.checkRects || ne.checkRects.length !== checks.length) {
                if (ne.checksG) ne.g.removeChild(ne.checksG);
                ne.checksG = el('g', { 'class': 'bp-checks' }, ne.g);
                ne.checkRects = [];
                var segW = Math.min(14, Math.floor((ne.w - 16) / checks.length) - 2);
                if (segW < 4) segW = 4;
                var totalW = checks.length * (segW + 2) - 2;
                var x0 = ne.cx - totalW / 2;
                var y0 = ne.top + ne.h - 12;
                for (var k = 0; k < checks.length; k++) {
                    var rc = el('rect', { x: x0 + k * (segW + 2), y: y0, width: segW, height: 6, rx: 1.5 }, ne.checksG);
                    rc.appendChild(document.createElementNS(NS, 'title'));
                    ne.checkRects.push(rc);
                }
            }
            for (var k2 = 0; k2 < checks.length; k2++) {
                var c = checks[k2];
                var rc2 = ne.checkRects[k2];
                rc2.setAttribute('class', 'bp-check ck-' + (c.status || 'PENDING'));
                rc2.firstChild.textContent = c.label + ' — ' + c.status + (c.detail ? (' (' + c.detail + ')') : '');
            }

            // (b) expanded sub-node stack below the task
            var SUBW = ne.w, SUBH = 18, SUBGAP = 5;
            var spineX = ne.cx - SUBW / 2 - 12;
            var leftX = ne.cx - SUBW / 2;
            var startY = ne.top + ne.h + 30;     // below the meta label
            if (!ne.subEls || ne.subEls.length !== checks.length) {
                if (ne.subG) ne.g.removeChild(ne.subG);
                ne.subG = el('g', { 'class': 'bp-subnodes' }, ne.g);
                ne.subEls = [];
                var lastY = startY + (checks.length - 1) * (SUBH + SUBGAP) + SUBH / 2;
                el('path', { d: 'M ' + ne.cx + ' ' + (ne.top + ne.h) + ' L ' + ne.cx + ' ' + (startY - 8) +
                    ' L ' + spineX + ' ' + (startY - 8) + ' L ' + spineX + ' ' + lastY, 'class': 'bp-spine' }, ne.subG);
                for (var s = 0; s < checks.length; s++) {
                    var y = startY + s * (SUBH + SUBGAP);
                    var sg = el('g', null, ne.subG);
                    el('path', { d: 'M ' + spineX + ' ' + (y + SUBH / 2) + ' L ' + leftX + ' ' + (y + SUBH / 2), 'class': 'bp-spine' }, sg);
                    var box = el('rect', { x: leftX, y: y, width: SUBW, height: SUBH, rx: 4, 'class': 'bp-subnode' }, sg);
                    var dot = el('circle', { cx: leftX + 11, cy: y + SUBH / 2, r: 4, 'class': 'bp-subdot' }, sg);
                    var tx = txt(sg, leftX + 22, y + SUBH / 2 + 4, 'bp-sublabel', '', 'start');
                    var tip = document.createElementNS(NS, 'title'); box.appendChild(tip);
                    ne.subEls.push({ box: box, dot: dot, tx: tx, tip: tip });
                }
                var needed = startY + checks.length * (SUBH + SUBGAP) + 10;
                var curH = parseFloat(svg.getAttribute('height')) || 0;
                if (needed > curH) svg.setAttribute('height', Math.ceil(needed));
            }
            for (var s2 = 0; s2 < checks.length; s2++) {
                var ck = checks[s2], se = ne.subEls[s2], stt = ck.status || 'PENDING';
                se.box.setAttribute('class', 'bp-subnode ck-' + stt);
                se.dot.setAttribute('class', 'bp-subdot ck-' + stt);
                var icon = stt === 'PASS' ? '✓ ' : stt === 'FAIL' ? '✗ ' : stt === 'RUNNING' ? '▶ ' : stt === 'SKIP' ? '⊘ ' : '';
                se.tx.textContent = icon + trunc(ck.label, 22);
                se.tip.textContent = ck.label + ' — ' + stt + (ck.detail ? (' (' + ck.detail + ')') : '');
            }
        },
        setStatus: function (id, status, meta) {
            var ne = nodeEls[id];
            if (!ne) return;
            var visual = status === 'TRUE' ? 'SUCCESS' : (status === 'FALSE' ? 'SKIPPED' : status);
            ne.g.setAttribute('class', 'bp-node st-' + visual);
            ne.status.textContent = status;
            if (ne.meta) ne.meta.textContent = trunc(meta || ne.meta.textContent, 34);
            // highlight the branch actually taken by a decided gate
            if (status === 'TRUE' || status === 'FALSE') {
                var taken = edgeEls[id + '|' + (status === 'TRUE' ? 'yes' : 'no')];
                var other = edgeEls[id + '|' + (status === 'TRUE' ? 'no' : 'yes')];
                if (taken) taken.setAttribute('class', 'bp-edge taken');
                if (other) other.setAttribute('class', 'bp-edge');
            }
        }
    };
}
