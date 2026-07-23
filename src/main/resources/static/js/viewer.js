/*
 * viewer.js - online viewer for ASCII/CSV files served by OpenProteo.
 * Server mode (api+path) streams data so the browser never loads millions of rows.
 * No literal newline escapes in source (UBS proxy safe).
 */
(function (global) {
    'use strict';
    var LF = String.fromCharCode(10);
    var CR = 13;
    var COLW = 170;

    function el(tag, cls, parent) { var e = document.createElement(tag); if (cls) e.className = cls; if (parent) parent.appendChild(e); return e; }
    function text(n, s) { n.textContent = (s == null ? '' : String(s)); return n; }
    function fmt(n) { if (n < 1024) return n + ' B'; if (n < 1048576) return (n / 1024).toFixed(1) + ' KB'; return (n / 1048576).toFixed(1) + ' MB'; }
    function json(r) { return r.json(); }
    function showErr(host, msg) { var d = el('div', 'banner error', host); text(d, 'Cannot load: ' + (msg || 'error')); }

    function virtualList(scrollEl, rowHeight, getCount, renderRow) {
        scrollEl.innerHTML = ''; scrollEl.style.position = 'relative';
        var spacer = el('div', null, scrollEl); spacer.style.position = 'relative'; spacer.style.width = '1px';
        var pool = el('div', null, scrollEl); pool.style.position = 'absolute'; pool.style.left = '0'; pool.style.right = '0'; pool.style.top = '0';
        var ticking = false, onDraw = null;
        function draw() {
            ticking = false;
            var count = getCount();
            spacer.style.height = (count * rowHeight) + 'px';
            var st = scrollEl.scrollTop, h = scrollEl.clientHeight;
            var start = Math.max(0, Math.floor(st / rowHeight) - 6);
            var end = Math.min(count, Math.ceil((st + h) / rowHeight) + 6);
            pool.style.transform = 'translateY(' + (start * rowHeight) + 'px)';
            pool.innerHTML = '';
            for (var i = start; i < end; i++) { var r = renderRow(i); r.style.height = rowHeight + 'px'; pool.appendChild(r); }
            if (onDraw) onDraw(scrollEl.scrollLeft);
        }
        scrollEl.addEventListener('scroll', function () { if (!ticking) { ticking = true; requestAnimationFrame(draw); } });
        return { redraw: draw, reset: function () { scrollEl.scrollTop = 0; draw(); }, onDraw: function (cb) { onDraw = cb; } };
    }

    /* "go to line/row" box: scrolls a virtualised body to a 1-based index and highlights it. */
    function gotoBox(tools, body, rowH, getMax, label, onGo) {
        var wrap = el('span', 'vwr-goto', tools);
        var inp = el('input', 'goto-in', wrap); inp.type = 'text'; inp.placeholder = label || 'go to line\u2026';
        var btn = el('button', 'btn sm', wrap); text(btn, '\u21B4 Go');
        function go() {
            var n = parseInt(inp.value, 10);
            if (!n || n < 1) return;
            var max = getMax(); if (max && n > max) n = max;
            body.scrollTop = (n - 1) * rowH;
            if (onGo) onGo(n);
        }
        btn.addEventListener('click', go);
        inp.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); go(); } });
        return inp;
    }

    /* Line-numbered virtual renderer shared by TXT and formatted JSON/XML. */
    function renderLines(body, getLines, hlRef) {
        var vl = virtualList(body, 19, function () { return getLines().length; }, function (i) {
            var lines = getLines();
            var row = document.createElement('div'); row.className = 'tline' + (hlRef && hlRef.n === i + 1 ? ' hl' : '');
            var g = el('span', 'tgutter', row); text(g, i + 1);
            g.style.minWidth = (String(lines.length).length + 1) + 'ch';
            var c = el('span', 'tcontent', row); c.textContent = lines[i] === '' ? ' ' : lines[i];
            return row;
        });
        vl.redraw();
        return vl;
    }

    function renderCsvServer(host, api, path, name, meta) {
        var PAGE = 200;
        var columns = [], displayNames = [], totalRows = 0, curTotal = 0, q = '';
        var cache = {}, inflight = {};
        fetch(api + 'csv/meta?path=' + encodeURIComponent(path)).then(json).then(function (j) {
            if (!j.ok) { showErr(host, j.error); return; }
            columns = j.columns; displayNames = j.displayNames || []; totalRows = j.totalRows; curTotal = totalRows;
            meta.textContent = totalRows + ' rows · ' + columns.length + ' cols · delim "' + j.delimiter + '"';
            build();
        }).catch(function (e) { showErr(host, String(e)); });

        function build() {
            var top = el('div', 'vwr-tools', host);
            var filter = el('input', 'search-box', top); filter.placeholder = '🔎 Filter (all columns)…';
            var info = el('span', 'dim small', top); info.style.marginLeft = '10px';
            var ranges = [], sortCol = -1, sortDesc = false, sortArrows = [];
            var rtop = el('div', 'vwr-tools', host);
            var colSel = el('select', 'search-box', rtop); colSel.style.maxWidth = '240px';
            var o0 = el('option', null, colSel); o0.value = ''; text(o0, '— column —');
            columns.forEach(function (cn, ci) { var o = el('option', null, colSel); o.value = String(ci); text(o, displayNames[ci] ? (displayNames[ci] + ' \u2014 ' + cn) : cn); });
            var fromIn = el('input', 'search-box', rtop); fromIn.placeholder = 'from'; fromIn.style.maxWidth = '140px';
            var toIn = el('input', 'search-box', rtop); toIn.placeholder = 'to'; toIn.style.maxWidth = '140px';
            var addBtn = el('button', 'btn sm', rtop); text(addBtn, '+ Add range');
            var chips = el('div', 'vwr-tools', host);
            function renderChips() {
                chips.innerHTML = '';
                ranges.forEach(function (rg, idx) {
                    var ch = el('span', 'ms-chip', chips);
                    text(ch, (displayNames[rg.col] || columns[rg.col]) + ': ' + (rg.from || '*') + ' .. ' + (rg.to || '*') + ' ');
                    var x = el('span', 'ms-x', ch); text(x, '✕');
                    x.addEventListener('click', function () { ranges.splice(idx, 1); renderChips(); applyParams(); });
                });
            }
            addBtn.addEventListener('click', function () {
                if (colSel.value === '') return;
                var fr = fromIn.value.trim(), to = toIn.value.trim();
                if (!fr && !to) return;
                ranges.push({ col: +colSel.value, from: fr, to: to });
                fromIn.value = ''; toIn.value = ''; renderChips(); applyParams();
            });
            toIn.addEventListener('keydown', function (e) { if (e.key === 'Enter') addBtn.click(); });
            function paramsSuffix() {
                var s = '';
                if (q) s += '&q=' + encodeURIComponent(q);
                for (var i = 0; i < ranges.length; i++) s += '&fc=' + ranges[i].col + '&ff=' + encodeURIComponent(ranges[i].from) + '&ft=' + encodeURIComponent(ranges[i].to);
                if (sortCol >= 0) s += '&sortCol=' + sortCol + '&sortDir=' + (sortDesc ? 'desc' : 'asc');
                return s;
            }
            function active() { return !!q || ranges.length > 0 || sortCol >= 0; }
            function applyParams() {
                cache = {}; inflight = {};
                info.textContent = active() ? 'working…' : '';
                fetch(api + 'csv/page?path=' + encodeURIComponent(path) + '&offset=0&limit=' + PAGE + paramsSuffix()).then(json).then(function (j) {
                    if (j.ok) {
                        cache[0] = j.rows;
                        curTotal = active() ? j.total : totalRows;
                        info.textContent = ((q || ranges.length) ? (j.total + ' / ' + totalRows + ' rows') : '') + (j.sortTruncated ? '  (sorted first 300k)' : '');
                        vl.reset();
                    } else { info.textContent = j.error || 'error'; }
                }).catch(function (e) { info.textContent = 'error: ' + e; });
            }
            function updateSortArrows() { for (var i = 0; i < sortArrows.length; i++) sortArrows[i].textContent = (i === sortCol ? (sortDesc ? ' ▼' : ' ▲') : ''); }
            var tabs = el('div', 'vwr-tabs', host);
            var tT = el('button', 'vtab active', tabs); text(tT, 'Table');
            var tA = el('button', 'vtab', tabs); text(tA, 'Aggregate');
            var paneT = el('div', null, host);
            var paneA = el('div', null, host); paneA.style.display = 'none';
            tT.addEventListener('click', function () { tT.classList.add('active'); tA.classList.remove('active'); paneT.style.display = ''; paneA.style.display = 'none'; });
            tA.addEventListener('click', function () { tA.classList.add('active'); tT.classList.remove('active'); paneA.style.display = ''; paneT.style.display = 'none'; });

            var colW = []; for (var ci = 0; ci < columns.length; ci++) colW.push(COLW);
            var GW = 66, hlRow = { n: 0 };   // row-number gutter width / highlighted row
            function rowWidthPx() { var w = GW; for (var i = 0; i < colW.length; i++) w += colW[i]; return w; }

            var head = el('div', 'vgrid-head', paneT);
            var hr = el('div', 'vgrid-row', head); hr.style.width = rowWidthPx() + 'px';
            var hnum = el('div', 'vgrid-cell head vnum', hr); hnum.style.width = GW + 'px'; text(hnum, '#');
            for (var c = 0; c < columns.length; c++) {
                var hc = el('div', 'vgrid-cell head', hr); hc.style.width = colW[c] + 'px'; hc.style.cursor = 'pointer';
                var dnm = displayNames[c];
                var lbl = el('span', 'hcol', hc);
                if (dnm) { var d1 = el('div', null, lbl); d1.style.fontWeight = '600'; text(d1, dnm); var d2 = el('div', 'dim small', lbl); text(d2, columns[c]); hc.title = dnm + '  (' + columns[c] + ')'; }
                else { text(lbl, columns[c]); }
                var arr = el('span', 'sortarr', hc); sortArrows.push(arr);
                (function (idx, cell) {
                    cell.addEventListener('click', function (e) {
                        if (e.target && e.target.className === 'vgrid-resizer') return;   // ignore the resize handle
                        if (sortCol !== idx) { sortCol = idx; sortDesc = false; }
                        else if (!sortDesc) { sortDesc = true; }
                        else { sortCol = -1; sortDesc = false; }
                        updateSortArrows(); applyParams();
                    });
                })(c, hc);
                addResizer(hc, c);
            }
            var body = el('div', 'vwr-body vgrid', paneT);

            // drag a column's right edge to resize it; widths persist across virtual re-renders
            function addResizer(headCell, idx) {
                var rz = el('div', 'vgrid-resizer', headCell);
                rz.addEventListener('mousedown', function (e) {
                    e.preventDefault(); e.stopPropagation();
                    var startX = e.clientX, startW = colW[idx], pending = false;
                    document.body.style.cursor = 'col-resize';
                    function onMove(ev) {
                        var w = startW + (ev.clientX - startX);
                        if (w < 40) w = 40;
                        colW[idx] = w;
                        headCell.style.width = w + 'px';
                        hr.style.width = rowWidthPx() + 'px';
                        if (!pending) { pending = true; requestAnimationFrame(function () { pending = false; vl.redraw(); }); }
                    }
                    function onUp() {
                        document.removeEventListener('mousemove', onMove, true);
                        document.removeEventListener('mouseup', onUp, true);
                        document.body.style.cursor = '';
                        vl.redraw();
                    }
                    document.addEventListener('mousemove', onMove, true);
                    document.addEventListener('mouseup', onUp, true);
                });
            }

            var vl = virtualList(body, 26, function () { return curTotal; }, function (i) {
                var pg = Math.floor(i / PAGE);
                var rows = cache[pg];
                var rd = document.createElement('div'); rd.className = 'vgrid-row' + (i % 2 ? ' odd' : ''); rd.style.width = rowWidthPx() + 'px';
                var row = rows ? rows[i - pg * PAGE] : null;
                if (hlRow.n === i + 1) rd.className += ' hl';
                var nc = document.createElement('div'); nc.className = 'vgrid-cell vnum'; nc.style.width = GW + 'px'; nc.textContent = String(i + 1); rd.appendChild(nc);
                for (var c2 = 0; c2 < columns.length; c2++) { var cell = document.createElement('div'); cell.className = 'vgrid-cell'; cell.style.width = colW[c2] + 'px'; cell.textContent = row ? (row[c2] == null ? '' : row[c2]) : ''; rd.appendChild(cell); }
                if (!rows) ensure(pg);
                return rd;
            });
            gotoBox(top, body, 26, function () { return curTotal; }, 'go to row\u2026',
                    function (n) { hlRow.n = n; vl.redraw(); });
            vl.onDraw(function (left) { head.scrollLeft = left; });
            body.addEventListener('scroll', function () { head.scrollLeft = body.scrollLeft; });
            function ensure(pg) {
                if (cache[pg] || inflight[pg]) return; inflight[pg] = true;
                var url = api + 'csv/page?path=' + encodeURIComponent(path) + '&offset=' + (pg * PAGE) + '&limit=' + PAGE + paramsSuffix();
                fetch(url).then(json).then(function (j) { delete inflight[pg]; if (j.ok) { cache[pg] = j.rows; if (active()) curTotal = j.total; vl.redraw(); } }).catch(function () { delete inflight[pg]; });
            }
            vl.redraw();
            var deb;
            filter.addEventListener('input', function () {
                clearTimeout(deb);
                deb = setTimeout(function () { q = filter.value.trim(); applyParams(); }, 300);
            });
            buildAgg(paneA, api, path, name, columns, function () { return q; }, function () { return ranges; });
        }
    }

    // Searchable, multi-select dropdown of columns (no external libs). Returns selected column indices.
    function makeMultiSelect(columns, placeholder) {
        var selected = {};
        var root = el('div', 'ms');
        var box = el('div', 'ms-box', root);
        var input = el('input', 'ms-input', box);
        input.placeholder = placeholder || 'search…';
        var panel = el('div', 'ms-panel', root); panel.style.display = 'none';
        var rows = [];
        function renderChips() {
            Array.prototype.slice.call(box.querySelectorAll('.ms-chip')).forEach(function (c) { box.removeChild(c); });
            Object.keys(selected).forEach(function (ci) {
                var chip = el('span', 'ms-chip'); chip.textContent = columns[+ci];
                var x = el('span', 'ms-x', chip); x.textContent = '×';
                x.addEventListener('click', function (e) { e.stopPropagation(); delete selected[ci]; sync(); });
                box.insertBefore(chip, input);
            });
        }
        function filter() {
            var q = (input.value || '').toLowerCase();
            rows.forEach(function (r) { r.el.style.display = (!q || r.name.toLowerCase().indexOf(q) >= 0) ? '' : 'none'; });
        }
        function sync() { renderChips(); rows.forEach(function (r) { r.ck.textContent = selected[r.idx] ? '☑' : '☐'; }); }
        columns.forEach(function (cn, ci) {
            var row = el('div', 'ms-row', panel);
            var ck = el('span', 'ms-ck', row); ck.textContent = '☐';
            text(el('span', null, row), cn);
            row.addEventListener('click', function () { if (selected[ci]) delete selected[ci]; else selected[ci] = true; sync(); input.focus(); filter(); });
            rows.push({ idx: String(ci), name: cn, el: row, ck: ck });
        });
        function open() { panel.style.display = ''; filter(); }
        function close() { panel.style.display = 'none'; }
        input.addEventListener('focus', open);
        input.addEventListener('input', function () { open(); filter(); });
        box.addEventListener('click', function () { input.focus(); });
        document.addEventListener('click', function (e) { if (!root.contains(e.target)) close(); });
        return {
            root: root,
            getSelected: function () { return Object.keys(selected); },
            count: function () { return Object.keys(selected).length; }
        };
    }

    function buildAgg(pane, api, path, name, columns, getQ, getRanges) {
        var PIVOT_MAX = 30;
        var bar = el('div', 'vwr-tools agg', pane);
        text(el('div', 'dim small', bar), 'Group by (count of rows per combination):');
        var grpMS = makeMultiSelect(columns, 'search & select group columns…'); bar.appendChild(grpMS.root);
        text(el('div', 'dim small', bar), 'Distinct count of (no group selected = global distinct per column):');
        var dstMS = makeMultiSelect(columns, 'search & select distinct columns…'); bar.appendChild(dstMS.root);
        var pvRow = el('div', null, bar);
        text(el('span', 'dim small', pvRow), 'Pivot by (optional): ');
        var pivotSel = el('select', null, pvRow);
        var o0 = el('option', null, pivotSel); o0.value = ''; text(o0, '— none —');
        columns.forEach(function (cn, ci) { var o = el('option', null, pivotSel); o.value = String(ci); text(o, cn); });
        text(el('span', 'dim small', pvRow), '  (tick exactly one "Distinct count of" column to fill cells with distinct counts instead of row counts)');

        var txRow = el('div', null, bar); txRow.style.marginTop = '4px';
        text(el('span', 'dim small', txRow), 'Substring (optional): ');
        var txInput = el('input', null, txRow);
        txInput.placeholder = 'DT_EOR=L4, CODE=L2  (L=left, R=right N chars — e.g. year, prefix)';
        txInput.style.width = '420px';

        var go = el('button', 'btn sm primary', bar); text(go, 'Compute'); go.style.marginTop = '6px';
        var dl = el('button', 'btn sm', bar); text(dl, '⬇ Export'); dl.style.marginLeft = '6px'; dl.style.marginTop = '6px'; dl.disabled = true;
        var summary = el('div', 'dim small', pane); summary.style.margin = '6px 0';
        var head = el('div', 'vgrid-head', pane);
        var body = el('div', 'vwr-body vgrid', pane);
        var exportRows = null;   // array of arrays (header + TOTAL + data) for CSV export

        function txMap() {
            var m = {};
            (txInput.value || '').split(',').forEach(function (pair) {
                var eq = pair.indexOf('=');
                if (eq < 0) return;
                var name = pair.slice(0, eq).trim().toLowerCase();
                var spec = pair.slice(eq + 1).trim();
                if (name && /^[LRlr][0-9]+$/.test(spec)) m[name] = spec.toUpperCase();
            });
            return m;
        }
        function specFor(idx, map) {
            var nm = (columns[+idx] || '').trim().toLowerCase();
            return map[nm] ? (idx + ':' + map[nm]) : String(idx);
        }

        go.addEventListener('click', function () {
            var g = grpMS.getSelected(), dd = dstMS.getSelected();
            var pv = pivotSel.value;
            var map = txMap();
            var distinctMode = false, distinctCol = null;
            if (pv !== '') {
                g = g.filter(function (x) { return String(x) !== pv; });
                if (dd.length >= 1) { distinctMode = true; distinctCol = dd[0]; }   // distinct-per-cell
                dd = distinctMode ? [distinctCol] : [];
            }
            if (!g.length && !dd.length && pv === '') { summary.textContent = 'Select at least one Group-by or Distinct column.'; return; }
            summary.textContent = 'Computing on the server…';
            var reqGroup = (pv !== '' ? g.concat([pv]) : g).map(function (x) { return specFor(x, map); });
            var reqDist = dd.map(function (x) { return specFor(x, map); });
            var url = api + 'csv/aggregate?path=' + encodeURIComponent(path) +
                (reqGroup.length ? ('&group=' + reqGroup.join(',')) : '') +
                (reqDist.length ? ('&distinct=' + reqDist.join(',')) : '') +
                (getQ() ? ('&q=' + encodeURIComponent(getQ())) : '');
            var rgs = (typeof getRanges === 'function') ? (getRanges() || []) : [];
            for (var ri = 0; ri < rgs.length; ri++) url += '&fc=' + rgs[ri].col + '&ff=' + encodeURIComponent(rgs[ri].from) + '&ft=' + encodeURIComponent(rgs[ri].to);
            fetch(url).then(json).then(function (j) {
                if (!j.ok) { summary.textContent = 'ERROR: ' + (j.error || 'failed'); return; }
                summary.textContent = j.distinctGroups + ' distinct group(s) over ' + j.scanned + ' rows' +
                    (j.truncated ? ' (groups truncated)' : '') + (j.truncatedDistinct ? ' (distinct counts are lower bounds: cap reached)' : '');
                dl.disabled = false;
                if (pv !== '') renderPivot(j, distinctMode); else renderFlat(j);
            }).catch(function (e) { summary.textContent = 'Error: ' + e; });
        });

        function gridRow(parent, w, odd) {
            var rd = document.createElement('div');
            rd.className = 'vgrid-row' + (odd ? ' odd' : '');
            rd.style.width = w + 'px';
            if (parent) parent.appendChild(rd);
            return rd;
        }
        function cellEl(parent, w, cls, content) {
            var c = el('div', 'vgrid-cell' + (cls ? (' ' + cls) : ''), parent);
            c.style.width = w + 'px';
            c.textContent = content == null ? '' : content;
            return c;
        }
        function syncScroll(vl) {
            vl.onDraw(function (left) { head.scrollLeft = left; });
            body.addEventListener('scroll', function () { head.scrollLeft = body.scrollLeft; });
            vl.reset();
        }

        // ---- flat (group-by / distinct-only) rendering with a pinned TOTAL row ----
        function renderFlat(j) {
            var gc = j.groupColumns, dc = j.distinctColumns;
            var totalW = gc.length * COLW + 110 + dc.length * 130;
            head.innerHTML = '';
            var hr = gridRow(head, totalW, false);
            gc.forEach(function (cn) { cellEl(hr, COLW, 'head', cn); });
            cellEl(hr, 110, 'head num', 'COUNT');
            dc.forEach(function (cn) { cellEl(hr, 130, 'head num', 'DISTINCT ' + cn); });

            // totals pinned under the headers
            var tr = gridRow(head, totalW, false);
            tr.className = 'vgrid-row totals';
            gc.forEach(function (cn, i) { cellEl(tr, COLW, null, i === 0 ? 'TOTAL' : ''); });
            if (!gc.length) { /* distinct-only: the single data row IS the total */ }
            cellEl(tr, 110, 'num', j.totalCount);
            dc.forEach(function (cn, k) { cellEl(tr, 130, 'num', j.totalDistinct ? j.totalDistinct[k] : ''); });

            var groups = gc.length ? j.groups : [];   // distinct-only: totals row says it all
            var vl = virtualList(body, 26, function () { return groups.length; }, function (i) {
                var g = groups[i];
                var rd = gridRow(null, totalW, i % 2 === 1);
                for (var k = 0; k < gc.length; k++) cellEl(rd, COLW, null, g.vals[k]);
                cellEl(rd, 110, 'num', g.count);
                if (g.distinct) for (var d2 = 0; d2 < g.distinct.length; d2++) cellEl(rd, 130, 'num dim', g.distinct[d2]);
                return rd;
            });
            syncScroll(vl);

            exportRows = [];
            var hdr = gc.slice(); hdr.push('COUNT');
            dc.forEach(function (c) { hdr.push('DISTINCT_' + c); });
            exportRows.push(hdr);
            var trow = []; gc.forEach(function (c, i) { trow.push(i === 0 ? 'TOTAL' : ''); });
            trow.push(j.totalCount);
            dc.forEach(function (c, k) { trow.push(j.totalDistinct ? j.totalDistinct[k] : ''); });
            exportRows.push(trow);
            j.groups.forEach(function (g) {
                var row = []; for (var k = 0; k < gc.length; k++) row.push(g.vals[k]);
                row.push(g.count);
                if (g.distinct) g.distinct.forEach(function (x) { row.push(x); });
                exportRows.push(row);
            });
        }

        // ---- pivot rendering: rows = group combo, columns = pivot values (top N + OTHER) ----
        function renderPivot(j, distinctMode) {
            var gc = j.groupColumns;
            var nG = gc.length - 1;                 // last group column is the pivot
            var pivotName = gc[nG];
            var SEPC = String.fromCharCode(1);
            var distinctName = distinctMode && j.distinctColumns && j.distinctColumns.length ? j.distinctColumns[0] : null;
            function cellVal(g) { return distinctMode ? (g.distinct ? g.distinct[0] : 0) : g.count; }
            var DASH = '–';

            var pvTotals = {}, pvOrder = [];
            j.groups.forEach(function (g) {
                var v = g.vals[nG];
                if (!(v in pvTotals)) { pvTotals[v] = 0; pvOrder.push(v); }
                pvTotals[v] += g.count;             // ordering always by row count
            });
            pvOrder.sort(function (a, b) { return pvTotals[b] - pvTotals[a]; });
            var shown = pvOrder.slice(0, PIVOT_MAX);
            var hasOther = pvOrder.length > shown.length;
            var nCols = shown.length + (hasOther ? 1 : 0);
            var shownIdx = {};
            shown.forEach(function (v, i) { shownIdx[v] = i; });

            var rowMap = {}, rows = [];
            j.groups.forEach(function (g) {
                var key = nG ? g.vals.slice(0, nG).join(SEPC) : 'ALL';
                var r = rowMap[key];
                if (!r) {
                    r = { vals: nG ? g.vals.slice(0, nG) : ['ALL'], cells: [], total: 0, other: 0, otherDirty: false };
                    for (var k = 0; k < nCols; k++) r.cells.push(0);
                    rowMap[key] = r; rows.push(r);
                }
                var v = g.vals[nG];
                var ci = (v in shownIdx) ? shownIdx[v] : (hasOther ? shown.length : -1);
                if (ci >= 0) { r.cells[ci] += cellVal(g); if (ci === shown.length) r.otherDirty = true; }
                r.total += g.count;                 // row total = row count (always summable)
            });
            rows.sort(function (a, b) { return b.total - a.total; });
            var colTotals = []; for (var k = 0; k < nCols; k++) colTotals.push(0);
            var grand = 0;
            rows.forEach(function (r) { grand += r.total; for (var k = 0; k < nCols; k++) colTotals[k] += r.cells[k]; });

            var labelCols = nG ? gc.slice(0, nG) : ['ROWS'];
            var colNames = shown.slice(); if (hasOther) colNames.push('OTHER');
            var PVW = 110;
            var totalW = labelCols.length * COLW + 100 + nCols * PVW;

            head.innerHTML = '';
            var hr = gridRow(head, totalW, false);
            labelCols.forEach(function (cn) { cellEl(hr, COLW, 'head', cn); });
            cellEl(hr, 100, 'head num', distinctMode ? 'ROWS' : 'TOTAL');
            colNames.forEach(function (cn) { var c = cellEl(hr, PVW, 'head num', cn); c.setAttribute('title', pivotName + ' = ' + cn); });

            var tr = gridRow(head, totalW, false);
            tr.className = 'vgrid-row totals';
            labelCols.forEach(function (cn, i) { cellEl(tr, COLW, null, i === 0 ? 'TOTAL' : ''); });
            cellEl(tr, 100, 'num', grand);
            colTotals.forEach(function (t, k) {
                // distinct is not additive: an OTHER bucket or a column total would double-count
                var v = distinctMode ? DASH : t;
                cellEl(tr, PVW, 'num', v);
            });

            summary.textContent += '  ·  pivot ' + pivotName + ': ' + pvOrder.length + ' value(s)' +
                (hasOther ? (', showing top ' + shown.length + ' + OTHER') : '') +
                (distinctMode ? ('  ·  cells = distinct ' + distinctName + ' (OTHER/totals not summable → ' + DASH + ')') : '');

            var vl = virtualList(body, 26, function () { return rows.length; }, function (i) {
                var r = rows[i];
                var rd = gridRow(null, totalW, i % 2 === 1);
                for (var k = 0; k < labelCols.length; k++) cellEl(rd, COLW, null, r.vals[k]);
                cellEl(rd, 100, 'num', r.total);
                for (var k2 = 0; k2 < nCols; k2++) {
                    var isOther = hasOther && k2 === shown.length;
                    var val = (distinctMode && isOther) ? DASH : (r.cells[k2] || (distinctMode ? '' : ''));
                    cellEl(rd, PVW, 'num dim', val === 0 ? '' : val);
                }
                return rd;
            });
            syncScroll(vl);

            exportRows = [];
            var hdr = labelCols.slice(); hdr.push(distinctMode ? 'ROWS' : 'TOTAL');
            colNames.forEach(function (c) { hdr.push(pivotName + '=' + c + (distinctMode ? (' (distinct ' + distinctName + ')') : '')); });
            exportRows.push(hdr);
            var trow = []; labelCols.forEach(function (c, i) { trow.push(i === 0 ? 'TOTAL' : ''); });
            trow.push(grand); colTotals.forEach(function (t) { trow.push(distinctMode ? '' : t); });
            exportRows.push(trow);
            rows.forEach(function (r) {
                var row = r.vals.slice(); row.push(r.total);
                r.cells.forEach(function (c) { row.push(c); });
                exportRows.push(row);
            });
        }

        dl.addEventListener('click', function () {
            if (!exportRows) return;
            var out = [];
            exportRows.forEach(function (r) { out.push(r.map(field).join(';')); });
            var blob = new Blob([String.fromCharCode(0xFEFF) + out.join(LF)], { type: 'text/csv;charset=utf-8' });
            var a = document.createElement('a'); a.href = URL.createObjectURL(blob);
            a.download = (name.replace(/\.[^.]+$/, '') || 'aggregation') + '_counts.csv'; a.click();
            setTimeout(function () { URL.revokeObjectURL(a.href); }, 2000);
        });
        function field(s) { s = (s == null ? '' : String(s)); if (s.indexOf(';') >= 0 || s.indexOf('"') >= 0 || s.charCodeAt(0) === CR || s.indexOf(LF) >= 0) return '"' + s.replace(/"/g, '""') + '"'; return s; }
    }

    // ---- pretty-printing for JSON / XML (inline or unformatted files) ----
    function formatJsonStr(s) {
        var obj = JSON.parse(s);
        return JSON.stringify(obj, null, 2);
    }
    function formatXmlStr(s) {
        var NL = String.fromCharCode(10);
        var doc;
        try { doc = new DOMParser().parseFromString(s, 'application/xml'); }
        catch (e) { return null; }
        if (!doc || !doc.documentElement) return null;
        if (doc.getElementsByTagName('parsererror').length || doc.documentElement.nodeName === 'parsererror') return null;
        function escAttr(v) { return String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;'); }
        function escText(v) { return String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
        function ser(node, depth) {
            var pad = ''; for (var i = 0; i < depth; i++) pad += '  ';
            if (node.nodeType === 3) {
                var t = node.nodeValue.replace(/^\s+|\s+$/g, '');
                return t ? pad + escText(t) + NL : '';
            }
            if (node.nodeType === 8) return pad + '<!--' + node.nodeValue + '-->' + NL;
            if (node.nodeType !== 1) return '';
            var tag = node.nodeName, attrs = '';
            for (var a = 0; a < node.attributes.length; a++) {
                var at = node.attributes[a]; attrs += ' ' + at.name + '="' + escAttr(at.value) + '"';
            }
            var kids = node.childNodes, elemKids = 0, textBuf = '';
            for (var k = 0; k < kids.length; k++) {
                if (kids[k].nodeType === 1) elemKids++;
                else if (kids[k].nodeType === 3) textBuf += kids[k].nodeValue;
            }
            if (kids.length === 0) return pad + '<' + tag + attrs + '/>' + NL;
            if (elemKids === 0) {
                return pad + '<' + tag + attrs + '>' + escText(textBuf.replace(/^\s+|\s+$/g, '')) + '</' + tag + '>' + NL;
            }
            var inner = '';
            for (var c = 0; c < kids.length; c++) inner += ser(kids[c], depth + 1);
            return pad + '<' + tag + attrs + '>' + NL + inner + pad + '</' + tag + '>' + NL;
        }
        var head = '<?xml version="1.0" encoding="UTF-8"?>' + NL;
        return (head + ser(doc.documentElement, 0)).replace(/\s+$/, '');
    }
    /** Returns {ok, text, kind} or {ok:false, error}. Picks JSON/XML by extension, then by content. */
    function prettyFormat(content, fileName) {
        var nm = (fileName || '').toLowerCase();
        var tryJson = nm.indexOf('.json') >= 0;
        var tryXml = nm.indexOf('.xml') >= 0;
        if (!tryJson && !tryXml) {
            var t = content.replace(/^\s+/, '');
            if (t.charAt(0) === '{' || t.charAt(0) === '[') tryJson = true;
            else if (t.charAt(0) === '<') tryXml = true;
        }
        if (tryJson) {
            try { return { ok: true, text: formatJsonStr(content), kind: 'JSON' }; }
            catch (e) { return { ok: false, error: 'invalid JSON (' + (e.message || e) + ')' }; }
        }
        if (tryXml) {
            var x = formatXmlStr(content);
            if (x) return { ok: true, text: x, kind: 'XML' };
            return { ok: false, error: 'invalid XML' };
        }
        return { ok: false, error: 'not JSON or XML' };
    }

    function renderTextServer(host, api, path, name, src, meta) {
        var PAGE = 500, totalLines = 0, cache = {}, inflight = {}, gutterW = 6;
        var tools = el('div', 'vwr-tools', host);
        var editBtn = el('button', 'btn sm', tools); text(editBtn, '✎ Edit');
        var info = el('span', 'dim small', tools); info.style.marginLeft = '10px';
        var body = el('div', 'vwr-body mono', host);
        var isStructured = /\.(json|xml)$/i.test(name || '');
        if (isStructured) {
            var sLines = [], sHl = { n: 0 }, sVl = null;
            gotoBox(tools, body, 19, function () { return sLines.length; }, 'go to line\u2026',
                    function (n) { sHl.n = n; if (sVl) sVl.redraw(); });
            info.textContent = 'loading…';
            fetch(src).then(function (r) { return r.text(); }).then(function (content) {
                var rr = prettyFormat(content, name), txt;
                if (rr.ok) { txt = rr.text; meta.textContent = rr.kind + ' · formatted'; }
                else { txt = content; meta.textContent = '(' + rr.error + ' \u2014 raw)'; }
                sLines = txt.split(LF);
                for (var i = 0; i < sLines.length; i++) if (sLines[i].length && sLines[i].charCodeAt(sLines[i].length - 1) === CR) sLines[i] = sLines[i].slice(0, -1);
                if (sLines.length && sLines[sLines.length - 1] === '') sLines.pop();
                sVl = renderLines(body, function () { return sLines; }, sHl);
                meta.textContent += ' \u00b7 ' + sLines.length + ' lines';
                info.textContent = '';
            }).catch(function (e) { showErr(host, String(e)); });
        } else {
        fetch(api + 'text/meta?path=' + encodeURIComponent(path)).then(json).then(function (j) {
            if (!j.ok) { showErr(host, j.error); return; }
            totalLines = j.totalLines; gutterW = String(totalLines).length + 1;
            meta.textContent = totalLines + ' lines · ' + fmt(j.size); vl.redraw();
        });
        var hlLine = { n: 0 };
        gotoBox(tools, body, 19, function () { return totalLines; }, 'go to line\u2026',
                function (n) { hlLine.n = n; vl.redraw(); });
        var vl = virtualList(body, 19, function () { return totalLines; }, function (i) {
            var pg = Math.floor(i / PAGE); var rows = cache[pg];
            var row = document.createElement('div'); row.className = 'tline' + (hlLine.n === i + 1 ? ' hl' : '');
            var g = el('span', 'tgutter', row); text(g, i + 1); g.style.minWidth = gutterW + 'ch';
            var c = el('span', 'tcontent', row); c.textContent = rows ? (rows[i - pg * PAGE] === '' ? ' ' : rows[i - pg * PAGE]) : '';
            if (!rows) ensure(pg);
            return row;
        });
        function ensure(pg) {
            if (cache[pg] || inflight[pg]) return; inflight[pg] = true;
            fetch(api + 'text/page?path=' + encodeURIComponent(path) + '&offset=' + (pg * PAGE) + '&limit=' + PAGE).then(json).then(function (j) { delete inflight[pg]; if (j.ok) { cache[pg] = j.lines; vl.redraw(); } }).catch(function () { delete inflight[pg]; });
        }
        }
        editBtn.addEventListener('click', function () {
            info.textContent = 'loading for edit…';
            fetch(src).then(function (r) { return r.text(); }).then(function (content) {
                body.style.display = 'none';
                var ed = el('div', 'file-editor', host);
                var ta = el('textarea', 'file-editor-area', ed); ta.value = content;
                var fmtBtn = el('button', 'btn sm', ed); text(fmtBtn, '⤓ Format'); fmtBtn.style.marginRight = '6px';
                fmtBtn.title = 'pretty-print JSON / XML (for inline or unformatted files)';
                var save = el('button', 'btn sm primary', ed); text(save, '💾 Save');
                var cancel = el('button', 'btn sm', ed); text(cancel, 'Cancel'); cancel.style.marginLeft = '6px';
                editBtn.disabled = true; info.textContent = '';
                fmtBtn.addEventListener('click', function () {
                    var r = prettyFormat(ta.value, name);
                    if (r.ok) { ta.value = r.text; info.textContent = 'formatted as ' + r.kind; }
                    else { info.textContent = 'cannot format: ' + r.error; }
                });
                cancel.addEventListener('click', function () { ed.remove(); body.style.display = ''; editBtn.disabled = false; });
                save.addEventListener('click', function () {
                    var fd = new FormData(); fd.append('path', path); fd.append('content', ta.value);
                    fetch(api + 'files/save', { method: 'POST', body: fd }).then(json).then(function (j) {
                        if (j.ok) { window.location.reload(); } else { info.textContent = 'save failed: ' + (j.error || ''); }
                    });
                });
            });
        });
    }

    function renderTextClient(host, content, name, meta) {
        var lines = content.split(LF);
        if (lines.length && lines[lines.length - 1] === '') lines.pop();
        for (var i = 0; i < lines.length; i++) if (lines[i].length && lines[i].charCodeAt(lines[i].length - 1) === CR) lines[i] = lines[i].slice(0, -1);
        meta.textContent = lines.length + ' lines · ' + fmt(content.length);
        var body = el('div', 'vwr-body mono', host);
        var gw = String(lines.length).length + 1;
        virtualList(body, 19, function () { return lines.length; }, function (i) {
            var row = document.createElement('div'); row.className = 'tline';
            var g = el('span', 'tgutter', row); text(g, i + 1); g.style.minWidth = gw + 'ch';
            var c = el('span', 'tcontent', row); c.textContent = lines[i] === '' ? ' ' : lines[i];
            return row;
        }).redraw();
    }

    function initViewer(opts) {
        var host = opts.host; host.innerHTML = '';
        var head = el('div', 'vwr-head', host);
        var title = el('div', null, head);
        text(el('span', 'vwr-name', title), opts.name || 'file');
        var meta = el('span', 'vwr-meta dim small', title); meta.style.marginLeft = '12px';
        var dl = el('a', 'btn sm', head); text(dl, '⬇ Download'); dl.href = opts.src; dl.style.marginLeft = 'auto';
        var bodyHost = el('div', null, host);
        var isCsv = /\.(csv|tsv)$/i.test(opts.name || '');
        var server = opts.api && opts.path;
        if (server) {
            if (isCsv) renderCsvServer(bodyHost, opts.api, opts.path, opts.name || '', meta);
            else renderTextServer(bodyHost, opts.api, opts.path, opts.name || '', opts.src, meta);
        } else {
            var loading = el('div', 'dim', bodyHost); text(loading, 'Loading…');
            fetch(opts.src).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.text(); })
                .then(function (content) { bodyHost.innerHTML = ''; renderTextClient(bodyHost, content, opts.name || '', meta); })
                .catch(function (e) { bodyHost.innerHTML = ''; showErr(bodyHost, e.message); });
        }
    }
    global.initViewer = initViewer;
})(window);
