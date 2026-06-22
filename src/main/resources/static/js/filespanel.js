/*
 * filespanel.js - reusable file manager mounted into a container.
 *
 *   mountFilesPanel(container, apiBase)
 *
 * apiBase ends with '/', e.g. CTX + 'api/workflows/FEED/'  or  CTX + 'api/shared/'.
 * Endpoints used: files (GET list, POST upload), files/delete (POST),
 *                 download?path=, alias-suggest?file=
 *
 * Upload classifies each batch as "document" (txt/md/json/...) or "executable step"
 * (ps1/jar/bat/cmd). Executables require a UNIQUE alias used as ${alias} to reference
 * the script from a step; the alias is auto-proposed from the file name and validated
 * server-side. No literal newline escapes in source (UBS proxy safe).
 */
function mountFilesPanel(container, apiBase, ctx, onChange, opts) {
    var listUrl = apiBase + 'files' + (opts && opts.rootOnly ? '?root=true' : '');
    'use strict';
    function esc(s) { var d = document.createElement('div'); d.textContent = (s == null ? '' : String(s)); return d.innerHTML; }
    function viewable(path) {
        return /\.(csv|tsv|txt|md|log|json|xml|properties|ps1|bat|cmd|sql|ya?ml|ini|conf|csv)$/i.test(path);
    }
    function fmt(n) {
        if (n < 1024) return n + ' B';
        if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
        return (n / 1048576).toFixed(1) + ' MB';
    }

    container.innerHTML =
        '<div class="dropzone" data-dz><div class="dz-text">Drag &amp; drop files here, or ' +
        '<button class="ghost-btn" data-browse>browse…</button> ' +
        '<button class="ghost-btn" data-newfile>✎ create text file</button></div>' +
        '<input type="file" multiple style="display:none" data-input></div>' +
        '<div class="form-row" style="margin-top:10px">' +
        '<div class="field"><label>File type</label><select data-kind>' +
        '<option value="document">document (txt, md, json, …)</option>' +
        '<option value="script">executable step (ps1, jar, bat, cmd)</option>' +
        '</select></div>' +
        '<div class="field wide" data-aliaswrap style="display:none"><label>Alias (variable name, unique) — use as ${alias} in a step</label>' +
        '<input data-alias placeholder="my_script"></div>' +
        '</div>' +
        '<div data-pending class="dim small" style="margin:6px 0"></div>' +
        '<div style="margin-bottom:8px"><button class="btn sm primary" data-upload style="display:none">⬆ Upload selected</button></div>' +
        '<div data-editor class="file-editor" style="display:none">' +
        '<div class="form-row"><div class="field wide"><label>New file name</label>' +
        '<input data-ename placeholder="notes.txt | Validate.ps1"></div></div>' +
        '<textarea data-econtent class="file-editor-area" placeholder="Paste or type the file content here…"></textarea>' +
        '<div style="margin-top:8px"><button class="btn sm primary" data-esave>💾 Save file</button> ' +
        '<button class="btn sm" data-ecancel>Cancel</button></div></div>' +
        '<div class="ds-test-result" data-msg></div>' +
        '<div data-list style="margin-top:8px"></div>';

    var dz = container.querySelector('[data-dz]');
    var input = container.querySelector('[data-input]');
    var kindSel = container.querySelector('[data-kind]');
    var aliasWrap = container.querySelector('[data-aliaswrap]');
    var aliasInp = container.querySelector('[data-alias]');
    var pendingBox = container.querySelector('[data-pending]');
    var uploadBtn = container.querySelector('[data-upload]');
    var msg = container.querySelector('[data-msg]');
    var listBox = container.querySelector('[data-list]');
    var editor = container.querySelector('[data-editor]');
    var eName = container.querySelector('[data-ename]');
    var eContent = container.querySelector('[data-econtent]');

    var pending = [];

    container.querySelector('[data-browse]').addEventListener('click', function () { input.click(); });
    input.addEventListener('change', function () { setPending(input.files); });
    kindSel.addEventListener('change', refreshAliasUi);

    var newFileBtn = container.querySelector('[data-newfile]');
    newFileBtn.addEventListener('click', function () {
        editor.style.display = editor.style.display === 'none' ? 'block' : 'none';
        if (editor.style.display === 'block') eName.focus();
    });
    container.querySelector('[data-ecancel]').addEventListener('click', function () {
        editor.style.display = 'none'; eName.value = ''; eContent.value = '';
    });
    // when saving an executable, auto-suggest an alias from the typed name
    container.querySelector('[data-esave]').addEventListener('click', function () {
        var name = eName.value.trim();
        if (!name) { msg.style.color = 'var(--fail)'; msg.textContent = 'Enter a file name'; return; }
        var kind = kindSel.value;
        function doSave(alias) {
            var fd = new FormData();
            fd.append('name', name);
            fd.append('content', eContent.value);
            fd.append('kind', kind);
            if (kind === 'script' && alias) fd.append('alias', alias);
            fetch(apiBase + 'files/create', { method: 'POST', body: fd })
                .then(function (r) { return r.json(); })
                .then(function (j) {
                    if (j.ok) {
                        msg.style.color = 'var(--ok)';
                        msg.textContent = 'Saved ' + j.name + (j.alias ? (' (${' + j.alias + '})') : '');
                        editor.style.display = 'none'; eName.value = ''; eContent.value = '';
                        load();
                    } else { msg.style.color = 'var(--fail)'; msg.textContent = j.error || 'Save failed'; }
                })
                .catch(function (e) { msg.style.color = 'var(--fail)'; msg.textContent = String(e); });
        }
        if (kind === 'script') {
            // reuse the alias chosen in the upload alias box if present, else ask the server
            if (aliasInp.value.trim()) { doSave(aliasInp.value.trim()); }
            else fetch(apiBase + 'alias-suggest?file=' + encodeURIComponent(name))
                .then(function (r) { return r.json(); })
                .then(function (j) { doSave(j && j.alias ? j.alias : null); })
                .catch(function () { doSave(null); });
        } else {
            doSave(null);
        }
    });

    ['dragenter', 'dragover'].forEach(function (ev) {
        dz.addEventListener(ev, function (e) { e.preventDefault(); e.stopPropagation(); dz.classList.add('drag'); });
    });
    ['dragleave', 'drop'].forEach(function (ev) {
        dz.addEventListener(ev, function (e) { e.preventDefault(); e.stopPropagation(); dz.classList.remove('drag'); });
    });
    dz.addEventListener('drop', function (e) { setPending(e.dataTransfer.files); });

    function setPending(files) {
        pending = Array.prototype.slice.call(files || []);
        if (!pending.length) { pendingBox.textContent = ''; uploadBtn.style.display = 'none'; return; }
        pendingBox.textContent = 'Selected: ' + pending.map(function (f) { return f.name; }).join(', ');
        uploadBtn.style.display = 'inline-block';
        refreshAliasUi();
    }

    function refreshAliasUi() {
        var isScript = kindSel.value === 'script';
        var single = pending.length === 1;
        aliasWrap.style.display = (isScript && single) ? 'block' : 'none';
        if (isScript && single) {
            // ask the server for a unique suggestion based on the file name
            fetch(apiBase + 'alias-suggest?file=' + encodeURIComponent(pending[0].name))
                .then(function (r) { return r.json(); })
                .then(function (j) { if (j && j.alias && !aliasInp.value) aliasInp.value = j.alias; })
                .catch(function () {});
        }
    }

    uploadBtn.addEventListener('click', function () {
        if (!pending.length) return;
        var kind = kindSel.value;
        var queue = pending.slice();
        var ok = 0, fail = 0, errs = [];
        uploadBtn.disabled = true;
        msg.style.color = 'var(--ink-dim)';
        msg.textContent = 'Uploading…';

        function step() {
            if (!queue.length) {
                uploadBtn.disabled = false;
                msg.style.color = fail ? 'var(--fail)' : 'var(--ok)';
                msg.textContent = 'Uploaded ' + ok + ' file(s)' + (fail ? (', ' + fail + ' failed: ' + errs.join('; ')) : '');
                pending = []; input.value = ''; aliasInp.value = '';
                pendingBox.textContent = ''; uploadBtn.style.display = 'none'; aliasWrap.style.display = 'none';
                load();
                return;
            }
            var f = queue.shift();
            sendOne(f, kind, function (res) {
                if (res.ok) ok++; else { fail++; errs.push(f.name + ': ' + (res.error || 'error')); }
                step();
            });
        }
        step();
    });

    function sendOne(file, kind, done) {
        function post(alias) {
            var fd = new FormData();
            fd.append('file', file);
            fd.append('kind', kind);
            if (kind === 'script' && alias) fd.append('alias', alias);
            fetch(apiBase + 'files', { method: 'POST', body: fd })
                .then(function (r) { return r.json(); })
                .then(done)
                .catch(function (e) { done({ ok: false, error: String(e) }); });
        }
        if (kind === 'script') {
            if (pending.length === 1 && aliasInp.value.trim()) {
                post(aliasInp.value.trim());
            } else {
                // multiple executables: auto-suggest a unique alias per file, just before upload
                fetch(apiBase + 'alias-suggest?file=' + encodeURIComponent(file.name))
                    .then(function (r) { return r.json(); })
                    .then(function (j) { post(j && j.alias ? j.alias : null); })
                    .catch(function () { post(null); });
            }
        } else {
            post(null);
        }
    }

    var allFiles = [];
    var fState = { q: '', key: 'path', dir: 1 };
    var shellBound = false;

    function fmtDate(ms) {
        if (!ms) return '';
        var d = new Date(ms);
        function p(n) { return (n < 10 ? '0' : '') + n; }
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
    }
    function sortIcon(key) { return fState.key === key ? (fState.dir > 0 ? ' \u25B2' : ' \u25BC') : ''; }

    function load() {
        fetch(listUrl)
            .then(function (r) { return r.json(); })
            .then(function (j) {
                if (!j.ok) { listBox.innerHTML = '<span class="dim small">' + esc(j.error || 'error') + '</span>'; return; }
                allFiles = j.files || [];
                renderFiles();
                if (typeof onChange === 'function') onChange(allFiles);
            })
            .catch(function (e) { listBox.innerHTML = '<span class="dim small">' + esc(String(e)) + '</span>'; });
    }

    function ensureShell() {
        if (shellBound && listBox.querySelector('[data-fsearch]')) return;
        listBox.innerHTML =
            '<div class="file-toolbar"><input data-fsearch class="file-search-input" placeholder="Search by file name…"></div>' +
            '<div data-fhost></div>';
        var si = listBox.querySelector('[data-fsearch]');
        si.value = fState.q;
        si.addEventListener('input', function () { fState.q = si.value.toLowerCase(); renderTable(); });
        shellBound = true;
    }

    function renderFiles() { ensureShell(); renderTable(); }

    function renderTable() {
        var host = listBox.querySelector('[data-fhost]');
        if (!host) return;
        if (!allFiles.length) { host.innerHTML = '<span class="dim small">No files yet.</span>'; return; }
        var rows = allFiles.filter(function (f) {
            if (!fState.q) return true;
            return (String(f.path || '').toLowerCase().indexOf(fState.q) >= 0) ||
                   (String(f.name || '').toLowerCase().indexOf(fState.q) >= 0);
        });
        rows.sort(function (a, b) {
            var k = fState.key;
            if (k === 'size' || k === 'modified') { return ((a[k] || 0) - (b[k] || 0)) * fState.dir; }
            var va = String(a[k] == null ? '' : a[k]).toLowerCase();
            var vb = String(b[k] == null ? '' : b[k]).toLowerCase();
            return (va < vb ? -1 : va > vb ? 1 : 0) * fState.dir;
        });
        var h = '<table><thead><tr>' +
            '<th data-sort="path" class="sortable">File' + sortIcon('path') + '</th>' +
            '<th data-sort="kind" class="sortable" style="width:100px">Type' + sortIcon('kind') + '</th>' +
            '<th data-sort="alias" class="sortable">Alias' + sortIcon('alias') + '</th>' +
            '<th data-sort="size" class="sortable" style="width:90px">Size' + sortIcon('size') + '</th>' +
            '<th data-sort="modified" class="sortable" style="width:150px">Modified' + sortIcon('modified') + '</th>' +
            '<th style="width:260px"></th></tr></thead><tbody>';
        rows.forEach(function (f) {
            var dl = apiBase + 'download?path=' + encodeURIComponent(f.path);
            var kindLabel = f.kind === 'script' ? 'executable' : (f.kind === 'output' ? 'output' : 'document');
            var viewBtn = '';
            if (ctx && viewable(f.path)) {
                var vurl = ctx + 'view?src=' + encodeURIComponent(dl) + '&name=' + encodeURIComponent(f.name) + '&api=' + encodeURIComponent(apiBase) + '&path=' + encodeURIComponent(f.path);
                viewBtn = '<a class="btn sm" href="' + vurl + '" target="_blank">\uD83D\uDC41 View</a> ';
            }
            h += '<tr>' +
                '<td class="mono small">' + esc(f.path) + '</td>' +
                '<td class="small dim">' + kindLabel + '</td>' +
                '<td class="mono small">' + (f.alias ? ('${' + esc(f.alias) + '}') : '<span class="dim">\u2014</span>') + '</td>' +
                '<td class="mono small dim">' + fmt(f.size) + '</td>' +
                '<td class="mono small dim">' + esc(fmtDate(f.modified)) + '</td>' +
                '<td>' + viewBtn + '<a class="btn sm" href="' + dl + '">\u2B07 Download</a> ' +
                '<button class="btn sm" data-copy="' + esc(f.path) + '" title="Copy the feed-relative path (use it as a csvsql/xlsx2csv source)">\uD83D\uDCCB Copy path</button> ' +
                '<button class="btn sm danger" data-del="' + esc(f.path) + '">Delete</button></td>' +
                '</tr>';
        });
        h += '</tbody></table>';
        host.innerHTML = h;
        host.querySelectorAll('[data-del]').forEach(function (b) {
            b.addEventListener('click', function () { del(b.getAttribute('data-del')); });
        });
        host.querySelectorAll('[data-copy]').forEach(function (b) {
            b.addEventListener('click', function () { copyPath(b.getAttribute('data-copy'), b); });
        });
        host.querySelectorAll('[data-sort]').forEach(function (th) {
            th.addEventListener('click', function () {
                var k = th.getAttribute('data-sort');
                if (fState.key === k) fState.dir = -fState.dir; else { fState.key = k; fState.dir = 1; }
                renderTable();
            });
        });
    }

    function copyPath(path, btn) {
        var done = function () {
            if (!btn) return;
            var old = btn.textContent; btn.textContent = '\u2713 Copied!';
            setTimeout(function () { btn.textContent = old; }, 1200);
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(path).then(done, function () { fallbackCopy(path); done(); });
        } else { fallbackCopy(path); done(); }
    }
    function fallbackCopy(text) {
        var ta = document.createElement('textarea');
        ta.value = text; ta.style.position = 'fixed'; ta.style.top = '-2000px'; ta.style.opacity = '0';
        document.body.appendChild(ta); ta.focus(); ta.select();
        try { document.execCommand('copy'); } catch (e) {}
        document.body.removeChild(ta);
    }

    function del(path) {
        var ask = window.opConfirm || function (m, cb) { if (confirm(m)) cb(); };
        ask('Delete ' + path + '?', function () {
            var fd = new FormData(); fd.append('path', path);
            fetch(apiBase + 'files/delete', { method: 'POST', body: fd })
                .then(function (r) { return r.json(); })
                .then(function () { load(); });
        }, { danger: true, okText: 'Delete' });
    }

    refreshAliasUi();
    load();
}
