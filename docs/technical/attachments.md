# Attachments

`<yona-attachments>` is registered with Vue's `defineCustomElement` and has an open Shadow DOM. `frontend/src/attachments/YonaAttachments.vue` owns the uploader template, styles, per-instance state, configuration, requests and lifecycle in `<script setup lang="ts">`. `yona-attachments.ts` only registers the component and adapts synchronous `configure()` calls to its declared configuration prop. The tag name, `data-resource-type`/`data-resource-id`, `.attached-file-marker` children and `configure({textarea, uploadURL, listURL, resourceType, resourceId})` are unchanged, so templates and `yona.Attachments`, `CodeCommentBox`, `issue.Detail` and `board/view.html` callers need no changes.

## Markup

`site/layout` renders `<template id="yona-attachments-template">` with localized `common.attach.*` messages. The SFC reads those translated labels as text and renders the complete uploader shell and file list through its own Vue template; it does not clone or imperatively bind the server shell. Its `<style>` is compiled in custom-element mode by `unplugin-vue/esbuild` and injected by Vue into the shadow root, without importing page-wide stylesheets. Shadow DOM supplies style isolation, so the SFC styles are not additionally `scoped`. Upload and delete controls have localized accessible labels; insertion uses a native keyboard-accessible button. Filenames use escaped template interpolation, not HTML rendering.

`temporaryUploadFiles` is created or reused in the host's light DOM, so it remains a successful control in the surrounding form. Completed attachments are mirrored into hidden `.attached-file-marker` light-DOM children: Turbo's `cloneNode` snapshots exclude shadow roots, so restored elements rebuild the list from those markers and reuse the cached hidden input. Vue's mount/unmount hooks bind and remove textarea listeners. The SFC retains its item and textarea refs on the individual host under a private symbol, so an in-flight upload can finish across a real detach/remount without losing its item, text replacement or form field. Clones initialize fresh refs from the inert markers; there is no global store.

## Internal structure

The registration's `configure()` property adapter remains available before mount and after unmount; each call assigns a fresh configuration prop, watched by the SFC's `configure` function. This also avoids Vue custom elements retaining an exposed method from an obsolete setup instance. File selection, drop and image paste all reach the SFC's `upload`, which inserts a marker only for textarea input. Request setup/progress, response parsing, completion/failure, deletion and list loading all live in the same component. Text formatting stays in the DOM-free `attachment-text.ts`, with explicit table-header preparation and alignment separators.

## Behavior

| Input | Result |
| --- | --- |
| File button, drop on the upload area | Upload; no text inserted |
| Image pasted into the textarea | `<!--_key_-->` at the caret, replaced by `![name](/files/N) ` after upload |
| File dropped on the textarea | Same marker and link per file (legacy `yona.Attachments._onDropFile`) |
| Text plus image on the clipboard (spreadsheet cells) | Markdown table, no upload (legacy `yona.Files`) |
| Click an uploaded item / its × | Insert its link at the caret / `POST _method=delete` and remove its link |
| Upload failure / delete failure | Failed upload item and marker removed / existing attachment retained; localized `common.attach.error.*` notification |

Dragging plain text keeps the browser's native behavior. Images and videos use the legacy link text (videos keep the video.js wrapper).

## Upload keys

Markers and list items are keyed by a random UUID per upload. The legacy key (`seconds + milliseconds + "-Y-M-D-H-m"`) is not unique: 1 s 10 ms and 11 s 0 ms both give `110`, so 4,500 of a minute's 60,000 instants share an ID, and files dropped together are created in the same millisecond. A collision made one completion replace every matching marker and update the wrong list item. `crypto.randomUUID` needs a secure context, so plain-http installations fall back to `crypto.getRandomValues`. Pasted images keep the legacy time-based visible name (`512-2026-10-1-22-30.png`); only the internal key changed.

## Tests

- `frontend/test/attachment-text.test.mjs`: link text, table conversion, clipboard detection, legacy names, sizes and key uniqueness without `randomUUID`
- `e2e/specs/06-issue/issue-attachments.spec.ts`, `e2e/specs/15-misc/markdown-editor-attachments.spec.ts`: upload, delete, paste, textarea drop, click insertion, spreadsheet paste, shadow isolation, form submission data, cached restoration, real detach/reconnect during an upload and the comment form (Chromium). The reconnect check delays a real backend response, configures while detached, and checks that the next paste uploads exactly once. Playwright CSS locators pierce open shadow roots; page-side DOM queries must use `element.shadowRoot` for uploader controls.

Vue-owned implementation verification (2026-10-08): Gradle `bootJar testFrontend` passed, including strict `vue-tsc` checks and all 15 unit tests. All 21 selected Chromium scenarios passed, including a real detached-microtask reconnect with an upload in flight, pre-upgrade editor values, native form/reset behavior, Turbo clones, preview sizing and security. A separate real-browser smoke configured a new uploader before its first connection, uploaded/deleted an image, and verified its native form field. The sample preview preserved its source and 1px help boundary with standard Vue custom-element slots.
