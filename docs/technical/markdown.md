# Markdown

Browser Markdown uses main-repository Vue 3 components mounted in open Shadow DOM, micromark + GFM, and DOMPurify. Thymeleaf still owns pages and form markup; server CommonMark remains for email, translation responses and `WikiRestApiController.renderedHtml`. Those API interfaces are unchanged.

## Read-only content

```html
<yona-markdown-renderer mode="comment" owner="owner" project="project"
    th:text="${issue.body}"></yona-markdown-renderer>
```

Always bind escaped text (`th:text`), never pre-rendered HTML or a Markdown attribute. `mode="document"` uses CommonMark line breaks and stable GitHub-style heading IDs. `mode="comment"` preserves soft line breaks. Repository documents additionally provide `ref` and full file `path`; Wiki provides its page path without a repository ref.

Each element captures its source once. Replacing content requires a new element. The renderer retains an inert `<template>` snapshot so Turbo's `cloneNode(true)` cache restores Markdown rather than reparsing rendered text. Reconnecting the same element does not parse again. There is no MutationObserver or Turbo dependency in the renderer.

The blocking `yona.css` stylesheet reserves loading space using the escaped source's native `pre-wrap` layout (including line breaks and width-dependent wrapping), with the renderer's typography and padding. A small head bootstrap opts JavaScript-capable browsers into source concealment and a visible loading cue before module download. No-JavaScript browsers retain readable source; module network/evaluation failures remove concealment. This is an approximate text-height reservation, not a prediction of heading, image or Mermaid dimensions. No fixed height or duplicate visible source survives rendering or resizing.

`data-markdown-ready` is removed on connection, including Turbo clones, and added only after Vue mounts sanitized output, the shadow styles load, and structural plugins/enhancement setup completes. The composed `markdown-rendered` event follows that marker. The public `.ready` promise resolves at this boundary. Asynchronous references, syntax highlighting, images and diagrams can settle afterward.

## Editor

`site/layout :: markdownEditor` renders the toolbar, help, tab panes and real form textarea inside `<yona-markdown-editor>`. Vue replaces the toolbar and panels inside an open shadow root. The original textarea and text-expander remain in a named light-DOM slot, preserving native `value`, `defaultValue`, selection, validation, form submission/reset and legacy attachment/autosave selectors. No duplicate hidden input or value synchronization is needed. The form remains usable without JavaScript.

The editor keeps Yona 1.x's Edit/Preview tabs, checklist button, draft notice and visible Markdown help navigation, using the existing `nav nav-tabs nm small` and `ybtn` styles. Labels come from Thymeleaf messages. Checklist insertion uses the legacy three-item template in the owning textarea and exits Preview; the old page-wide handler is removed to avoid duplicate insertion. The added formatting toolbar and separate Help button are removed.

The toolbar reference is the exact `v1.16.0` tag, not `upstream/master`: its checklist button includes `yobicon-list task-list-icon`, tab padding is `4px 15px`, and list bottom margin is `-1px`. Editor-scoped rules restore these values without changing other site tabs. With ko-KR labels the corrected button measures 120.48×25px (previously 104.09×24px); the prior wider tab padding shifted it 60px right. Six toolbar elements in Edit/Preview at 1440px and 390px widths matched the tag's markup, compiled Less, Bootstrap and original icon font within 1 CSS px, with exact paint/typography/spacing. This is a static browser-reference comparison, not full running Play-page parity.

Markdown help is the native `help/markdown :: markdown` Thymeleaf fragment. Its ten input/output examples remain light-DOM HTML in the `help` slot, with the existing document-root Stimulus controller and global Yona styles. The draft notice is slotted for the same reason: autosave still updates the original node.

The restored-draft clear button occupies the next toolbar `<li>` after the checklist, matching `v1.16.0`'s `app/views/common/editor.scala.html`, both before upgrade and through the `clear-draft` slot. Its original light-DOM node keeps draft visibility and delegated deletion working. At 1430px, comparison against the legacy toolbar markup found the same 10px gap, button size and paint, with a 1px vertical-offset difference; Edit/Preview/Turbo-clone regression checks and actual clear-from-preview reload passed.

GitHub's text expander provides completion. `@` and `#` adapt the existing permission-aware `mentionList` endpoint with abortable requests; `:` searches the 65 existing local emoji entries without a request. Suggestion labels are text, not HTML. Tab/Shift+Tab, attachments, draft restore/clear and the existing `.value` getter/setter use the same textarea. `<yona-attachments>` binds paste/drop to that textarea: a pasted image inserts a temporary `<!--_id_-->` marker replaced by its link after upload (`e2e/specs/15-misc/markdown-editor-attachments.spec.ts`).

Preview is explicit: entering Preview mounts a new renderer with the textarea as `sourceElement`; Edit removes it. Typing does not parse Markdown. Setting editor `.value` or resetting its form exits a stale preview. Wiki previews use document mode. Inline code reviews use the existing vanilla CodeCommentBox with a server-rendered form, rather than a second Vue editor.

The visible preview pane retains the legacy `div.markdown-preview` shell, including its border against the help navigation and its background/radius. That shell owns the padding; the nested renderer has zero padding so content is not inset twice.

Entering Preview captures the edit pane's current outer height, including the textarea's bottom margin, and applies that exact border-box height to the preview pane. Long content scrolls inside Preview instead of expanding the editor. Returning to Edit retains the same textarea and its native resized height. Active panes use `flow-root` to contain margins so following content does not shift between tabs. The page-wide `autosize()` excludes Markdown editor textareas; it still enhances other textareas. Chromium regression checks cover delayed module loading, default/resized textarea ownership, and short/overflowing Edit → Preview → Edit roundtrips at 1366px and 390px widths.

CM6, the Vue Markdown editor/review-form distributions, Marked, the global highlighter and the `/markdown/{owner}/{project}` preview controller are removed. Other unrelated Vue widgets are unchanged. The server renderer's obsolete repository-relative helpers were removed; server-only rendering/cache and API response fields remain.

### Shadow boundary and help ownership

The reuse audit found 19 `markdownEditor` include templates: issues (3), board (3), Wiki (1), milestones (2), pull requests (3), code diff/compare/SVN (3), and common comment/update/thread/review partials (4). They all receive the same help fragment; there is no separate help-only include. CodeCommentBox moves the same `#review-form` DOM with `appendChild`, so this is a shared behavior rather than an issue-form behavior.

`frontend/src/markdown/yona-markdown-help.ts`, loaded globally as `/javascripts/markdown/yona-markdown-help.js`, starts one Stimulus Application and registers its local `MarkdownHelpController` as `markdown-help`. The fragment root declares the behavior:

```html
<div class="markdown-help" data-controller="markdown-help"
    data-action="click->markdown-help#toggle keydown.enter->markdown-help#toggle keydown.space->markdown-help#toggle">
  <!-- Existing .help-nav[data-target] controls and HTML example panels. -->
</div>
```

[`data-action` and its keyboard filters](https://stimulus.hotwired.dev/reference/actions#keyboardevent-filter) route click/Enter/Space to `toggle(Event)`. It handles only help controls, prevents their default action and updates `active`, `aria-expanded` and panel `hidden` through scoped `tab`/`panel` targets; existing `data-target` panel class names remain. There is no `data-toggle="markdown-help"`, editor help signal or editor-owned help listener. The DOM stores the open state; there is no `connect()` reset.

Vue owns the shadow toolbar and preview, not the slotted help, clear-draft button, notice or input subtrees. Stimulus therefore continues to discover help through document queries and retains open panels across same-node reparenting and Turbo clones. The clear-draft slot remains in the toolbar immediately after the checklist button. The shadow root reuses the same cached Bootstrap, icon, Yona and highlighting stylesheets; each `.ce.vue` component owns its additional rules in a `<style>` block.

Renderer output is genuinely inside its shadow root. Heading fragments scroll explicitly across that boundary, task-list integration queries the renderer's shadow root while retaining the host's permission/form context, and reference popovers initialize against the output subtree. Renderer snapshots remain inert light-DOM templates so Turbo clones preserve source. The measurements below are historical pre-Vue results, not measurements of this cutover.

### Internal functions

- `MarkdownEditor.ce.vue`: real `<script setup lang="ts">`, `<template>` and `<style>` sections own preview state, toolbar markup, checklist insertion and local styles. Native controls/host are shallow props, never deep reactive state. Static `<slot v-pre>` elements remain native Shadow DOM slots rather than Vue slot outlets; the compiler preserves `yona-*` elements, and renderer `sourceElement` uses a property binding.
- `yona-markdown-editor.ts`: the HTMLElement bridge retains public `.value`/`.ready`, original textarea initialization, form/reset/reconnect events and line indentation. Completion separates the request (`mentionSuggestions`), response conversion (`mentionSuggestion`) and safe DOM construction (`suggestionOption`).
- `yona-markdown-help.ts`: one local Stimulus controller owns help toggling through declarative actions; the Vue editor retains its server-rendered root in a slot.
- `yona-markdown-renderer.ts`: snapshot initialization and post-mount enhancement stay in the element; `markdownOutput` owns parsing/sanitization, and `commentLineBreaks` owns comment-only newline rules. `MarkdownRenderer.ce.vue` owns output markup and local styles; its `v-html` receives only the already-sanitized snapshot.
- `plugins/structure.ts`: headings, link policy, reference collection/resolution and DOM replacement each have a named function.
- `runtime/reference-batch-resolver.ts`: `send` orchestrates the batch; `fetchMetadata` handles requests/validation, and `settleBatch` handles caching/subscriber cleanup.

The existing scanner, highlighting registry, Mermaid queue and scheduler already separate their responsibilities; no additional wrappers or modules are needed.

## Pipeline and security

`micromark → DOMPurify → relative links/headings/references/task lists → async enhancement`.

Raw HTML is restricted to the HTML profile, without forms, inline styles, event handlers, SVG/MathML or unsafe protocols. Structural plugins create DOM nodes and text, not user-supplied HTML. External links use `noopener`; `application.noreferrer=true` also adds `noreferrer`.

Reference autolinking reproduces the server `AutoLinkRenderer` passes in order (`path#N`, `#N`, `path@sha`, `sha`, `@user|@org|@owner/project`), including fork shorthand, ASCII-only word boundaries, localized issue state and text-only user hover popovers. Unresolved matches stay text. `frontend/test/reference-tokens.test.mjs` (`pnpm test`, run by Gradle `test`) covers these rules.

References are batched globally per project in a fixed 25 ms window, deduplicated and cached in page memory. `POST /api/{owner}/{project}/markdown/references/resolve` accepts at most 100 typed tokens, each at most 200 characters, and returns metadata, never HTML. Target project/issue permissions and member-only repository access are checked before lookup results are disclosed. Disconnect cancels subscribers; stale results cannot update detached content.

Highlight core and individual grammars load only for recognized code fences. The fixture in `frontend/src/markdown/runtime/highlight-compatibility.json` preserves all 59 legacy language registrations and aliases plus the current bundle's languages (66 canonical grammars). Unknown languages stay plain text; automatic language detection is not used.

Mermaid loads only for Mermaid fences. A shared 24 ms queue renders sequentially and yields between diagrams, prioritizing the viewport. Strict mode, 50,000-character and 500-edge limits apply. SVG is sanitized separately, without `foreignObject`, handlers or external resources. Failed diagrams retain their source. Viewer instances belong to the renderer lifecycle and are destroyed on disconnect.

## Build and checks

`./gradlew processResources` and `bootJar` depend on `pnpmInstall`, `typecheckFrontend` and `buildMarkdown`; Gradle `test` also runs template typechecking. The pinned `pnpm-lock.yaml` is installed with `--frozen-lockfile --ignore-scripts`. esbuild uses `unplugin-vue/esbuild` in `.ce.vue` custom-element mode, emitting component styles inline for the existing shadow mount helper, ESM and lazy chunks into `build/generated/frontend/markdown`. `vue-tsc` checks TypeScript and SFC templates. Generated Markdown bundles are not committed. Existing Turbo assets retain their separate build path. Gradle selects `pnpm.cmd` on Windows.

The frontend pins TypeScript 6 because `vue-tsc` currently requires the JavaScript compiler entry point removed by native TypeScript 7. Production SFC source maps are disabled, matching the existing minified bundle build and avoiding the esbuild adapter adding JavaScript source-map comments to inline CSS.

`scripts/vue-sfc.mjs` selects esbuild's text loader for compiled `type=style&inline` modules. The default CSS loader exports an empty object rather than the CSS string that the shadow root needs. Runtime geometry/style checks cover this integration; a browser smoke confirmed real CSS rules and the 4px/15px toolbar padding.

```sh
cd frontend
pnpm install --frozen-lockfile --ignore-scripts
pnpm run build:markdown
pnpm run typecheck
pnpm test
# From the repository root, against a running application:
node frontend/scripts/check-markdown-structure.mjs http://localhost:8080
node frontend/scripts/check-markdown-enhancements.mjs http://localhost:8080
```

SFC conversion checks (2026-10-08): `pnpm run typecheck`, `pnpm run build:markdown`, all 9 unit tests and Gradle `buildMarkdown testMarkdown` passed. All 7 existing Chromium component checks passed against branch-built assets proxied to the live application, including native slots, clear-draft toolbar placement, reset/Turbo clone, preview sizing, completion security and Viewer cleanup. The initial live backend returned static-asset HTTP 500s; its restart restored the assets, and the two affected checks passed on retry.

Vue/Shadow DOM cutover checks (2026-10-08): frontend build, strict TypeScript, all 9 unit tests and Gradle `buildMarkdown testMarkdown` passed. Chromium passed 7 component checks plus native attachment paste/link insertion, comment paste, 2 help checks and delayed editor upgrade. Structure and enhancement scripts passed Chromium/Firefox/WebKit (162 language identifiers, 66 grammars, 10 diagrams each). At 1366px and 390px viewports, a 320px textarea retained exactly the same editor/textarea bounds through overflowing Preview and back, with native FormData ownership. Four repository-document loading checks could not run against the demo project because it has no HEAD branch/corpus; this is not reported as a pass.

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

| Recorded closure before toolbar restoration | Minified bytes | gzip | Brotli |
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
