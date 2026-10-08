import {createApp, shallowReactive, type App} from 'vue';
import AttachmentsList from './AttachmentsList.ce.vue';
import {
  hasTextAndImage, legacyUploadName, linkText, markdownTable, uploadKey, uploadMarker,
} from './attachment-text';

export type AttachmentItem = {key: string; id?: string; name: string; url?: string; mimeType?: string; size: number; progress: number};
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
  private readonly state = shallowReactive<{items: AttachmentItem[]; dragging: boolean}>({items: [], dragging: false});
  private app?: App;

  get items(): AttachmentItem[] { return this.state.items; }
  set items(items: AttachmentItem[]) { this.state.items = items; }
  get dragging(): boolean { return this.state.dragging; }
  set dragging(dragging: boolean) { this.state.dragging = dragging; }
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
    if (this.shell && this.list && !this.app) {
      this.app = createApp(AttachmentsList, {
        state: this.state,
        shell: this.shell,
        onInsert: (item: AttachmentItem) => this.insertText(linkText(item)),
        onDelete: (item: AttachmentItem) => { void this.deleteAttachment(item); },
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
    for (const css of AttachmentsList.styles ?? []) {
      const style = document.createElement('style');
      style.textContent = css;
      this.shadowRoot!.append(style);
    }
    this.shadowRoot!.append(shell);
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

  private async deleteAttachment(item: AttachmentItem): Promise<void> {
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

  private patch(key: string, changes: Partial<AttachmentItem>): AttachmentItem | undefined {
    const items = [...this.items];
    let updated: AttachmentItem | undefined;
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
}

if (!customElements.get('yona-attachments')) customElements.define('yona-attachments', YonaAttachments);
