import {createApp, h, nextTick, shallowRef, type App} from 'vue';
import {mountShadow} from './shadow-styles';
import TextExpanderElement, {type TextExpanderChangeEvent} from '@github/text-expander-element';
import './yona-markdown-renderer';
import {emoji} from './emoji';

let nextEditorId = 0;

type Suggestion = {value: string; label: string; image?: string};

/** The server's actual textarea owns the value and native form/reset semantics. */
export class YonaMarkdownEditor extends HTMLElement {
  private textarea?: HTMLTextAreaElement;
  private expander?: TextExpanderElement;
  private preview = shallowRef(false);
  private previewHeight = 0;
  private helpMarkup?: HTMLElement;
  private notice?: HTMLElement;
  private clearDraft?: HTMLElement;
  private lifetime?: AbortController;
  private query?: AbortController;
  private app?: App;
  private stylesReady: Promise<unknown> = Promise.resolve();
  get ready() { return this.stylesReady.then(() => nextTick()); }

  connectedCallback() {
    const form = (this.textarea ?? this.querySelector('textarea'))?.form;
    if (!this.textarea) this.initializeTextarea();
    if (!this.shadowRoot) {
      const shadow = mountShadow(this, `
        :host { display: block; }
        [hidden] { display: none !important; }
        .markdown-editor-controls { color: #333; }
        .markdown-editor-controls.nav-tabs.small > li { margin-bottom: -1px; }
        .markdown-editor-controls.nav-tabs.small > li > a { padding: 4px 15px; }
        .markdown-editor-controls a:focus-visible { outline: 2px solid #2679b5; }
        .tab-pane.active { display: flow-root; }
        .markdown-preview { box-sizing: border-box; overflow: auto; }
        .markdown-preview > yona-markdown-renderer { padding: 0 !important; }
      `);
      this.stylesReady = shadow.ready;
      this.app = createApp({render: () => this.render()});
      this.app.mount(shadow.mount);
    }
    if (this.textarea && this.expander) this.bindEvents(this.textarea, this.expander, form);
  }

  private initializeTextarea() {
    const textarea = this.querySelector('textarea');
    if (!textarea) return;
    const wrapper = this.closest('[data-toggle="markdown-editor"]');
    this.helpMarkup = this.querySelector<HTMLElement>('.markdown-help') ?? wrapper?.querySelector<HTMLElement>('.markdown-help') ?? undefined;
    this.notice = this.querySelector<HTMLElement>('.editor-notice-label') ?? wrapper?.querySelector<HTMLElement>('.editor-notice-label') ?? undefined;
    this.clearDraft = this.querySelector<HTMLElement>('.editor-clear-temporary') ?? wrapper?.querySelector<HTMLElement>('.editor-clear-temporary') ?? undefined;
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
    this.expander.slot = 'input';
    this.expander.append(textarea);
    if (this.helpMarkup) this.helpMarkup.slot = 'help';
    if (this.notice) this.notice.slot = 'notice';
    if (this.clearDraft) this.clearDraft.slot = 'clear-draft';
    // Keep native form controls, Stimulus help and the autosave notice in the document tree.
    this.replaceChildren(this.expander, ...[this.helpMarkup, this.clearDraft, this.notice].filter((node): node is HTMLElement => !!node));
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
    // Reparenting an editor keeps its Vue state and the original textarea.
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
    this.preview.value = false;
    if (this.expander) this.expander.hidden = false;
  }

  private showPreview() {
    if (!this.textarea || this.preview.value) return;
    this.query?.abort();
    this.expander?.dismiss();
    this.previewHeight = this.shadowRoot!.querySelector('.tab-pane')!.getBoundingClientRect().height;
    this.preview.value = true;
    if (this.expander) this.expander.hidden = true;
  }

  private addChecklist() {
    const textarea = this.textarea;
    if (!textarea) return;
    this.edit();
    const position = textarea.selectionStart || textarea.value.length;
    const template = '\n- [ ] Todo A\n- [ ] Todo B\n- [ ] Todo C';
    textarea.setRangeText(template, position, position, 'end');
    textarea.dispatchEvent(new Event('input', {bubbles: true}));
    void nextTick(() => textarea.focus());
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

  private render() {
    if (!this.textarea) return null;
    const preview = this.preview.value;
    const id = this.textarea.id;
    const mode = this.getAttribute('editor-mode') ?? this.textarea.dataset.editorMode;
    return [
      h('ul', {class: 'nav nav-tabs nm small markdown-editor-controls', role: 'group', 'aria-label': 'Markdown view'}, [
        ...[
          {name: 'edit', active: !preview, action: () => this.edit(), label: this.dataset.editLabel ?? 'Edit'},
          {name: 'preview', active: preview, action: () => this.showPreview(), label: this.dataset.previewLabel ?? 'Preview'},
        ].map(({name, active, action, label}) => h('li', {class: active ? 'active' : ''}, h('a', {
          href: `#${id}-${name}`, role: 'button', 'aria-controls': `${id}-${name}`,
          'aria-pressed': String(active), onClick: (event: MouseEvent) => { event.preventDefault(); action(); },
        }, label))),
        h('li', h('div', {class: 'task-list-button'}, h('button', {
          type: 'button', class: 'add-task-list-button ybtn ybtn-small ybtn-danger-no-outline',
          onClick: () => this.addChecklist(),
        }, [h('i', {class: 'yobicon-list task-list-icon', 'aria-hidden': 'true'}), ` ${this.dataset.checklistLabel ?? 'Add checklist'}`]))),
        this.clearDraft ? h('li', h('slot', {name: 'clear-draft'})) : null,
        this.notice ? h('li', h('slot', {name: 'notice'})) : null,
      ]),
      h('div', {class: 'tab-content', style: {position: 'relative', overflow: 'visible'}}, [
        h('slot', {name: 'help'}),
        h('div', {id: `${id}-edit`, class: ['tab-pane', {active: !preview}], hidden: preview},
          h('div', {class: 'textarea-box'}, h('slot', {name: 'input'}))),
        h('div', {
          id: `${id}-preview`, class: ['tab-pane', 'markdown-preview', {active: preview}], hidden: !preview,
          style: preview ? {height: `${this.previewHeight}px`} : undefined,
        }, preview ? h('yona-markdown-renderer', {
          '.sourceElement': this.textarea,
          mode: mode === 'wiki-content' || mode === 'readme' ? 'document' : 'comment',
          ...Object.fromEntries(['owner', 'project', 'ref', 'path'].map(name => [name, this.getAttribute(name)])),
        }) : undefined),
      ]),
    ];
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
