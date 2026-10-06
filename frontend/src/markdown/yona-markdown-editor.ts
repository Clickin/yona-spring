import {LitElement, html, nothing} from 'lit';
import TextExpanderElement, {type TextExpanderChangeEvent} from '@github/text-expander-element';
import {YonaMarkdownRenderer} from './yona-markdown-renderer';
import {emoji} from './emoji';

let nextEditorId = 0;

type Suggestion = {value: string; label: string; image?: string};

/** The server's actual textarea owns the value and native form/reset semantics. */
export class YonaMarkdownEditor extends LitElement {
  private textarea?: HTMLTextAreaElement;
  private expander?: TextExpanderElement;
  private preview?: YonaMarkdownRenderer;
  private helpMarkup?: HTMLElement;
  private notice?: HTMLElement;
  private lifetime?: AbortController;
  private query?: AbortController;

  protected createRenderRoot() { return this; }

  connectedCallback() {
    const form = (this.textarea ?? this.querySelector('textarea'))?.form;
    if (!this.textarea) this.initializeTextarea();
    super.connectedCallback();
    if (this.textarea && this.expander) this.bindEvents(this.textarea, this.expander, form);
  }

  private initializeTextarea() {
    const textarea = this.querySelector('textarea');
    if (!textarea) return;
    const wrapper = this.closest('[data-toggle="markdown-editor"]');
    this.helpMarkup = wrapper?.querySelector<HTMLElement>('.markdown-help') ?? undefined;
    this.notice = wrapper?.querySelector<HTMLElement>('.editor-notice-label') ?? undefined;
    this.textarea = textarea;
    if (!textarea.id) textarea.id = `yona-markdown-input-${++nextEditorId}`;
    textarea.hidden = false;
    // Turbo clones retain completion attributes, but not the expander's state.
    for (const name of ['aria-expanded', 'aria-haspopup', 'aria-controls', 'aria-activedescendant', 'aria-autocomplete']) {
      textarea.removeAttribute(name);
    }
    if (textarea.getAttribute('role') === 'combobox') textarea.removeAttribute('role');
    this.expander = new TextExpanderElement();
    this.expander.setAttribute('keys', ': @ #');
    this.expander.setAttribute('multiword', ':');
    this.expander.append(textarea);
    this.replaceChildren();
  }

  private bindEvents(textarea: HTMLTextAreaElement, expander: TextExpanderElement, form: HTMLFormElement | null | undefined) {
    this.lifetime = new AbortController();
    const options = {signal: this.lifetime.signal};
    // Capture runs before the dependency's input handler starts a replacement request.
    textarea.addEventListener('input', () => this.query?.abort(), {...options, capture: true});
    textarea.addEventListener('blur', () => this.query?.abort(), options);
    this.addEventListener('keydown', event => this.indent(event), options);
    form?.addEventListener('reset', () => this.edit(), options);
    expander.addEventListener('text-expander-change', event => this.complete(event), options);
    expander.addEventListener('text-expander-value', event => {
      const detail = (event as CustomEvent<{item: HTMLElement; value: string | null}>).detail;
      detail.value = detail.item.dataset.value ?? null;
    }, options);
    expander.addEventListener('text-expander-committed', () => {
      textarea.dispatchEvent(new Event('input', {bubbles: true}));
    }, options);
  }

  disconnectedCallback() {
    this.expander?.dismiss();
    this.query?.abort();
    this.lifetime?.abort();
    super.disconnectedCallback();
  }

  get value() { return (this.textarea ?? this.querySelector('textarea'))?.value ?? ''; }

  set value(value: string) {
    const textarea = this.textarea ?? this.querySelector('textarea');
    if (!textarea) return;
    this.edit();
    textarea.value = value;
    textarea.dispatchEvent(new Event('input', {bubbles: true}));
  }

  private edit() {
    this.query?.abort();
    this.expander?.dismiss();
    this.preview = undefined;
    if (this.expander) this.expander.hidden = false;
    this.requestUpdate();
  }

  private showPreview() {
    if (!this.textarea || this.preview) return;
    this.query?.abort();
    this.expander?.dismiss();
    const renderer = new YonaMarkdownRenderer();
    renderer.sourceElement = this.textarea;
    const mode = this.getAttribute('editor-mode') ?? this.textarea.dataset.editorMode;
    renderer.setAttribute('mode', mode === 'wiki-content' || mode === 'readme' ? 'document' : 'comment');
    for (const name of ['owner', 'project', 'ref', 'path']) {
      const value = this.getAttribute(name);
      if (value !== null) renderer.setAttribute(name, value);
    }
    this.preview = renderer;
    if (this.expander) this.expander.hidden = true;
    this.requestUpdate();
  }

  private addChecklist() {
    const textarea = this.textarea;
    if (!textarea) return;
    this.edit();
    const position = textarea.selectionStart || textarea.value.length;
    const template = '\n- [ ] Todo A\n- [ ] Todo B\n- [ ] Todo C';
    textarea.setRangeText(template, position, position, 'end');
    textarea.dispatchEvent(new Event('input', {bubbles: true}));
    void this.updateComplete.then(() => textarea.focus());
  }

  private indent(event: KeyboardEvent) {
    const textarea = this.textarea;
    if (!textarea || event.target !== textarea || event.key !== 'Tab' || event.defaultPrevented ||
        event.ctrlKey || event.altKey || event.metaKey || this.expander?.querySelector('[role="listbox"]')) return;
    event.preventDefault();
    const {selectionStart: start, selectionEnd: end} = textarea;
    if (!event.shiftKey && start === end) {
      textarea.setRangeText('\t', start, end, 'end');
    } else {
      this.indentLines(textarea, event.shiftKey);
    }
    textarea.dispatchEvent(new Event('input', {bubbles: true}));
  }

  private indentLines(textarea: HTMLTextAreaElement, outdent: boolean) {
    const {selectionStart: start, selectionEnd: end, value} = textarea;
    const lineStart = start === 0 ? 0 : value.lastIndexOf('\n', start - 1) + 1;
    // A selection ending after a newline excludes the following line.
    const selectedEnd = end > start && value[end - 1] === '\n' ? end - 1 : end;
    const newline = value.indexOf('\n', selectedEnd);
    const blockEnd = newline < 0 ? value.length : newline;
    const lines = value.slice(lineStart, blockEnd).split('\n');
    let firstDelta = 0;
    let delta = 0;
    const replacement = lines.map((line, index) => {
      const removed = outdent ? (line.match(/^(?:\t| {1,4})/)?.[0].length ?? 0) : 0;
      const change = outdent ? -removed : 1;
      if (index === 0) firstDelta = change;
      delta += change;
      return outdent ? line.slice(removed) : `\t${line}`;
    }).join('\n');
    textarea.setRangeText(replacement, lineStart, blockEnd, 'preserve');
    textarea.setSelectionRange(Math.max(lineStart, start + firstDelta), Math.max(lineStart, end + delta));
  }

  private complete(event: TextExpanderChangeEvent) {
    if (!event.detail) return;
    const {key, text, provide} = event.detail;
    this.query?.abort();
    const request = new AbortController();
    this.query = request;
    provide(this.suggestions(key, text, request.signal));
  }

  private async suggestions(key: string, text: string, signal: AbortSignal) {
    const fragment = document.createElement('ul');
    fragment.className = 'markdown-suggestions';
    fragment.setAttribute('role', 'listbox');
    fragment.setAttribute('aria-label', key === ':' ? 'Emoji' : key === '@' ? 'People' : 'Issues');
    let suggestions: Suggestion[];
    if (key === ':') {
      const search = text.toLowerCase();
      suggestions = emoji.filter(item => item.name.toLowerCase().includes(search))
        .sort((a, b) => a.name.toLowerCase().indexOf(search) - b.name.toLowerCase().indexOf(search))
        .slice(0, 10).map(item => ({value: item.content, label: `${item.content} ${item.name}`}));
    } else {
      suggestions = await this.mentionSuggestions(key, text, signal);
    }
    fragment.append(...suggestions.map(suggestionOption));
    return {matched: !signal.aborted && suggestions.length > 0, fragment};
  }

  private async mentionSuggestions(key: string, text: string, signal: AbortSignal): Promise<Suggestion[]> {
    if (key !== '@' && key !== '#') return [];
    const endpoint = this.closest('[data-mention-url]')?.getAttribute('data-mention-url');
    if (!endpoint) return [];
    try {
      const url = new URL(endpoint, document.baseURI);
      if (url.origin !== location.origin || !['http:', 'https:'].includes(url.protocol)) return [];
      url.searchParams.set('query', text);
      url.searchParams.set('mentionType', key === '@' ? 'user' : 'issue');
      const response = await fetch(url, {signal, headers: {Accept: 'application/json'}});
      if (!response.ok) return [];
      const data: unknown = await response.json();
      if (signal.aborted || !data || typeof data !== 'object' || !('result' in data) || !Array.isArray(data.result)) return [];
      const suggestions: Suggestion[] = [];
      const search = text.toLowerCase();
      const rows: unknown[] = data.result;
      for (const row of rows) {
        const suggestion = mentionSuggestion(row, key, search);
        if (suggestion) suggestions.push(suggestion);
        if (suggestions.length === 10) break;
      }
      return suggestions;
    } catch { return []; }
  }

  protected render() {
    if (!this.textarea) return nothing;
    return html`
      <style>
        yona-markdown-editor { display: block; }
        yona-markdown-editor [hidden] { display: none !important; }
        yona-markdown-editor .markdown-editor-controls { color: #333; }
        yona-markdown-editor .markdown-editor-controls.nav-tabs.small > li { margin-bottom: -1px; }
        yona-markdown-editor .markdown-editor-controls.nav-tabs.small > li > a { padding: 4px 15px; }
        yona-markdown-editor .markdown-editor-controls a:focus-visible { outline: 2px solid #2679b5; }
        yona-markdown-editor text-expander { display: block; position: relative; }
        yona-markdown-editor textarea { display: block; box-sizing: border-box; width: 100%; min-height: 12em; resize: vertical; font-family: monospace; }
        yona-markdown-editor .markdown-preview > yona-markdown-renderer { padding: 0 !important; }
        yona-markdown-editor .markdown-suggestions { position: absolute; z-index: 100; max-height: 240px; max-width: 100%; overflow: auto; padding: 4px; margin: 0; list-style: none; color: #222; background: white; border: 1px solid #aaa; box-shadow: 0 2px 6px #0003; }
        yona-markdown-editor .markdown-suggestions [role=option] { cursor: pointer; padding: 4px 8px; overflow-wrap: anywhere; }
        yona-markdown-editor .markdown-suggestions [aria-selected=true] { color: white; background: #2679b5; }
        yona-markdown-editor .markdown-suggestions img { vertical-align: middle; margin-right: 6px; }
      </style>
      <ul class="nav nav-tabs nm small markdown-editor-controls" role="group" aria-label="Markdown view">
        <li class=${this.preview ? '' : 'active'}>
          <a href="#${this.textarea.id}-edit" role="button" aria-controls="${this.textarea.id}-edit"
              aria-pressed=${String(!this.preview)}
              @click=${(event: MouseEvent) => { event.preventDefault(); this.edit(); }}>
            ${this.dataset.editLabel ?? 'Edit'}
          </a>
        </li>
        <li class=${this.preview ? 'active' : ''}>
          <a href="#${this.textarea.id}-preview" role="button" aria-controls="${this.textarea.id}-preview"
              aria-pressed=${String(!!this.preview)}
              @click=${(event: MouseEvent) => { event.preventDefault(); this.showPreview(); }}>
            ${this.dataset.previewLabel ?? 'Preview'}
          </a>
        </li>
        <li>
          <div class="task-list-button">
            <button type="button" class="add-task-list-button ybtn ybtn-small ybtn-danger-no-outline"
                @click=${this.addChecklist}><i class="yobicon-list task-list-icon" aria-hidden="true"></i> ${this.dataset.checklistLabel ?? 'Add checklist'}</button>
          </div>
        </li>
        ${this.notice ? html`<li>${this.notice}</li>` : nothing}
      </ul>
      <div class="tab-content" style="position: relative; overflow: visible;">
        ${this.helpMarkup ?? nothing}
        <div id="${this.textarea.id}-edit" class="tab-pane ${this.preview ? '' : 'active'}" ?hidden=${!!this.preview}>
          <div class="textarea-box">${this.expander}</div>
        </div>
        <div id="${this.textarea.id}-preview" class="tab-pane markdown-preview ${this.preview ? 'active' : ''}" ?hidden=${!this.preview}>
          ${this.preview ?? nothing}
        </div>
      </div>
    `;
  }
}

function mentionSuggestion(row: unknown, key: '@' | '#', search: string): Suggestion | undefined {
  if (!row || typeof row !== 'object') return;
  const item = row as Record<string, unknown>;
  if (key === '@') {
    if (typeof item.loginid !== 'string') return;
    const searchText = String(item.searchText ?? `${item.loginid} ${item.name ?? ''}`);
    if (!searchText.toLowerCase().includes(search)) return;
    return {
      value: `@${item.loginid}`, label: `${item.name ?? ''} @${item.loginid}`,
      image: typeof item.image === 'string' ? item.image : undefined,
    };
  }
  if (typeof item.issueNo !== 'number' && typeof item.issueNo !== 'string') return;
  const label = `${item.issueNo} ${item.title ?? ''}`;
  if (!label.toLowerCase().includes(search)) return;
  return {value: `#${item.issueNo}`, label: `#${label}`};
}

/** Labels stay text; avatars accept only HTTP(S), including relative image URLs. */
function suggestionOption({value, label, image}: Suggestion): HTMLLIElement {
  const item = document.createElement('li');
  item.setAttribute('role', 'option');
  item.dataset.value = value;
  if (image) {
    try {
      const url = new URL(image, document.baseURI);
      if (url.protocol === 'https:' || url.protocol === 'http:') {
        const avatar = document.createElement('img');
        avatar.src = url.href;
        avatar.alt = '';
        avatar.width = avatar.height = 20;
        item.append(avatar);
      }
    } catch { /* Invalid avatar URLs do not hide otherwise valid results. */ }
  }
  item.append(document.createTextNode(label));
  return item;
}

customElements.define('yona-markdown-editor', YonaMarkdownEditor);
