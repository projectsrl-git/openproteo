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

    function mountAll() { mount(); mountEnv(); }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mountAll);
    else mountAll();
})();
