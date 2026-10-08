import {defineCustomElement} from 'vue';
import Renderer from './YonaMarkdownRenderer.vue';
export {highlightCode} from './runtime/highlight-registry';

export const YonaMarkdownRenderer = defineCustomElement(Renderer);
export type YonaMarkdownRenderer = InstanceType<typeof YonaMarkdownRenderer> & {readonly ready: Promise<unknown>};

customElements.define('yona-markdown-renderer', YonaMarkdownRenderer);
