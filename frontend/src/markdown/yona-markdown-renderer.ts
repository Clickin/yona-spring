import {createApp, h, nextTick} from 'vue';
import {mountShadow} from './shadow-styles';
import {micromark, type Options} from 'micromark';
import {gfm, gfmHtml} from 'micromark-extension-gfm';
import DOMPurify from 'dompurify';
import {applyStructure} from './plugins/structure';
import {enhance} from './runtime/enhance';
export {highlightCode} from './runtime/highlight-registry';

/** Immutable mount snapshot. Replace the element to display a different source. */
export class YonaMarkdownRenderer extends HTMLElement {
  sourceElement?: HTMLTextAreaElement | HTMLElement;
  private output?: HTMLDivElement;
  private snapshot?: HTMLTemplateElement;
  private lifetime?: AbortController;
  private mounted: Promise<unknown> = Promise.resolve();
  get ready() { return this.mounted; }

  connectedCallback() {
    // A Turbo clone carries attributes but must mount its own output before becoming visible.
    this.removeAttribute('data-markdown-ready');
    if (!this.output) this.initializeOutput();
    this.lifetime = new AbortController();
    const lifetime = this.lifetime;
    this.mounted = this.mounted.then(() => nextTick()).then(() => this.enhanceOutput(lifetime.signal));
  }

  private initializeOutput() {
    const source = this.sourceElement;
    const markdown = source instanceof HTMLTextAreaElement || source instanceof HTMLInputElement
      ? source.value : source?.textContent ??
        this.querySelector<HTMLTemplateElement>(':scope > template[data-markdown-snapshot]')?.content.textContent ??
        this.textContent ?? '';
    // Turbo caches cloned DOM, not element fields. Keep the immutable snapshot inert and cloneable.
    this.snapshot = document.createElement('template');
    this.snapshot.dataset.markdownSnapshot = '';
    this.snapshot.content.append(document.createTextNode(markdown));
    const markup = markdownOutput(markdown, this.getAttribute('mode') === 'document').innerHTML;
    this.replaceChildren(this.snapshot);
    const shadow = mountShadow(this, `
      :host { display: block; }
      .markdown-wrap { padding: 0 !important; font-size: inherit; }
      .markdown-output img { max-width: 100%; }
    `);
    createApp({render: () => h('div', {class: 'markdown-wrap'}, [
      h('div', {class: 'markdown-output', innerHTML: markup}),
    ])}).mount(shadow.mount);
    this.output = shadow.root.querySelector<HTMLDivElement>('.markdown-output')!;
    this.mounted = shadow.ready;
    this.classList.add('markdown-wrap');
    this.style.display = 'block';
  }

  private enhanceOutput(signal: AbortSignal) {
    if (signal.aborted || !this.isConnected) return;
    const context = {
      mode: this.getAttribute('mode') ?? 'comment',
      owner: this.getAttribute('owner') ?? '',
      project: this.getAttribute('project') ?? '',
      ref: this.getAttribute('ref') ?? '',
      path: this.getAttribute('path') ?? '',
    };
    applyStructure(this.output!, context, signal);
    (window as Window & {yona?: {initTasklist?: (root: HTMLElement) => void}}).yona?.initTasklist?.(this);
    const scrollToFragment = () => {
      let id: string;
      try { id = decodeURIComponent(location.hash.slice(1)); } catch { return; }
      this.shadowRoot?.getElementById(id)?.scrollIntoView();
    };
    this.output!.addEventListener('click', event => {
      const link = (event.target as Element).closest<HTMLAnchorElement>('a[href]');
      if (!link || event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      const url = new URL(link.href);
      if (url.origin === location.origin && url.pathname === location.pathname && url.search === location.search && url.hash) {
        event.preventDefault();
        location.hash = url.hash;
        scrollToFragment();
      }
    }, {signal});
    window.addEventListener('hashchange', scrollToFragment, {signal});
    if (location.hash) scrollToFragment();
    enhance(this.output!, signal);
    this.setAttribute('data-markdown-ready', '');
    this.dispatchEvent(new CustomEvent('markdown-rendered', {bubbles: true, composed: true}));
  }

  disconnectedCallback() {
    this.lifetime?.abort();
  }

}

/** Parse and sanitize before any output becomes visible or participates in a form. */
function markdownOutput(markdown: string, documentMode: boolean): HTMLDivElement {
  const output = document.createElement('div');
  output.className = 'markdown-output';
  const markup = micromark(markdown, {
    htmlExtensions: documentMode ? [gfmHtml()] : [gfmHtml(), commentLineBreaks()],
    extensions: [gfm()],
    allowDangerousHtml: true,
  });
  output.append(DOMPurify.sanitize(markup, {
    RETURN_DOM_FRAGMENT: true,
    USE_PROFILES: {html: true},
    FORBID_TAGS: ['style', 'form', 'button', 'textarea', 'select', 'option'],
    FORBID_ATTR: ['style', 'autofocus', 'name', 'id'],
    ALLOW_DATA_ATTR: false,
    ADD_TAGS: ['input'],
  }));
  // Raw HTML cannot introduce successful form controls; GFM task boxes are display-only here.
  output.querySelectorAll('input').forEach(input => {
    if (input.type !== 'checkbox') input.remove();
    else { input.disabled = true; input.removeAttribute('name'); }
  });
  return output;
}

/** Comments keep soft breaks inside paragraphs, but not tight lists or code. */
function commentLineBreaks(): NonNullable<Options['htmlExtensions']>[number] {
  let hardBreak = false;
  let paragraph = false;
  return {
    enter: {
      paragraph() {
        paragraph = true;
        if (!this.getData('tightStack').at(-1)) {
          this.lineEndingIfNeeded();
          this.tag('<p>');
        }
        this.setData('slurpAllLineEndings');
      },
    },
    exit: {
      paragraph() {
        paragraph = false;
        if (this.getData('tightStack').at(-1)) this.setData('slurpAllLineEndings', true);
        else this.tag('</p>');
      },
      hardBreakEscape() { this.tag('<br />'); hardBreak = true; },
      hardBreakTrailing() { this.tag('<br />'); hardBreak = true; },
      lineEnding(token) {
        if (this.getData('slurpAllLineEndings')) return;
        if (this.getData('slurpOneLineEnding')) {
          this.setData('slurpOneLineEnding');
          return;
        }
        if (this.getData('inCodeText')) { this.raw(' '); return; }
        if (paragraph && !hardBreak) this.tag('<br />');
        hardBreak = false;
        this.raw(this.encode(this.sliceSerialize(token)));
      },
    },
  };
}

customElements.define('yona-markdown-renderer', YonaMarkdownRenderer);
