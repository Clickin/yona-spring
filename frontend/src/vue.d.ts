import 'vue';
import type {DefineComponent} from 'vue';
import type {YonaMarkdownRenderer} from './markdown/yona-markdown-renderer';

declare module 'vue' {
  interface ComponentCustomOptions {
    /** Inline styles emitted by unplugin-vue for .ce.vue components. */
    styles?: string[];
  }

  interface GlobalComponents {
    // Template typing only; esbuild preserves this native custom element.
    'yona-markdown-renderer': DefineComponent<{
      sourceElement?: YonaMarkdownRenderer['sourceElement'];
      mode?: string;
      owner?: string | null;
      project?: string | null;
      ref?: string;
      path?: string | null;
    }>;
  }
}
