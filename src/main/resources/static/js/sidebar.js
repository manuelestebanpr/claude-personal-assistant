// Mobile sidebar drawer. Separate from chat.js on purpose: chat.js returns early when no chat is
// selected, and the conversation list has to be reachable exactly then.
(function () {
    'use strict';

    const toggle = document.getElementById('sidebar-toggle');
    const sidebar = document.getElementById('sidebar');
    const backdrop = document.getElementById('sidebar-backdrop');
    if (!toggle || !sidebar) {
        return;
    }

    toggle.addEventListener('click', function () {
        setOpen(!isOpen());
    });

    if (backdrop) {
        backdrop.addEventListener('click', () => setOpen(false));
    }

    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape' && isOpen()) {
            setOpen(false);
            toggle.focus();
        }
    });

    // Resizing past the breakpoint takes the drawer out of play; the class would otherwise stay on
    // and re-apply the moment the viewport shrinks again.
    window.addEventListener('resize', function () {
        if (isOpen() && window.innerWidth > 768) {
            setOpen(false);
        }
    });

    function isOpen() {
        return document.body.classList.contains('sidebar-open');
    }

    function setOpen(open) {
        document.body.classList.toggle('sidebar-open', open);
        toggle.setAttribute('aria-expanded', String(open));
        toggle.setAttribute('aria-label', open ? 'Hide chats' : 'Show chats');
        if (backdrop) {
            backdrop.hidden = !open;
        }
    }
})();
