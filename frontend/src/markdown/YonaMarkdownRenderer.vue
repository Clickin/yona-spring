<script lang="ts">
const rendererApi = Symbol('markdown-renderer-api');
type RendererApi = {ready: Promise<unknown>};
</script>

<script setup lang="ts">
import {nextTick, onBeforeUnmount, onMounted, ref, useHost, useShadowRoot} from 'vue';
import {micromark, type Options} from 'micromark';
import {gfm, gfmHtml} from 'micromark-extension-gfm';
import DOMPurify from 'dompurify';
import {loadShadowStyles} from './shadow-styles';
import {applyStructure} from './plugins/structure';
import {enhance} from './runtime/enhance';

defineOptions({inheritAttrs: false});
const props = defineProps<{sourceElement?: HTMLElement}>();
const host = useHost()! as HTMLElement & {[rendererApi]?: RendererApi};
const shadow = useShadowRoot()!;
const output = ref<HTMLDivElement>();
const lifetime = new AbortController();
// Vue retains exposed CE getters across unmounts, so remounts update this same public object.
const exposed = host[rendererApi] ??= {ready: Promise.resolve()};

host.removeAttribute('data-markdown-ready');
const source = props.sourceElement;
let snapshot = host.querySelector<HTMLTemplateElement>(':scope > template[data-markdown-snapshot]');
// A remount and a Turbo clone keep the original immutable source, even if sourceElement changed.
const markdown = snapshot?.content.textContent ??
  (source instanceof HTMLTextAreaElement || source instanceof HTMLInputElement
    ? source.value : source instanceof HTMLElement ? source.textContent : host.textContent) ?? '';
if (!snapshot) {
  snapshot = document.createElement('template');
  snapshot.dataset.markdownSnapshot = '';
  snapshot.content.append(document.createTextNode(markdown));
}
host.replaceChildren(snapshot);
host.classList.add('markdown-wrap');
host.style.display = 'block';
const markup = markdownOutput(markdown, host.getAttribute('mode') === 'document');

onMounted(() => {
  exposed.ready = loadShadowStyles(shadow).then(() => nextTick()).then(() => enhanceOutput(lifetime.signal));
});
onBeforeUnmount(() => {
  lifetime.abort();
  host.removeAttribute('data-markdown-ready');
});
defineExpose(exposed);

function enhanceOutput(signal: AbortSignal) {
  if (signal.aborted || !host.isConnected || !output.value) return;
  const root = output.value;
  const context = {
    mode: host.getAttribute('mode') ?? 'comment',
    owner: host.getAttribute('owner') ?? '',
    project: host.getAttribute('project') ?? '',
    ref: host.getAttribute('ref') ?? '',
    path: host.getAttribute('path') ?? '',
  };
  applyStructure(root, context, signal);
  (window as Window & {yona?: {initTasklist?: (root: HTMLElement) => void}}).yona?.initTasklist?.(host);
  const scrollToFragment = () => {
    let id: string;
    try { id = decodeURIComponent(location.hash.slice(1)); } catch { return; }
    shadow.getElementById(id)?.scrollIntoView();
  };
  root.addEventListener('click', event => {
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
  enhance(root, signal);
  host.setAttribute('data-markdown-ready', '');
  host.dispatchEvent(new CustomEvent('markdown-rendered', {bubbles: true, composed: true}));
}

/** Parse and sanitize before any output becomes visible or participates in a form. */
function markdownOutput(markdown: string, documentMode: boolean): string {
  const output = document.createElement('div');
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
  return output.innerHTML;
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
</script>

<template>
  <div class="markdown-wrap">
    <div ref="output" class="markdown-output" v-html="markup"></div>
  </div>
</template>

<style>
:host { display: block; }
.markdown-wrap { padding: 0 !important; font-size: inherit; }
.markdown-output img { max-width: 100%; }
</style>
