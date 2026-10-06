import {LitElement, html, nothing} from 'lit';
import {repeat} from 'lit/directives/repeat.js';
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
 * Light-DOM attachment uploader. The shell markup and messages come from the server-rendered
 * <template id="yona-attachments-template"> (site/layout); Lit renders only the file list.
 * `configure({textarea})` keeps the contract of the Vue widget this replaces.
 */
export class YonaAttachments extends LitElement {
  static properties = {items: {state: true}, dragging: {state: true}};
  declare items: Item[];
  declare dragging: boolean;
  private shell?: HTMLElement;
  private list?: HTMLUListElement;
  private temporary?: HTMLInputElement;
  private textarea: HTMLTextAreaElement | null = null;
  private uploadURL = '/files';
  private listURL = '/files';

  constructor() {
    super();
    this.items = [];
    this.dragging = false;
  }

  connectedCallback() {
    if (!this.shell) this.mountShell();
    super.connectedCallback();
    if (this.textarea) this.bindTextarea(this.textarea);
  }

  disconnectedCallback() {
    if (this.textarea) this.unbindTextarea(this.textarea);
    super.disconnectedCallback();
  }

  protected createRenderRoot() {
    return this.list ?? document.createElement('ul');
  }

  /** Same contract as the Vue widget: attach a textarea and load the resource's attachments. */
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
    // A Turbo cache clone already carries the shell; keep it and let Lit render the list again.
    let shell = this.querySelector<HTMLElement>(':scope > .upload-wrap');
    if (shell) shell.querySelector('.attached-files')?.replaceChildren();
    else {
      const template = document.getElementById('yona-attachments-template');
      const source = template instanceof HTMLTemplateElement ? template.content.firstElementChild : null;
      if (!source) {
        console.error('yona-attachments: #yona-attachments-template is missing');
        return;
      }
      shell = source.cloneNode(true) as HTMLElement;
      this.prepend(shell);
    }
    this.shell = shell;
    this.list = shell.querySelector<HTMLUListElement>('.attached-files') ?? undefined;
    this.temporary = shell.querySelector<HTMLInputElement>('input[name="temporaryUploadFiles"]') ?? undefined;
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

  protected updated(): void {
    const visible = this.items.length > 0 ? 'block' : 'none';
    if (this.list) this.list.style.display = visible;
    const help = this.shell?.querySelector<HTMLElement>(':scope > p.help');
    if (help) help.style.display = visible;
    const dropper = this.shell?.querySelector<HTMLElement>(':scope > .upload-drop-here');
    if (dropper) dropper.style.display = this.dragging ? 'block' : 'none';
  }

  protected render() {
    const insertLabel = this.shell?.dataset.insertLabel ?? '';
    return repeat(this.items, item => item.key, item => html`
      <li class="attached-file ${item.id ? 'complete' : 'temporary'}" id=${item.id ? nothing : item.key}
          data-id=${item.id ?? nothing} data-name=${item.name} data-href=${item.url ?? nothing}
          data-mime=${item.mimeType ?? nothing} @click=${(event: Event) => this.onItemClick(item, event)}>
        <i class="yobicon-supportrequest"></i>
        <i class="mimetype ${isVideo(item.mimeType) ? 'yobicon-video2' : ''}"></i>
        <strong class="name">${item.name}</strong>
        <span class="size">${readableSize(item.size)}</span>
        <div class="pull-right"><div class="progress upload-progress"><div class="bar orange" style="width: ${item.progress}%"></div></div></div>
        <button type="button" class="btn-transparent btn-delete pull-right">&times;</button>
        <span class="pull-right nbtn small white btn-insert">${insertLabel}</span>
      </li>`);
  }
}

if (!customElements.get('yona-attachments')) customElements.define('yona-attachments', YonaAttachments);
