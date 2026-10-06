import {LitElement, html, nothing} from 'lit';
import '@github/markdown-toolbar-element';
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
  private help?: HTMLElement;
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
    this.help = this.closest('[data-toggle="markdown-editor"]')?.querySelector<HTMLElement>('yona-help-markdown') ?? undefined;
    if (this.help) {
      if (!this.help.id) this.help.id = `${textarea.id}-help`;
      this.help.hidden = true;
    }
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

  private toggleHelp() {
    if (!this.help) return;
    this.help.hidden = !this.help.hidden;
    this.requestUpdate();
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
        yona-markdown-editor .markdown-editor-controls, yona-markdown-editor markdown-toolbar { display: flex; flex-wrap: wrap; gap: 4px; padding: 5px 0; }
        yona-markdown-editor markdown-toolbar > * { cursor: pointer; border: 1px solid #bbb; border-radius: 3px; padding: 3px 7px; }
        yona-markdown-editor markdown-toolbar > :focus-visible { outline: 2px solid #2679b5; }
        yona-markdown-editor text-expander { display: block; position: relative; }
        yona-markdown-editor textarea { display: block; box-sizing: border-box; width: 100%; min-height: 12em; resize: vertical; font-family: monospace; }
        yona-markdown-editor .markdown-suggestions { position: absolute; z-index: 100; max-height: 240px; max-width: 100%; overflow: auto; padding: 4px; margin: 0; list-style: none; color: #222; background: white; border: 1px solid #aaa; box-shadow: 0 2px 6px #0003; }
        yona-markdown-editor .markdown-suggestions [role=option] { cursor: pointer; padding: 4px 8px; overflow-wrap: anywhere; }
        yona-markdown-editor .markdown-suggestions [aria-selected=true] { color: white; background: #2679b5; }
        yona-markdown-editor .markdown-suggestions img { vertical-align: middle; margin-right: 6px; }
      </style>
      <div class="markdown-editor-controls" role="group" aria-label="Markdown view">
        <button type="button" aria-pressed=${String(!this.preview)} @click=${() => { this.edit(); this.textarea?.focus(); }}>Edit</button>
        <button type="button" aria-pressed=${String(!!this.preview)} @click=${this.showPreview}>Preview</button>
        ${this.help ? html`<button type="button" aria-controls=${this.help.id} aria-expanded=${String(!this.help.hidden)} @click=${this.toggleHelp}>Help</button>` : nothing}
      </div>
      <markdown-toolbar for=${this.textarea.id} aria-label="Markdown formatting" ?hidden=${!!this.preview}>
        <md-header role="button" tabindex="-1" aria-label="Heading">Heading</md-header>
        <md-bold role="button" tabindex="-1" aria-label="Bold">Bold</md-bold>
        <md-italic role="button" tabindex="-1" aria-label="Italic">Italic</md-italic>
        <md-quote role="button" tabindex="-1" aria-label="Quote">Quote</md-quote>
        <md-code role="button" tabindex="-1" aria-label="Code">Code</md-code>
        <md-link role="button" tabindex="-1" aria-label="Link">Link</md-link>
        <md-image role="button" tabindex="-1" aria-label="Image">Image</md-image>
        <md-unordered-list role="button" tabindex="-1" aria-label="List">List</md-unordered-list>
        <md-ordered-list role="button" tabindex="-1" aria-label="Ordered list">Ordered list</md-ordered-list>
        <md-task-list role="button" tabindex="-1" aria-label="Task list">Task list</md-task-list>
        <md-mention role="button" tabindex="-1" aria-label="Mention">@</md-mention>
        <md-ref role="button" tabindex="-1" aria-label="Issue">#</md-ref>
      </markdown-toolbar>
      ${this.expander}${this.preview ?? nothing}
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
