# Attachments

`<yona-attachments>` (`frontend/src/attachments/yona-attachments.ts`) is a light-DOM Lit custom element. It replaces the Vue widget of the same tag, whose source was not in the repository. The tag name, `data-resource-type`/`data-resource-id`, `.attached-file-marker` children and `configure({textarea, uploadURL, listURL, resourceType, resourceId})` are unchanged, so templates and `yona.Attachments`, `CodeCommentBox`, `issue.Detail` and `board/view.html` callers need no changes.

## Markup

`site/layout` renders `<template id="yona-attachments-template">` with the pre-Vue `common/uploadForm` markup and `common.attach.*` messages. Each element clones that shell into its light DOM on first connection; Lit renders only the `<li class="attached-file">` list into the shell's `<ul>`. Existing `yona.css` rules style it, so the Vue widget's embedded CSS copy is gone, and messages follow the user's locale instead of the widget's hard-coded Korean. A Turbo cache clone keeps its shell; its list restarts from the marker children, as the Vue widget's remount did.

`temporaryUploadFiles` is a real hidden input inside the shell and therefore part of the surrounding form.

## Internal structure

`mountShell` initializes the shell and marker-backed list; `bindShell` owns its event listeners. File selection, drop and image paste all reach `upload`, which inserts a marker only for textarea input. Request setup/progress lives in `createUploadRequest`, response parsing in `onUploadResponse`, and completion/failure in their respective state updates. Text formatting stays in the DOM-free `attachment-text.ts`, with explicit table-header preparation and alignment separators.

## Behavior

| Input | Result |
| --- | --- |
| File button, drop on the upload area | Upload; no text inserted |
| Image pasted into the textarea | `<!--_key_-->` at the caret, replaced by `![name](/files/N) ` after upload |
| File dropped on the textarea | Same marker and link per file (legacy `yona.Attachments._onDropFile`) |
| Text plus image on the clipboard (spreadsheet cells) | Markdown table, no upload (legacy `yona.Files`) |
| Click an uploaded item / its × | Insert its link at the caret / `POST _method=delete` and remove its link |
| Upload or delete failure | Item and marker removed; `common.attach.error.*` notification as in legacy |

Dragging plain text keeps the browser's native behavior. Images and videos use the legacy link text (videos keep the video.js wrapper).

## Upload keys

Markers and list items are keyed by a random UUID per upload. The legacy key (`seconds + milliseconds + "-Y-M-D-H-m"`) is not unique: 1 s 10 ms and 11 s 0 ms both give `110`, so 4,500 of a minute's 60,000 instants share an ID, and files dropped together are created in the same millisecond. A collision made one completion replace every matching marker and update the wrong list item. `crypto.randomUUID` needs a secure context, so plain-http installations fall back to `crypto.getRandomValues`. Pasted images keep the legacy time-based visible name (`512-2026-10-1-22-30.png`); only the internal key changed.

## Tests

- `frontend/test/attachment-text.test.mjs`: link text, table conversion, clipboard detection, legacy names, sizes and key uniqueness without `randomUUID`
- `e2e/specs/06-issue/issue-attachments.spec.ts`, `e2e/specs/15-misc/markdown-editor-attachments.spec.ts`: upload, delete, paste, textarea drop, click insertion, spreadsheet paste and the comment form (Chromium)
