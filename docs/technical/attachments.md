# Attachments

`<yona-attachments>` is a Vue SFC-backed custom element with an open Shadow DOM. `frontend/src/attachments/AttachmentsList.ce.vue` owns the list UI and uploader styles; `yona-attachments.ts` is the native host and upload bridge. The tag name, `data-resource-type`/`data-resource-id`, `.attached-file-marker` children and `configure({textarea, uploadURL, listURL, resourceType, resourceId})` are unchanged, so templates and `yona.Attachments`, `CodeCommentBox`, `issue.Detail` and `board/view.html` callers need no changes.

## Markup

`site/layout` renders `<template id="yona-attachments-template">` with the `common/uploadForm` markup and localized `common.attach.*` messages. Each element clones that shell into its shadow root on first connection; `AttachmentsList.ce.vue` renders the `<li class="attached-file">` list through a real Vue `<template>` and `<script setup lang="ts">`. Its `<style>` is compiled in custom-element mode by `unplugin-vue/esbuild` and injected into that same shadow root, without importing page-wide stylesheets. Shadow DOM supplies style isolation, so the SFC styles are not additionally `scoped`; they also style the server-rendered shell. Upload and delete controls have localized accessible labels; insertion uses a native keyboard-accessible button. Filenames use escaped template interpolation, not HTML rendering.

`temporaryUploadFiles` is moved out of the shell into the host's light DOM, so it remains a successful control in the surrounding form. Completed attachments are mirrored into hidden `.attached-file-marker` light-DOM children: Turbo's `cloneNode` snapshots exclude shadow roots, so restored elements rebuild their shell and list from those markers and reuse the cached hidden input. Disconnecting unmounts Vue and removes textarea listeners; reconnecting mounts again without duplicating listeners or resetting completed uploads.

## Internal structure

`mountShell` initializes the shell and marker-backed list; `bindShell` owns its event listeners. A shallow reactive state object carries the list and drag state into the SFC; its setup effect updates the server-rendered help/drop indicator, and its template emits insert/delete actions back to the bridge. File selection, drop and image paste all reach `upload`, which inserts a marker only for textarea input. Request setup/progress lives in `createUploadRequest`, response parsing in `onUploadResponse`, and completion/failure in their respective state updates. Text formatting stays in the DOM-free `attachment-text.ts`, with explicit table-header preparation and alignment separators.

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
- `e2e/specs/06-issue/issue-attachments.spec.ts`, `e2e/specs/15-misc/markdown-editor-attachments.spec.ts`: upload, delete, paste, textarea drop, click insertion, spreadsheet paste, shadow isolation, form submission data, cached restoration and the comment form (Chromium). Playwright CSS locators pierce open shadow roots; page-side DOM queries must use `element.shadowRoot` for uploader controls.

SFC verification (2026-10-08): Gradle `bootJar testFrontend` passed, including `vue-tsc` strict template checks and all 15 unit tests. The integrated branch's generated assets passed 18 Chromium component/editor/attachment checks, including delayed upgrade, shadow styles, native slots, upload/delete, paste/drop, spreadsheet tables and Turbo restoration. The actual sample preview retained its original form value and 1px help boundary; renderer, editor and attachment list were mounted from their `.ce.vue` components.
