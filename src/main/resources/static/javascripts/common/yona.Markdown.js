/** Server-rendered project description refresh; editor preview uses Turbo Frames. */
yona.Markdown = (function () {
    var renderUrl;
    return {
        init: function (options) {
            renderUrl = options && options.sMarkdownRendererUrl;
        },
        render: function (target, body) {
            if (!renderUrl || !target) return;
            return fetch(renderUrl, {
                method: "POST",
                headers: {"Content-Type": "application/json; charset=utf-8"},
                body: JSON.stringify({body: body, breaks: !target.classList.contains("readme-body")})
            }).then(function (response) {
                if (!response.ok) throw new Error("Markdown rendering failed: " + response.status);
                return response.text();
            }).then(function (html) {
                target.innerHTML = html;
            }).catch(function () {
                // Keep the previous rendered description when the refresh fails.
            });
        }
    };
})();
