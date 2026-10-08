import {build} from 'esbuild';
import vue from 'unplugin-vue/esbuild';
import {mkdir, readdir, rm, writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const outdir = fileURLToPath(new URL('../../build/generated/frontend/markdown/', import.meta.url));
await rm(outdir, {recursive: true, force: true});
await mkdir(outdir, {recursive: true});
const entries = (await readdir(`${root}src/markdown`)).filter(name => name.startsWith('yona-') && name.endsWith('.ts'));
const result = await build({
  absWorkingDir: root,
  entryPoints: entries.map(name => `src/markdown/${name}`),
  outdir,
  bundle: true,
  splitting: true,
  format: 'esm',
  platform: 'browser',
  target: ['es2022'],
  plugins: [vue({
    isProduction: true,
    sourceMap: false,
    customElement: /\.ce\.vue$/,
    template: {
      compilerOptions: {
        isCustomElement: tag => tag.startsWith('yona-'),
      },
    },
  })],
  define: {
    'process.env.NODE_ENV': '"production"',
    __VUE_OPTIONS_API__: 'false',
    __VUE_PROD_DEVTOOLS__: 'false',
    __VUE_PROD_HYDRATION_MISMATCH_DETAILS__: 'false',
  },
  minify: true,
  chunkNames: 'chunks/[name]-[hash]',
  metafile: true,
  legalComments: 'eof',
});
await writeFile(new URL('../../build/generated/frontend/markdown-meta.json', import.meta.url), JSON.stringify(result.metafile));
