import {createApp, h, shallowRef, watchEffect, type App} from 'vue';
import {
  hasTextAndImage, isVideo, legacyUploadName, linkText, markdownTable, readableSize, uploadKey, uploadMarker,
} from './attachment-text';

type Item = {key: string; id?: string; name: string; url?: string; mimeType?: string; size: number; progress: number};
type Uploaded = {id: string | number; name: string; url: string; mimeType: string; size: number};
export type AttachmentOptions = {
  textarea?: HTMLTextAreaElement | null; uploadURL?: string; listURL?: string; resourceType?: string; resourceId?: string;
};
type Globals = Window & {
  Messages?: (key: string, ...args: unknown[]) => string;
  $yona?: {notify?: (message: string, duration?: number) => void};
};

const hasFiles = (event: DragEvent) => Array.from(event.dataTransfer?.types ?? []).includes('Files');

function xsrfHeaders(): Record<string, string> {
  const token = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
  return token ? {'X-XSRF-TOKEN': decodeURIComponent(token[1] ?? '')} : {};
}

function notify(key: string, ...args: unknown[]): void {
  const globals = window as Globals;
  const message = globals.Messages?.(key, ...args);
  if (message) globals.$yona?.notify?.(message);
}

/**
 * The localized Thymeleaf shell and Vue file list live in a shadow root.
 * Form inputs and attachment markers stay in light DOM for forms and Turbo snapshots.
 */
export class YonaAttachments extends HTMLElement {
  private readonly fileItems = shallowRef<Item[]>([]);
  private readonly dragActive = shallowRef(false);
  private app?: App;

  get items(): Item[] { return this.fileItems.value; }
  set items(items: Item[]) { this.fileItems.value = items; }
  get dragging(): boolean { return this.dragActive.value; }
  set dragging(dragging: boolean) { this.dragActive.value = dragging; }
  private shell?: HTMLElement;
  private list?: HTMLUListElement;
  private temporary?: HTMLInputElement;
  private textarea: HTMLTextAreaElement | null = null;
  private uploadURL = '/files';
  private listURL = '/files';

  constructor() {
    super();
    this.attachShadow({mode: 'open'});
  }

  connectedCallback() {
    if (!this.shell) this.mountShell();
    if (this.list && !this.app) {
      this.app = createApp({
        setup: () => {
          watchEffect(() => this.updateShell());
          return () => this.render();
        },
      });
      this.app.mount(this.list);
    }
    if (this.textarea) this.bindTextarea(this.textarea);
  }

  disconnectedCallback() {
    if (this.textarea) this.unbindTextarea(this.textarea);
    this.app?.unmount();
    this.app = undefined;
    this.dragging = false;
  }

  /** Attach a textarea and load the resource's attachments. */
  configure(options: AttachmentOptions = {}): void {
    if (this.textarea) this.unbindTextarea(this.textarea);
    this.textarea = options.textarea ?? null;
    if (this.textarea && this.isConnected) this.bindTextarea(this.textarea);
    this.uploadURL = options.uploadURL ?? this.uploadURL;
    this.listURL = options.listURL ?? this.listURL;
    const type = options.resourceType ?? this.dataset.resourceType;
    const id = options.resourceId ?? this.dataset.resourceId;
    if (type) void this.loadList(type, id);
  }

  private mountShell(): void {
    const template = document.getElementById('yona-attachments-template');
    const source = template instanceof HTMLTemplateElement ? template.content.firstElementChild : null;
    if (!source) {
      console.error('yona-attachments: #yona-attachments-template is missing');
      return;
    }
    const shell = source.cloneNode(true) as HTMLElement;
    shell.classList.toggle('comment-upload', !!this.closest('.write-comment-box'));
    const style = document.createElement('style');
    style.textContent = styles;
    this.shadowRoot!.append(style, shell);
    this.shell = shell;
    this.list = shell.querySelector<HTMLUListElement>('.attached-files') ?? undefined;
    // Shadow inputs do not participate in the surrounding form. Reuse a cached input if present.
    const input = shell.querySelector<HTMLInputElement>('input[name="temporaryUploadFiles"]');
    this.temporary = this.querySelector<HTMLInputElement>(':scope > input[name="temporaryUploadFiles"]') ?? input ?? undefined;
    input?.remove();
    if (this.temporary) this.append(this.temporary);
    // Comment edit forms render existing attachments as invisible marker children.
    this.items = Array.from(this.querySelectorAll<HTMLElement>(':scope > .attached-file-marker'), marker => ({
      key: marker.dataset.id ?? uploadKey(), id: marker.dataset.id, name: marker.dataset.name ?? '',
      url: marker.dataset.href, mimeType: marker.dataset.mime, size: Number(marker.dataset.size) || 0, progress: 100,
    }));
    this.bindShell(shell);
  }

  private bindShell(shell: HTMLElement): void {
    shell.addEventListener('dragover', event => this.onDragOver(event));
    shell.addEventListener('dragleave', event => {
      event.preventDefault();
      this.dragging = false;
    });
    shell.addEventListener('drop', event => this.onDrop(event, false));
    shell.querySelector<HTMLInputElement>('input[type="file"]')
      ?.addEventListener('change', event => this.onFileSelection(event));
  }

  private onFileSelection(event: Event): void {
    const input = event.target as HTMLInputElement;
    for (const file of Array.from(input.files ?? [])) this.upload(file);
    input.value = '';
  }

  private readonly onTextareaPaste = (event: ClipboardEvent) => this.onPaste(event);
  private readonly onTextareaDragOver = (event: DragEvent) => this.onDragOver(event);
  private readonly onTextareaDrop = (event: DragEvent) => this.onDrop(event, true);

  private bindTextarea(textarea: HTMLTextAreaElement): void {
    textarea.addEventListener('paste', this.onTextareaPaste);
    textarea.addEventListener('dragover', this.onTextareaDragOver);
    textarea.addEventListener('drop', this.onTextareaDrop);
  }

  private unbindTextarea(textarea: HTMLTextAreaElement): void {
    textarea.removeEventListener('paste', this.onTextareaPaste);
    textarea.removeEventListener('dragover', this.onTextareaDragOver);
    textarea.removeEventListener('drop', this.onTextareaDrop);
  }

  private onDragOver(event: DragEvent): void {
    if (!hasFiles(event)) return;
    event.preventDefault();
    event.stopPropagation();
    this.dragging = true;
  }

  /** Legacy yona.Attachments: only a drop onto the textarea inserts links; text drops stay native. */
  private onDrop(event: DragEvent, intoTextarea: boolean): void {
    if (!hasFiles(event)) return;
    event.preventDefault();
    event.stopPropagation();
    this.dragging = false;
    for (const file of Array.from(event.dataTransfer?.files ?? [])) this.upload(file, intoTextarea);
  }

  private onPaste(event: ClipboardEvent): void {
    const items = event.clipboardData?.items;
    if (!items) return;
    if (hasTextAndImage(items)) {
      event.preventDefault();
      this.insertText(markdownTable(event.clipboardData!.getData('text/plain').trim()));
      return;
    }
    for (const entry of Array.from(items)) {
      if (entry.kind !== 'file' || !entry.type.startsWith('image')) continue;
      const blob = entry.getAsFile();
      if (!blob) continue;
      const name = `${legacyUploadName()}.png`;
      this.upload(new File([blob], name, {type: blob.type}), true);
      event.preventDefault();
    }
  }

  private upload(file: File, intoTextarea = false): void {
    const key = uploadKey();
    if (intoTextarea) this.insertText(uploadMarker(key));
    // Browsers name some clipboard images image.png; legacy gave them a time-based name.
    const name = file.name === 'image.png' ? `${legacyUploadName()}.png` : file.name;
    this.items = [{key, name, size: file.size, progress: 0}, ...this.items];
    const body = new FormData();
    body.append('filePath', file, name);
    const request = this.createUploadRequest(key);
    request.send(body);
  }

  private createUploadRequest(key: string): XMLHttpRequest {
    const request = new XMLHttpRequest();
    request.open('POST', this.uploadURL);
    for (const [header, value] of Object.entries(xsrfHeaders())) request.setRequestHeader(header, value);
    request.upload?.addEventListener('progress', event => {
      if (event.lengthComputable) this.patch(key, {progress: Math.ceil(event.loaded / event.total * 100)});
    });
    request.addEventListener('load', () => this.onUploadResponse(key, request));
    request.addEventListener('error', () => this.failUpload(key, 0, 'network error'));
    return request;
  }

  private onUploadResponse(key: string, request: XMLHttpRequest): void {
    if (request.status < 200 || request.status >= 300) {
      this.failUpload(key, request.status, request.statusText);
      return;
    }
    let uploaded: Uploaded;
    try {
      uploaded = JSON.parse(request.responseText);
    } catch {
      this.failUpload(key, request.status, 'invalid response');
      return;
    }
    this.completeUpload(key, uploaded);
  }

  private completeUpload(key: string, uploaded: Uploaded): void {
    const id = String(uploaded.id);
    this.setTemporary(id, true);
    const item = this.patch(key, {
      id, name: uploaded.name, url: uploaded.url, mimeType: uploaded.mimeType, size: uploaded.size, progress: 100,
    });
    if (item) this.replaceText(uploadMarker(key), linkText(item));
    this.saveMarkers();
  }

  private failUpload(key: string, status: number, statusText: string): void {
    this.items = this.items.filter(item => item.key !== key);
    this.removeText(uploadMarker(key));
    console.error('yona-attachments: upload failed', status, statusText);
    notify('common.attach.error.upload', status, statusText);
  }

  private async deleteAttachment(item: Item): Promise<void> {
    if (!item.url) return;
    try {
      const response = await fetch(item.url, {
        method: 'post', headers: xsrfHeaders(), body: new URLSearchParams({_method: 'delete'}),
      });
      if (!response.ok) throw response;
      if (item.id) this.setTemporary(item.id, false);
      this.removeText(linkText(item));
      this.items = this.items.filter(candidate => candidate.key !== item.key);
      this.saveMarkers();
    } catch (error) {
      const response = error instanceof Response ? error : undefined;
      console.error('yona-attachments: delete failed', response?.status);
      notify('common.attach.error.delete', response?.status ?? 0, response?.statusText ?? '');
    }
  }

  private async loadList(resourceType: string, resourceId?: string): Promise<void> {
    const query = new URLSearchParams({containerType: resourceType, containerId: resourceId ?? ''});
    try {
      const response = await fetch(`${this.listURL}?${query}`);
      if (!response.ok) return;
      const body = await response.json() as {attachments?: Uploaded[]; tempFiles?: Uploaded[]};
      const files = [...body.attachments ?? [], ...(resourceId ? [] : body.tempFiles ?? [])];
      const known = new Set(this.items.map(item => item.id));
      // configure() may run again for the same form; keep each attachment once.
      const added = files.filter(file => !known.has(String(file.id))).map(file => ({
        key: String(file.id), id: String(file.id), name: file.name, url: file.url, mimeType: file.mimeType,
        size: file.size, progress: 100,
      }));
      if (added.length) this.items = [...this.items, ...added];
      this.saveMarkers();
    } catch {
      // A failed list request leaves new uploads working, as in the Vue widget.
    }
  }

  private patch(key: string, changes: Partial<Item>): Item | undefined {
    const items = [...this.items];
    let updated: Item | undefined;
    for (let index = 0; index < items.length; index++) {
      const item = items[index];
      if (item.key !== key) continue;
      updated = {...item, ...changes};
      items[index] = updated;
    }
    this.items = items;
    return updated;
  }

  private setTemporary(id: string, present: boolean): void {
    if (!this.temporary) return;
    let ids = this.temporary.value ? this.temporary.value.split(',') : [];
    if (present) {
      if (!ids.includes(id)) ids.push(id);
    } else {
      ids = ids.filter(value => value !== id);
    }
    this.temporary.value = ids.join(',');
  }

  private saveMarkers(): void {
    // Turbo cloneNode snapshots exclude shadow roots; keep completed items as inert data.
    this.querySelectorAll(':scope > .attached-file-marker').forEach(marker => marker.remove());
    for (const item of this.items) {
      if (!item.id) continue;
      const marker = document.createElement('span');
      marker.className = 'attached-file-marker';
      marker.hidden = true;
      Object.assign(marker.dataset, {
        id: item.id, name: item.name, href: item.url ?? '', mime: item.mimeType ?? '', size: String(item.size),
      });
      this.append(marker);
    }
  }

  private insertText(text: string): void {
    const textarea = this.textarea;
    if (!textarea) return;
    const position = textarea.selectionStart ?? textarea.value.length;
    textarea.value = textarea.value.slice(0, position) + text + textarea.value.slice(position);
    textarea.setSelectionRange(position + text.length, position + text.length);
    this.syncEditor(textarea);
  }

  private removeText(text: string): void {
    const textarea = this.textarea;
    if (!textarea) return;
    textarea.value = textarea.value.split(text).join('').split(text.trim()).join('');
    this.syncEditor(textarea);
  }

  private replaceText(from: string, to: string): void {
    const textarea = this.textarea;
    if (!textarea) return;
    const position = textarea.selectionStart ?? 0;
    const gap = to.length - from.length - 1;
    textarea.value = textarea.value.split(from).join(to);
    if (gap > 0) textarea.setSelectionRange(position + gap, position + gap);
    this.syncEditor(textarea);
  }

  /** Exits a stale preview and notifies draft listeners through the editor's value setter. */
  private syncEditor(textarea: HTMLTextAreaElement): void {
    const editor = textarea.closest<HTMLElement & {value: string}>('yona-markdown-editor');
    if (editor) editor.value = textarea.value;
  }

  private onItemClick(item: Item, event: Event): void {
    if (!item.id) return;
    if ((event.target as Element).closest('.btn-delete')) void this.deleteAttachment(item);
    else this.insertText(linkText(item));
  }

  private updateShell(): void {
    const visible = this.items.length > 0 ? 'block' : 'none';
    if (this.list) this.list.style.display = visible;
    const help = this.shell?.querySelector<HTMLElement>(':scope > p.help');
    if (help) help.style.display = visible;
    const dropper = this.shell?.querySelector<HTMLElement>(':scope > .upload-drop-here');
    if (dropper) dropper.style.display = this.dragging ? 'block' : 'none';
  }

  private render() {
    const insertLabel = this.shell?.dataset.insertLabel ?? '';
    const deleteLabel = this.shell?.dataset.deleteLabel ?? '';
    return this.items.map(item => h('li', {
      key: item.key, class: ['attached-file', item.id ? 'complete' : 'temporary'],
      id: item.id ? undefined : item.key,
      'data-id': item.id, 'data-name': item.name, 'data-href': item.url, 'data-mime': item.mimeType,
      onClick: (event: Event) => this.onItemClick(item, event),
    }, [
      h('i', {class: 'yobicon-supportrequest', 'aria-hidden': 'true'}),
      h('i', {class: ['mimetype', isVideo(item.mimeType) ? 'yobicon-video2' : ''], 'aria-hidden': 'true'}),
      h('strong', {class: 'name'}, item.name),
      h('span', {class: 'size'}, readableSize(item.size)),
      h('div', {
        class: 'progress upload-progress', role: 'progressbar', 'aria-label': item.name,
        'aria-valuemin': 0, 'aria-valuemax': 100, 'aria-valuenow': item.progress,
      }, [h('div', {class: 'bar orange', style: {width: `${item.progress}%`}})]),
      h('button', {
        type: 'button', class: 'btn-transparent btn-delete', disabled: !item.id,
        'aria-label': `${deleteLabel}: ${item.name}`,
      }, '×'),
      h('button', {type: 'button', class: 'nbtn small white btn-insert', disabled: !item.id}, insertLabel),
    ]));
  }
}

const styles = `
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
  .yobicon-upload::before { content: "\\e4bd"; }
  .yobicon-supportrequest::before { content: "\\e203"; }
  .yobicon-video2::before { content: "\\e19e"; }
`;

if (!customElements.get('yona-attachments')) customElements.define('yona-attachments', YonaAttachments);
