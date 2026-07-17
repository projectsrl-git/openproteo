/* OpenProteo modal dialogs — opConfirm / opAlert.
   Replaces the browser confirm()/alert() popups with themed modal divs.
   No literal newline-escape sequences in strings (UBS proxy constraint). */
(function () {
    function ensureRoot() {
        var root = document.getElementById('op-modal-root');
        if (root) return root;
        root = document.createElement('div');
        root.id = 'op-modal-root';
        document.body.appendChild(root);
        return root;
    }

    function close(overlay) {
        if (overlay && overlay.parentNode) overlay.parentNode.removeChild(overlay);
        document.removeEventListener('keydown', overlay._onKey, true);
    }

    /**
     * Themed confirm dialog.
     * @param message text to show
     * @param onYes   callback invoked when the user confirms
     * @param opts    optional { title, okText, cancelText, danger:true }
     */
    window.opConfirm = function (message, onYes, opts) {
        opts = opts || {};
        var root = ensureRoot();
        var overlay = document.createElement('div');
        overlay.className = 'op-modal-overlay';

        var box = document.createElement('div');
        box.className = 'op-modal';

        var title = document.createElement('div');
        title.className = 'op-modal-title';
        title.textContent = opts.title || 'Confirm';

        var body = document.createElement('div');
        body.className = 'op-modal-body';
        body.textContent = message;

        var actions = document.createElement('div');
        actions.className = 'op-modal-actions';

        var cancel = document.createElement('button');
        cancel.className = 'btn sm';
        cancel.type = 'button';
        cancel.textContent = opts.cancelText || 'Cancel';
        cancel.onclick = function () { close(overlay); };

        var ok = document.createElement('button');
        ok.className = 'btn sm ' + (opts.danger ? 'danger' : 'primary');
        ok.type = 'button';
        ok.textContent = opts.okText || 'OK';
        var checkState = {};
        var requiredIds = [];
        var checksWrap = null;
        if (opts.checks && opts.checks.length) {
            checksWrap = document.createElement('div');
            checksWrap.className = 'op-modal-checks';
            checksWrap.style.margin = '10px 0 2px';
            opts.checks.forEach(function (c) {
                checkState[c.id] = !!c.checked;
                if (c.required) requiredIds.push(c.id);
                var lab = document.createElement('label');
                lab.style.display = 'block';
                lab.style.margin = '4px 0';
                lab.style.fontSize = '13px';
                var cb = document.createElement('input');
                cb.type = 'checkbox';
                cb.checked = !!c.checked;
                cb.style.marginRight = '6px';
                cb.onchange = function () { checkState[c.id] = cb.checked; updateOk(); };
                lab.appendChild(cb);
                lab.appendChild(document.createTextNode(c.label || ''));
                checksWrap.appendChild(lab);
            });
        }
        function updateOk() { var en = true; for (var i = 0; i < requiredIds.length; i++) { if (!checkState[requiredIds[i]]) { en = false; break; } } ok.disabled = !en; ok.style.opacity = en ? '' : '0.5'; }
        ok.onclick = function () { if (ok.disabled) return; close(overlay); if (typeof onYes === 'function') onYes(checkState); };

        actions.appendChild(cancel);
        actions.appendChild(ok);
        box.appendChild(title);
        box.appendChild(body);
        if (checksWrap) box.appendChild(checksWrap);
        box.appendChild(actions);
        updateOk();
        overlay.appendChild(box);
        root.appendChild(overlay);

        overlay.onclick = function (e) { if (e.target === overlay) close(overlay); };
        overlay._onKey = function (e) {
            if (e.key === 'Escape') close(overlay);
            else if (e.key === 'Enter') { if (ok.disabled) return; close(overlay); if (typeof onYes === 'function') onYes(checkState); }
        };
        document.addEventListener('keydown', overlay._onKey, true);
        ok.focus();
    };

    /** Themed alert dialog (single OK button). */
    window.opAlert = function (message, opts) {
        opts = opts || {};
        var root = ensureRoot();
        var overlay = document.createElement('div');
        overlay.className = 'op-modal-overlay';

        var box = document.createElement('div');
        box.className = 'op-modal';

        var title = document.createElement('div');
        title.className = 'op-modal-title';
        title.textContent = opts.title || 'Notice';

        var body = document.createElement('div');
        body.className = 'op-modal-body';
        body.textContent = message;

        var actions = document.createElement('div');
        actions.className = 'op-modal-actions';

        var ok = document.createElement('button');
        ok.className = 'btn sm primary';
        ok.type = 'button';
        ok.textContent = opts.okText || 'OK';
        ok.onclick = function () { close(overlay); };

        actions.appendChild(ok);
        box.appendChild(title);
        box.appendChild(body);
        box.appendChild(actions);
        overlay.appendChild(box);
        root.appendChild(overlay);

        overlay.onclick = function (e) { if (e.target === overlay) close(overlay); };
        overlay._onKey = function (e) { if (e.key === 'Escape' || e.key === 'Enter') close(overlay); };
        document.addEventListener('keydown', overlay._onKey, true);
        ok.focus();
    };
})();
