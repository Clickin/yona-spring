// Presentation only. AccessControl, MVC and VCS authorization enforce the policy server-side.
(function () {
    function disableWrites() {
        var banner = document.querySelector('.project-archive-banner');
        if (!banner) return;
        function disable(control) {
            if (control.closest('[data-archive-control]')) return;
            control.setAttribute('aria-disabled', 'true');
            control.title = banner.textContent;
            if ('disabled' in control) control.disabled = true;
            control.classList.add('ybtn-disabled');
            control.tabIndex = -1;
            control.addEventListener('click', function (event) {
                event.preventDefault();
                event.stopImmediatePropagation();
            }, true);
        }
        document.querySelectorAll('.page-wrap-outer form, .project-page-wrap form').forEach(function (form) {
            if (form.hasAttribute('data-archive-control')) return;
            if (form.method.toLowerCase() === 'get' && !form.hasAttribute('data-api-base')) return;
            form.querySelectorAll('input, textarea, select, button').forEach(disable);
            form.addEventListener('submit', function (event) {
                event.preventDefault();
                event.stopImmediatePropagation();
            }, true);
        });
        document.querySelectorAll('[data-request-method]:not([data-request-method="get"]), [data-toggle="comment-edit"], [data-toggle="comment-delete"], .favorite-issue, #watch-button, #upvote-issue-weight, #down-vote-issue-weight, #btn-review, #btn-unreview').forEach(disable);
        document.querySelectorAll('[data-allowed-update]').forEach(function (content) {
            content.setAttribute('data-allowed-update', 'false');
        });
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', disableWrites, { once: true });
    else disableWrites();
})();
