# Markdown

Browser Markdown uses main-repository Lit 3 Web Components, micromark + GFM, and DOMPurify. Server CommonMark remains for email, translation responses and `WikiRestApiController.renderedHtml`; those API contracts are unchanged.

## Read-only content

```html
<yona-markdown-renderer mode="comment" owner="owner" project="project"
    th:text="${issue.body}"></yona-markdown-renderer>
```

Always bind escaped text (`th:text`), never pre-rendered HTML or a Markdown attribute. `mode="document"` uses CommonMark line breaks and stable GitHub-style heading IDs. `mode="comment"` preserves soft line breaks. Repository documents additionally provide `ref` and full file `path`; Wiki provides its page path without a repository ref.

Each element captures its source once. Replacing content requires a new element. The renderer retains an inert `<template>` snapshot so Turbo's `cloneNode(true)` cache restores Markdown rather than reparsing rendered text. Reconnecting the same element does not parse again. There is no MutationObserver or Turbo dependency in the renderer.

The blocking `yona.css` stylesheet reserves loading space using the escaped source's native `pre-wrap` layout (including line breaks and width-dependent wrapping), with the renderer's typography and padding. A small head bootstrap opts JavaScript-capable browsers into source concealment and a visible loading cue before module download. No-JavaScript browsers retain readable source; module network/evaluation failures remove concealment. This is an approximate text-height reservation, not a prediction of heading, image or Mermaid dimensions. No fixed height or duplicate visible source survives rendering or resizing.

`data-markdown-ready` is removed on connection, including Turbo clones, and added only after Lit mounts output and synchronous structural plugins/enhancement setup completes. The `markdown-rendered` event follows that marker. Asynchronous references, syntax highlighting, images and diagrams can settle afterward. Source removal and Lit mounting run in the same browser turn, without painting an empty intermediate host.

## Editor

`site/layout :: markdownEditor` renders the real form textarea inside `<yona-markdown-editor>`. JavaScript retains that node; its `value`, `defaultValue`, selection and native form reset remain authoritative. The form is usable without JavaScript.

The Lit editor uses GitHub's Markdown toolbar and text expander. `@` and `#` adapt the existing permission-aware `mentionList` endpoint with abortable requests; `:` searches the 65 existing local emoji entries without a request. Suggestion labels are text, not HTML. Tab/Shift+Tab, attachments, draft restore/clear and the existing `.value` getter/setter use the same textarea. `<yona-attachments>` binds paste/drop to that textarea: a pasted image inserts a temporary `<!--_id_-->` marker replaced by its link after upload (`e2e/specs/15-misc/markdown-editor-attachments.spec.ts`).

Preview is explicit: entering Preview mounts a new renderer with the textarea as `sourceElement`; Edit removes it. Typing does not parse Markdown. Setting editor `.value` or resetting its form exits a stale preview. Wiki previews use document mode. Inline code reviews use the existing vanilla CodeCommentBox with a server-rendered form, rather than a second Vue editor.

CM6, the Vue Markdown editor/review-form distributions, Marked, the global highlighter and the `/markdown/{owner}/{project}` preview controller are removed. Other unrelated Vue widgets are unchanged. The server renderer's obsolete repository-relative helpers were removed; server-only rendering/cache and API response fields remain.

## Pipeline and security

`micromark → DOMPurify → relative links/headings/references/task lists → async enhancement`.

Raw HTML is restricted to the HTML profile, without forms, inline styles, event handlers, SVG/MathML or unsafe protocols. Structural plugins create DOM nodes and text, not user-supplied HTML. External links use `noopener`; `application.noreferrer=true` also adds `noreferrer`.

Reference autolinking reproduces the server `AutoLinkRenderer` passes in order (`path#N`, `#N`, `path@sha`, `sha`, `@user|@org|@owner/project`), including the fork shorthand (`owner#N`, `owner@sha` against the current project name), ASCII-only word boundaries (so `이슈#3` links `#3`), `@`-only project links, the localized issue state (`stateLabel`) and the user hover popover (`name loginId`; the current popover is text-only, so the legacy avatar is omitted). An unresolved match stays text that later passes may still link; `frontend/test/reference-tokens.test.mjs` (`npm test`, run by Gradle `test`) covers these rules.

References are batched globally per project in a fixed 25 ms window, deduplicated and cached in page memory. `POST /api/{owner}/{project}/markdown/references/resolve` accepts at most 100 typed tokens, each at most 200 characters, and returns metadata, never HTML. Target project/issue permissions and member-only repository access are checked before lookup results are disclosed. Disconnect cancels subscribers; stale results cannot update detached content.

Highlight core and individual grammars load only for recognized code fences. The fixture in `frontend/src/markdown/runtime/highlight-compatibility.json` preserves all 59 legacy language registrations and aliases plus the current bundle's languages (66 canonical grammars). Unknown languages stay plain text; automatic language detection is not used.

Mermaid loads only for Mermaid fences. A shared 24 ms queue renders sequentially and yields between diagrams, prioritizing the viewport. Strict mode, 50,000-character and 500-edge limits apply. SVG is sanitized separately, without `foreignObject`, handlers or external resources. Failed diagrams retain their source. Viewer instances belong to the renderer lifecycle and are destroyed on disconnect.

## Build and checks

`./gradlew processResources` and `bootJar` depend on `npmCi` and `buildFrontend`. The pinned lockfile is installed with `--ignore-scripts --no-audit --no-fund`. esbuild emits ESM entries for each `frontend/src/<area>/yona-*.ts` and shared lazy chunks into `build/generated/frontend/web`, served under `/javascripts/<area>/`; generated Markdown bundles are not committed. Existing Turbo assets retain their separate build path. Gradle selects `npm.cmd` on Windows.

```sh
cd frontend
npm ci --ignore-scripts --no-audit --no-fund
npm run build
npm test
npx tsc --noEmit
# From the repository root, against a running application:
node frontend/scripts/check-markdown-structure.mjs http://localhost:8080
node frontend/scripts/check-markdown-enhancements.mjs http://localhost:8080
```

Both browser scripts use the existing E2E Playwright dependency and exercise Chromium, Firefox and WebKit. Application fixtures live in `e2e/specs/15-misc/markdown-components.spec.ts`.

`e2e/specs/05-code/markdown-documents.spec.ts` pushes a representative source corpus through the local application's real Git endpoint, then opens README → nested `.md` → README and loads an actual relative image. Korean/duplicate headings, GFM, safe HTML, Kotlin highlighting and a 200-paragraph document passed in all three browsers. Repository `ref` is bound with `th:attr`, not Thymeleaf's reserved `th:ref`.

## Baseline

Before migration (`next` a71722f, bytes; local gzip/Brotli, not HTTP transfer):

| Asset | Minified | gzip | Brotli |
|---|---:|---:|---:|
| CM6 editor | 557,522 | 194,197 | 162,527 |
| Marked | 44,870 | 13,865 | 12,531 |
| Global highlight | 129,254 | 44,094 | 38,156 |

The read-only renderer migration precedes the editor replacement in a separate commit. Legacy undocumented Marked quirks and `yb-header-*` IDs are not preserved.

## Recorded verification and measurements

Measurements below are local arm64 browser smoke results, not performance thresholds. Compressed sizes sum each entry's static dependency closure; renderer/editor totals overlap and must not be added together.

| Current closure | Minified bytes | gzip | Brotli |
|---|---:|---:|---:|
| Renderer | 146,656 | 50,383 | 42,844 |
| Editor including renderer | 183,720 | 61,381 | 52,592 |
| Lazy highlight core | 21,718 | 8,964 | 8,106 |
| Lazy Mermaid core dependencies, before diagram-specific chunks | 709,387 | 187,758 | 158,601 |

A cold-context Chromium issue view, with identical Markdown and no code/Mermaid fences, was measured against separate upstream `a71722f` and migrated application instances. HTTP compression was not enabled; these are observed `PerformanceResourceTiming.transferSize` totals including headers, not the gzip table above.

| Page measurement | Upstream | Lit |
|---|---:|---:|
| All same-origin transfer bytes | 3,777,755 | 2,662,562 |
| JavaScript transfer bytes | 2,765,215 | 1,581,994 |
| Markdown-related script transfer bytes, including unchanged help widget | 1,373,496 | 197,305 |
| First contentful paint, one sample (ms) | 40 | 36 |

Plain Markdown downloaded no Mermaid or highlight grammar chunks. The browser fixture exercised all 162 supported identifiers/aliases, covering 66 grammars and the 59-language legacy inventory.

| Component scenario | Chromium | Firefox | WebKit |
|---|---:|---:|---:|
| 100 comment renderers, synchronous mount/update (ms) | 22.8 | 41.0 | 36.0 |
| Reference requests for that wave | 1 | 1 | 1 |
| Ten Mermaid diagrams including cold import (ms) | 412.1 | 441 | 545 |

Chromium recorded one 68 ms long task for the ten-diagram run; the queue yielded between diagrams. Firefox/WebKit do not provide the same Long Tasks observation, so empty lists are not proof of zero long tasks. After 20/40/60 real Turbo detail swaps and forced Chromium GC, live document renderers remained 2 and retained renderer objects were 8/6/7: no growing retention in this scenario.

Verified: native textarea/toolbar/indent/reset/clone/preview, local and remote completion security, Viewer disposal, malformed Markdown HTML attacks, reference permission/batching/cancellation, relative URL normalization, task PATCH, Mermaid directive/JavaScript-link attacks and both text/edge limits. Dedicated component checks passed in all three browsers. Selected JVM server/security/template suites passed (66 tests in the final group); `processResources`, `bootJar` and strict frontend typechecking passed.

The larger application E2E runs are **not wholly green**: upstream Turbo history/filter behavior remains unchanged. The same Back-history wrong-detail failure and search-navigation failures were reproduced on a separate untouched `a71722f` worktree. Firefox and WebKit application runs each passed 86 tests with one Turbo navigation failure; a Chromium run passed 83 with one history failure. These are not suppressed or re-pinned. Markdown fixtures exercise legacy source semantics; no production Yona 1.x export archive was supplied, so an actual archive-import acceptance run is not claimed.
