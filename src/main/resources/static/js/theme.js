/* OpenProteo theme toggle — light/dark, persisted in localStorage.
   No literal newline-escape sequences in strings (UBS proxy constraint). */
(function () {
    var KEY = 'op-theme';

    function read() {
        try { return window.localStorage.getItem(KEY); } catch (e) { return null; }
    }
    function write(v) {
        try { window.localStorage.setItem(KEY, v); } catch (e) { /* ignore */ }
    }
    function current() {
        return document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
    }
    function apply(theme) {
        if (theme === 'light') document.documentElement.setAttribute('data-theme', 'light');
        else document.documentElement.removeAttribute('data-theme');
    }

    // apply persisted preference as early as possible
    var saved = read();
    if (saved) apply(saved);

    function label(theme) { return theme === 'light' ? '\u263E dark' : '\u2600 light'; }

    function mount() {
        var bar = document.querySelector('.topbar');
        if (!bar || document.getElementById('themeToggle')) return;
        var btn = document.createElement('button');
        btn.id = 'themeToggle';
        btn.className = 'theme-toggle';
        btn.type = 'button';
        btn.title = 'Toggle light/dark theme';
        btn.textContent = label(current());
        btn.onclick = function () {
            var next = current() === 'light' ? 'dark' : 'light';
            apply(next); write(next);
            btn.textContent = label(next);
        };
        var clock = bar.querySelector('.clock');
        if (clock) bar.insertBefore(btn, clock);
        else bar.appendChild(btn);
    }

    function ctx() { var b = document.querySelector('.brand'); var h = b ? b.getAttribute('href') : '/'; return h.charAt(h.length - 1) === '/' ? h : h + '/'; }
    function mountEnv() {
        var bar = document.querySelector('.topbar');
        if (!bar || document.getElementById('envBadge')) return;
        try {
            fetch(ctx() + 'api/env').then(function (r) { return r.json(); }).then(function (j) {
                var e = (j && j.environment) ? String(j.environment).trim() : '';
                if (!e) return;
                if (document.getElementById('envBadge')) return;
                var span = document.createElement('span');
                span.id = 'envBadge';
                span.className = 'env-badge env-' + e.toUpperCase().replace(/[^A-Z0-9]/g, '');
                span.textContent = e.toUpperCase();
                span.title = 'Environment ' + e.toUpperCase() + (j.host ? (' \u00B7 ' + j.host) : '');
                if (/^prod/i.test(e)) { bar.classList.add('is-prod'); }
                var sub = bar.querySelector('.sub');
                var spacer = bar.querySelector('.spacer');
                if (sub && sub.parentNode === bar) bar.insertBefore(span, sub.nextSibling);
                else if (spacer) bar.insertBefore(span, spacer);
                else bar.appendChild(span);
            }).catch(function () { });
        } catch (err) { }
    }

    var VER_PROMISE = null;
    function versionInfo() {
        if (!VER_PROMISE) {
            VER_PROMISE = fetch(ctx() + 'api/version')
                .then(function (r) { return r.json(); })
                .catch(function () { return null; });
        }
        return VER_PROMISE;
    }

    function mountVersion() {
        var bar = document.querySelector('.topbar');
        if (!bar || document.getElementById('verBadge')) return;
        try {
            versionInfo().then(function (j) {
                if (!j) return;
                var label = j.label ? ('v' + j.label) : (j.version ? ('v' + j.version) : '');
                if (!label) return;
                if (document.getElementById('verBadge')) return;
                var span = document.createElement('span');
                span.id = 'verBadge';
                span.className = 'ver-badge';
                span.textContent = label;
                span.title = 'OpenProteo ' + (j.version || '')
                        + (j.buildNumber ? ('  build ' + j.buildNumber) : '')
                        + (j.shortCommit ? ('  commit ' + j.shortCommit) : '')
                        + (j.buildTime ? ('  built ' + j.buildTime) : '');
                var clock = document.getElementById('clock');
                if (clock && clock.parentNode === bar) bar.insertBefore(span, clock);
                else bar.appendChild(span);
            }).catch(function () { });
        } catch (err) { }
    }

    /* Splash shown once per browser-tab session: fades itself out, and any click or key closes it. */
    function mountSplash() {
        if (!document.querySelector('.topbar') || document.getElementById('opSplash')) return;
        try { if (sessionStorage.getItem('op-splash') === '1') return; sessionStorage.setItem('op-splash', '1'); }
        catch (e) { return; }                      // storage blocked: skip the splash rather than repeat it

        var reduce = false;
        try { reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) { }

        var ov = document.createElement('div');
        ov.id = 'opSplash';
        ov.className = 'op-splash' + (reduce ? ' op-noanim' : '');
        var card = document.createElement('div'); card.className = 'op-splash-card'; ov.appendChild(card);
        var img = document.createElement('img'); img.className = 'op-splash-logo'; img.alt = '';
        img.src = ctx() + 'img/logo.svg'; card.appendChild(img);
        var nm = document.createElement('div'); nm.className = 'op-splash-name'; nm.textContent = 'OPENPROTEO'; card.appendChild(nm);
        var cl = document.createElement('div'); cl.className = 'op-splash-claim';
        cl.textContent = 'Pipeline Workflow Orchestrator'; card.appendChild(cl);
        var vr = document.createElement('div'); vr.className = 'op-splash-ver'; card.appendChild(vr);
        var bar = document.createElement('div'); bar.className = 'op-splash-bar';
        bar.appendChild(document.createElement('span')); card.appendChild(bar);
        document.body.appendChild(ov);

        versionInfo().then(function (j) {
            if (!j) return;
            var t = (j.label ? ('v' + j.label) : (j.version ? ('v' + j.version) : ''));
            if (j.buildTime) t += (t ? '   ' : '') + j.buildTime;
            vr.textContent = t;
        });

        var done = false;
        function close() {
            if (done) return; done = true;
            ov.className += ' op-hide';
            setTimeout(function () { if (ov.parentNode) ov.parentNode.removeChild(ov); }, reduce ? 0 : 420);
        }
        setTimeout(close, reduce ? 700 : 2000);
        ov.addEventListener('click', close);
        document.addEventListener('keydown', function onKey(e) { document.removeEventListener('keydown', onKey); close(); });
        setTimeout(function () { if (ov.parentNode) ov.parentNode.removeChild(ov); }, 6000);   // hard safety net
    }

    function mountAll() { mount(); mountEnv(); mountVersion(); mountSplash(); }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mountAll);
    else mountAll();
})();
