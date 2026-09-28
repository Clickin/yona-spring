# Markdown

Browser Markdown uses main-repository Lit 3 Web Components, micromark + GFM, and DOMPurify. Server CommonMark remains for email, translation responses and `WikiRestApiController.renderedHtml`; those API contracts are unchanged.

## Read-only content

```html
<yona-markdown-renderer mode="comment" owner="owner" project="project"
    th:text="${issue.body}"></yona-markdown-renderer>
```

Always bind escaped text (`th:text`), never pre-rendered HTML or a Markdown attribute. `mode="document"` uses CommonMark line breaks and stable GitHub-style heading IDs. `mode="comment"` preserves soft line breaks. Repository documents additionally provide `ref` and full file `path`; Wiki provides its page path without a repository ref.

Each element captures its source once. Replacing content requires a new element. The renderer retains an inert `<template>` snapshot so Turbo's `cloneNode(true)` cache restores Markdown rather than reparsing rendered text. Reconnecting the same element does not parse again. There is no MutationObserver or Turbo dependency in the renderer.

## Pipeline and security

`micromark → DOMPurify → relative links/headings/references/task lists → async enhancement`.

Raw HTML is restricted to the HTML profile, without forms, inline styles, event handlers, SVG/MathML or unsafe protocols. Structural plugins create DOM nodes and text, not user-supplied HTML. External links use `noopener`; `application.noreferrer=true` also adds `noreferrer`.

References are batched globally per project in a fixed 25 ms window, deduplicated and cached in page memory. `POST /api/{owner}/{project}/markdown/references/resolve` accepts at most 100 typed tokens, each at most 200 characters, and returns metadata, never HTML. Target project/issue permissions and member-only repository access are checked before lookup results are disclosed. Disconnect cancels subscribers; stale results cannot update detached content.

Highlight core and individual grammars load only for recognized code fences. The fixture in `frontend/src/markdown/runtime/highlight-compatibility.json` preserves all 59 legacy language registrations and aliases plus the current bundle's languages (66 canonical grammars). Unknown languages stay plain text; automatic language detection is not used.

Mermaid loads only for Mermaid fences. A shared 24 ms queue renders sequentially and yields between diagrams, prioritizing the viewport. Strict mode, 50,000-character and 500-edge limits apply. SVG is sanitized separately, without `foreignObject`, handlers or external resources. Failed diagrams retain their source. Viewer instances belong to the renderer lifecycle and are destroyed on disconnect.

## Build and checks

`./gradlew processResources` and `bootJar` depend on `npmCi` and `buildMarkdown`. The pinned lockfile is installed with `--ignore-scripts --no-audit --no-fund`. esbuild emits ESM and lazy chunks into `build/generated/frontend/markdown`; generated Markdown bundles are not committed. Existing Turbo assets retain their separate build path. Gradle selects `npm.cmd` on Windows.

```sh
cd frontend
npm ci --ignore-scripts --no-audit --no-fund
npm run build:markdown
npx tsc --noEmit
# From the repository root, against a running application:
node frontend/scripts/check-markdown-structure.mjs http://localhost:8080
node frontend/scripts/check-markdown-enhancements.mjs http://localhost:8080
```

Both browser scripts use the existing E2E Playwright dependency and exercise Chromium, Firefox and WebKit. Application fixtures live in `e2e/specs/15-misc/markdown-components.spec.ts`.

## Baseline

Before migration (`next` a71722f, bytes; local gzip/Brotli, not HTTP transfer):

| Asset | Minified | gzip | Brotli |
|---|---:|---:|---:|
| CM6 editor | 557,522 | 194,197 | 162,527 |
| Marked | 44,870 | 13,865 | 12,531 |
| Global highlight | 129,254 | 44,094 | 38,156 |

The read-only renderer migration precedes the editor replacement in a separate commit. Legacy undocumented Marked quirks and `yb-header-*` IDs are not preserved.
