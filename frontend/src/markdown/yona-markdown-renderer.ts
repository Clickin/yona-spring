import {LitElement, html} from 'lit';
import {micromark, type Options} from 'micromark';
import {gfm, gfmHtml} from 'micromark-extension-gfm';
import DOMPurify from 'dompurify';
import {applyStructure} from './plugins/structure';
import {enhance} from './runtime/enhance';
export {highlightCode} from './runtime/highlight-registry';

/** Immutable mount snapshot. Replace the element to display a different source. */
export class YonaMarkdownRenderer extends LitElement {
  sourceElement?: HTMLTextAreaElement | HTMLElement;
  private output?: HTMLDivElement;
  private snapshot?: HTMLTemplateElement;
  private lifetime?: AbortController;

  protected createRenderRoot() { return this; }

  connectedCallback() {
    // A Turbo clone carries attributes but must mount its own output before becoming visible.
    this.removeAttribute('data-markdown-ready');
    if (!this.output) {
      let hardBreak = false;
      let paragraph = false;
      const source = this.sourceElement;
      const markdown = source instanceof HTMLTextAreaElement || source instanceof HTMLInputElement
        ? source.value : source?.textContent ??
          this.querySelector<HTMLTemplateElement>(':scope > template[data-markdown-snapshot]')?.content.textContent ??
          this.textContent ?? '';
      // Turbo caches cloned DOM, not element fields. Keep the immutable snapshot inert and cloneable.
      this.snapshot = document.createElement('template');
      this.snapshot.dataset.markdownSnapshot = '';
      this.snapshot.content.append(document.createTextNode(markdown));
      const output = document.createElement('div');
      output.className = 'markdown-output';
      output.append(DOMPurify.sanitize(micromark(markdown, {
        htmlExtensions: [gfmHtml(), ...(this.getAttribute('mode') === 'document' ? [] : [{
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
        } satisfies NonNullable<Options['htmlExtensions']>[number]])],
        extensions: [gfm()],
        allowDangerousHtml: true,
      }), {
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
      this.output = output;
      this.replaceChildren();
      this.classList.add('markdown-wrap');
      this.style.display = 'block';
    }
    super.connectedCallback();
    this.lifetime = new AbortController();
    const lifetime = this.lifetime;
    void this.updateComplete.then(() => {
      if (lifetime.signal.aborted || !this.isConnected) return;
      const context = {
        mode: this.getAttribute('mode') ?? 'comment',
        owner: this.getAttribute('owner') ?? '',
        project: this.getAttribute('project') ?? '',
        ref: this.getAttribute('ref') ?? '',
        path: this.getAttribute('path') ?? '',
      };
      applyStructure(this.output!, context, lifetime.signal);
      enhance(this.output!, lifetime.signal);
      this.setAttribute('data-markdown-ready', '');
      this.dispatchEvent(new CustomEvent('markdown-rendered', {bubbles: true}));
    });
  }

  disconnectedCallback() {
    this.lifetime?.abort();
    super.disconnectedCallback();
  }

  protected render() { return html`${this.snapshot}${this.output}`; }
}

customElements.define('yona-markdown-renderer', YonaMarkdownRenderer);
