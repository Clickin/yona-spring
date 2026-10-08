<script lang="ts">
import type {Ref, ShallowRef} from 'vue';

type AttachmentItem = {key: string; id?: string; name: string; url?: string; mimeType?: string; size: number; progress: number};
type Uploaded = {id: string | number; name: string; url: string; mimeType: string; size: number};
// Vue remounts after a real disconnect. Keep these instance-local refs alive for in-flight uploads.
const attachmentState = Symbol('attachmentState');
type AttachmentHost = HTMLElement & {
  [attachmentState]?: {items: Ref<AttachmentItem[]>; textarea: ShallowRef<HTMLTextAreaElement | null>};
};
</script>

<script setup lang="ts">
import {onMounted, onUnmounted, ref, shallowRef, useHost, watch} from 'vue';
import type {AttachmentOptions} from './yona-attachments';
import {
  hasTextAndImage, isVideo, legacyUploadName, linkText, markdownTable, readableSize, uploadKey, uploadMarker,
} from './attachment-text';

const props = defineProps<{configuration?: AttachmentOptions}>();
const host = useHost()! as AttachmentHost;
const state = host[attachmentState] ??= {
  items: ref<AttachmentItem[]>(Array.from(host.querySelectorAll<HTMLElement>(':scope > .attached-file-marker'), marker => ({
    key: marker.dataset.id ?? uploadKey(), id: marker.dataset.id, name: marker.dataset.name ?? '',
    url: marker.dataset.href, mimeType: marker.dataset.mime, size: Number(marker.dataset.size) || 0, progress: 100,
  }))),
  textarea: shallowRef<HTMLTextAreaElement | null>(null),
};
const {items, textarea} = state;
const dragging = ref(false);
const commentUpload = !!host.closest('.write-comment-box');
let mounted = false;
let uploadURL = '/files';
let listURL = '/files';

// Thymeleaf remains the source of translated labels; Vue owns all rendered uploader markup.
const template = document.getElementById('yona-attachments-template');
const shell = template instanceof HTMLTemplateElement ? template.content.firstElementChild : null;
if (!shell) console.error('yona-attachments: #yona-attachments-template is missing');
const label = (selector: string) => shell?.querySelector(selector)?.textContent ?? '';
const labels = {
  insert: shell?.getAttribute('data-insert-label') ?? '',
  delete: shell?.getAttribute('data-delete-label') ?? '',
  dropHelp: label('.help-droppable'), upload: label('.fake-file-wrap span'),
  selectHelp: label('.plain'), pasteHelp: label('.help-pastable'),
  saveHelp: label('p.help span'), drop: label('.upload-drop-here .msg'),
};

// A shadow input is not a successful native form control; reuse Turbo's light-DOM copy.
const temporary = host.querySelector<HTMLInputElement>(':scope > input[name="temporaryUploadFiles"]')
  ?? document.createElement('input');
temporary.type = 'hidden';
temporary.name = 'temporaryUploadFiles';
if (temporary.parentElement !== host) host.append(temporary);

function xsrfHeaders(): Record<string, string> {
  const token = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
  return token ? {'X-XSRF-TOKEN': decodeURIComponent(token[1] ?? '')} : {};
}

function notify(key: string, ...args: unknown[]): void {
  const globals = window as Window & {
    Messages?: (key: string, ...args: unknown[]) => string;
    $yona?: {notify?: (message: string, duration?: number) => void};
  };
  const message = globals.Messages?.(key, ...args);
  if (message) globals.$yona?.notify?.(message);
}

function configure(options: AttachmentOptions): void {
  if (mounted && textarea.value) unbindTextarea(textarea.value);
  textarea.value = options.textarea ?? null;
  if (mounted && textarea.value) bindTextarea(textarea.value);
  uploadURL = options.uploadURL ?? uploadURL;
  listURL = options.listURL ?? listURL;
  const type = options.resourceType ?? host.dataset.resourceType;
  const id = options.resourceId ?? host.dataset.resourceId;
  if (type) void loadList(type, id);
}

watch(() => props.configuration, options => {
  if (options) configure(options);
}, {immediate: true, flush: 'sync'});

onMounted(() => {
  mounted = true;
  if (textarea.value) bindTextarea(textarea.value);
});
onUnmounted(() => {
  mounted = false;
  if (textarea.value) unbindTextarea(textarea.value);
  dragging.value = false;
});

const onTextareaDrop = (event: DragEvent) => onDrop(event, true);
function bindTextarea(element: HTMLTextAreaElement): void {
  element.addEventListener('paste', onPaste);
  element.addEventListener('dragover', onDragOver);
  element.addEventListener('drop', onTextareaDrop);
}
function unbindTextarea(element: HTMLTextAreaElement): void {
  element.removeEventListener('paste', onPaste);
  element.removeEventListener('dragover', onDragOver);
  element.removeEventListener('drop', onTextareaDrop);
}

function onFileSelection(event: Event): void {
  const input = event.target as HTMLInputElement;
  for (const file of Array.from(input.files ?? [])) upload(file);
  input.value = '';
}

const hasFiles = (event: DragEvent) => Array.from(event.dataTransfer?.types ?? []).includes('Files');
function onDragOver(event: DragEvent): void {
  if (!hasFiles(event)) return;
  event.preventDefault();
  event.stopPropagation();
  dragging.value = true;
}

/** Only a drop onto the textarea inserts links; text drops stay native. */
function onDrop(event: DragEvent, intoTextarea: boolean): void {
  if (!hasFiles(event)) return;
  event.preventDefault();
  event.stopPropagation();
  dragging.value = false;
  for (const file of Array.from(event.dataTransfer?.files ?? [])) upload(file, intoTextarea);
}

function onPaste(event: ClipboardEvent): void {
  const clipboard = event.clipboardData;
  if (!clipboard) return;
  if (hasTextAndImage(clipboard.items)) {
    event.preventDefault();
    insertText(markdownTable(clipboard.getData('text/plain').trim()));
    return;
  }
  for (const entry of Array.from(clipboard.items)) {
    if (entry.kind !== 'file' || !entry.type.startsWith('image')) continue;
    const blob = entry.getAsFile();
    if (!blob) continue;
    upload(new File([blob], `${legacyUploadName()}.png`, {type: blob.type}), true);
    event.preventDefault();
  }
}

function upload(file: File, intoTextarea = false): void {
  const key = uploadKey();
  if (intoTextarea) insertText(uploadMarker(key));
  const name = file.name === 'image.png' ? `${legacyUploadName()}.png` : file.name;
  items.value.unshift({key, name, size: file.size, progress: 0});
  const body = new FormData();
  body.append('filePath', file, name);
  const request = new XMLHttpRequest();
  request.open('POST', uploadURL);
  for (const [header, value] of Object.entries(xsrfHeaders())) request.setRequestHeader(header, value);
  request.upload?.addEventListener('progress', event => {
    if (event.lengthComputable) patch(key, {progress: Math.ceil(event.loaded / event.total * 100)});
  });
  request.addEventListener('load', () => onUploadResponse(key, request));
  request.addEventListener('error', () => failUpload(key, 0, 'network error'));
  request.send(body);
}

function onUploadResponse(key: string, request: XMLHttpRequest): void {
  if (request.status < 200 || request.status >= 300) {
    failUpload(key, request.status, request.statusText);
    return;
  }
  let uploaded: Uploaded;
  try {
    uploaded = JSON.parse(request.responseText);
  } catch {
    failUpload(key, request.status, 'invalid response');
    return;
  }
  const id = String(uploaded.id);
  setTemporary(id, true);
  const item = patch(key, {
    id, name: uploaded.name, url: uploaded.url, mimeType: uploaded.mimeType, size: uploaded.size, progress: 100,
  });
  if (item) replaceText(uploadMarker(key), linkText(item));
  saveMarkers();
}

function failUpload(key: string, status: number, statusText: string): void {
  items.value = items.value.filter(item => item.key !== key);
  removeText(uploadMarker(key));
  console.error('yona-attachments: upload failed', status, statusText);
  notify('common.attach.error.upload', status, statusText);
}

async function deleteAttachment(item: AttachmentItem): Promise<void> {
  if (!item.url) return;
  try {
    const response = await fetch(item.url, {
      method: 'post', headers: xsrfHeaders(), body: new URLSearchParams({_method: 'delete'}),
    });
    if (!response.ok) throw response;
    if (item.id) setTemporary(item.id, false);
    removeText(linkText(item));
    items.value = items.value.filter(candidate => candidate.key !== item.key);
    saveMarkers();
  } catch (error) {
    const response = error instanceof Response ? error : undefined;
    console.error('yona-attachments: delete failed', response?.status);
    notify('common.attach.error.delete', response?.status ?? 0, response?.statusText ?? '');
  }
}

async function loadList(resourceType: string, resourceId?: string): Promise<void> {
  const query = new URLSearchParams({containerType: resourceType, containerId: resourceId ?? ''});
  try {
    const response = await fetch(`${listURL}?${query}`);
    if (!response.ok) return;
    const body = await response.json() as {attachments?: Uploaded[]; tempFiles?: Uploaded[]};
    const files = [...body.attachments ?? [], ...(resourceId ? [] : body.tempFiles ?? [])];
    const known = new Set(items.value.map(item => item.id));
    // configure() may run again for the same form; keep each attachment once.
    for (const file of files) {
      const id = String(file.id);
      if (known.has(id)) continue;
      known.add(id);
      items.value.push({key: id, id, name: file.name, url: file.url, mimeType: file.mimeType, size: file.size, progress: 100});
    }
    saveMarkers();
  } catch {
    // A failed list request leaves new uploads working.
  }
}

function patch(key: string, changes: Partial<AttachmentItem>): AttachmentItem | undefined {
  const item = items.value.find(candidate => candidate.key === key);
  if (item) Object.assign(item, changes);
  return item;
}

function setTemporary(id: string, present: boolean): void {
  let ids = temporary.value ? temporary.value.split(',') : [];
  if (present) {
    if (!ids.includes(id)) ids.push(id);
  } else {
    ids = ids.filter(value => value !== id);
  }
  temporary.value = ids.join(',');
}

function saveMarkers(): void {
  // Turbo cloneNode snapshots exclude shadow roots; keep completed items as inert data.
  host.querySelectorAll(':scope > .attached-file-marker').forEach(marker => marker.remove());
  for (const item of items.value) {
    if (!item.id) continue;
    const marker = document.createElement('span');
    marker.className = 'attached-file-marker';
    marker.hidden = true;
    Object.assign(marker.dataset, {
      id: item.id, name: item.name, href: item.url ?? '', mime: item.mimeType ?? '', size: String(item.size),
    });
    host.append(marker);
  }
}

function insertText(text: string): void {
  const element = textarea.value;
  if (!element) return;
  const position = element.selectionStart ?? element.value.length;
  element.value = element.value.slice(0, position) + text + element.value.slice(position);
  element.setSelectionRange(position + text.length, position + text.length);
  syncEditor(element);
}

function removeText(text: string): void {
  const element = textarea.value;
  if (!element) return;
  element.value = element.value.split(text).join('').split(text.trim()).join('');
  syncEditor(element);
}

function replaceText(from: string, to: string): void {
  const element = textarea.value;
  if (!element) return;
  const position = element.selectionStart ?? 0;
  const gap = to.length - from.length - 1;
  element.value = element.value.split(from).join(to);
  if (gap > 0) element.setSelectionRange(position + gap, position + gap);
  syncEditor(element);
}

/** Exit a stale preview and notify draft listeners through the editor's value setter. */
function syncEditor(element: HTMLTextAreaElement): void {
  const editor = element.closest<HTMLElement & {value: string}>('yona-markdown-editor');
  if (editor) editor.value = element.value;
}
</script>

<template>
  <div
    v-if="shell"
    class="upload-wrap content-footer"
    :class="{'comment-upload': commentUpload}"
    @dragover="onDragOver"
    @dragleave.prevent="dragging = false"
    @drop="onDrop($event, false)"
  >
    <div class="attach-wrap">
      <span class="help help-droppable">{{ labels.dropHelp }}</span>
      <div class="btn-wrap">
        <div class="nbtn medium white fake-file-wrap">
          <i class="yobicon-upload" aria-hidden="true"></i> <span>{{ labels.upload }}</span>
          <input type="file" class="file" multiple :aria-label="labels.upload" @change="onFileSelection">
        </div>
      </div>
      <span class="plain">{{ labels.selectHelp }}</span>
      <span class="help help-pastable">{{ labels.pasteHelp }}</span>
    </div>
    <ul v-show="items.length" class="attached-files unstyled">
      <li
        v-for="item in items"
        :key="item.key"
        class="attached-file"
        :class="item.id ? 'complete' : 'temporary'"
        :id="item.id ? undefined : item.key"
        :data-id="item.id"
        :data-name="item.name"
        :data-href="item.url"
        :data-mime="item.mimeType"
        @click="item.id && insertText(linkText(item))"
      >
        <i class="yobicon-supportrequest" aria-hidden="true"></i>
        <i class="mimetype" :class="{'yobicon-video2': isVideo(item.mimeType)}" aria-hidden="true"></i>
        <strong class="name">{{ item.name }}</strong>
        <span class="size">{{ readableSize(item.size) }}</span>
        <div class="progress upload-progress" role="progressbar" :aria-label="item.name" :aria-valuemin="0" :aria-valuemax="100" :aria-valuenow="item.progress">
          <div class="bar orange" :style="{width: `${item.progress}%`}"></div>
        </div>
        <button type="button" class="btn-transparent btn-delete" :disabled="!item.id" :aria-label="`${labels.delete}: ${item.name}`" @click.stop="deleteAttachment(item)">×</button>
        <button type="button" class="nbtn small white btn-insert" :disabled="!item.id">{{ labels.insert }}</button>
      </li>
    </ul>
    <p v-show="items.length" class="right-txt help">
      <i class="yobicon-supportrequest" aria-hidden="true"></i> <span>{{ labels.saveHelp }}</span>
    </p>
    <div v-show="dragging" class="upload-drop-here"><div class="msg-wrap"><div class="msg">{{ labels.drop }}</div></div></div>
  </div>
</template>

<style>
:host { display: block; color: inherit; font: inherit; }
button, input { font: inherit; }
button { cursor: pointer; }
button:focus-visible, .fake-file-wrap:focus-within { outline: 2px solid #3a7ee5; outline-offset: 2px; }
.upload-wrap { position: relative; padding: 10px; background: #f5f5f5; border-radius: 5px; }
.comment-upload { background: #efefef; margin-bottom: 10px; border-radius: 0 0 5px 5px; }
.attach-wrap { text-align: center; }
.attach-wrap .btn-wrap { display: inline-block; margin: 0 5px; vertical-align: top; }
.attach-wrap .plain { display: inline-block; line-height: 30px; }
.nbtn { display: inline-block; border: 0; border-radius: 2px; color: #222; background: #fff;
  font-size: 11px; font-weight: bold; line-height: 18px; text-align: center; white-space: nowrap;
  box-shadow: inset 0 -1px 1px #0005; margin-right: 5px; }
.nbtn.medium { padding: 6px 20px; }
.nbtn:hover { background: #e6e6e6; color: #f36c22; }
.fake-file-wrap { position: relative; overflow: hidden; cursor: pointer; }
.fake-file-wrap .file { position: absolute; inset: 0; width: 100%; height: 100%; opacity: 0; cursor: pointer; }
.attached-files { list-style: none; margin: 15px 0 0; padding: 15px 0; border-top: 1px solid #e0e0e0; }
.attached-file { display: inline-flex; align-items: center; gap: 3px; max-width: calc(100% - 30px);
  height: 30px; line-height: 30px; border: 1px solid #ccc; background: #fafafa;
  padding: 0 10px; margin: 5px 4px; cursor: pointer; }
.attached-file:hover { border-color: #f36c22; }
.attached-file i { display: none; color: #3a7ee5; }
.attached-file.temporary i { display: inline-block; }
.attached-file .name { min-width: 0; max-width: 250px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.attached-file .size { font-size: 11px; white-space: nowrap; }
.upload-progress { width: 100px; height: 7px; overflow: hidden; background: #f0f0f0; border: 1px solid #e0e0e0; }
.upload-progress .bar { height: 100%; background: #f36c22; }
.btn-delete { display: none; flex-shrink: 0; width: 30px; height: 30px; border: 0; padding: 0;
  background: transparent; font-size: 1.5em; font-weight: bold; }
.btn-delete:hover { color: #f36c22; }
.btn-insert { display: none; padding: 1px 10px; height: 24px; line-height: 20px; box-shadow: none; }
.attached-file.complete .progress { display: none; }
.attached-file.complete .btn-delete, .attached-file.complete .btn-insert { display: inline-block; }
.right-txt { text-align: right; }
p.help { margin: 0 0 10px; }
.upload-drop-here { position: absolute; inset: 2px; border: 3px dashed #ffb23d;
  background: #fffc; z-index: 1; pointer-events: none; }
.msg-wrap { display: flex; align-items: center; justify-content: center; height: 100%; }
.msg { color: #999; font-size: 26px; text-align: center; }
i { font-family: yobicon; font-style: normal; font-weight: normal; }
.yobicon-upload::before { content: "\e4bd"; }
.yobicon-supportrequest::before { content: "\e203"; }
.yobicon-video2::before { content: "\e19e"; }
</style>
