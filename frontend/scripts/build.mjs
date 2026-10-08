import {build} from 'esbuild';
import {vueSfc} from './vue-sfc.mjs';
import {mkdir, readdir, rm, writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const outdir = fileURLToPath(new URL('../../build/generated/frontend/web/', import.meta.url));
await rm(outdir, {recursive: true, force: true});
await mkdir(outdir, {recursive: true});
// Each src/<area>/yona-*.ts is a page entry, served as /javascripts/<area>/yona-*.js; chunks are shared.
const entries = [];
for (const area of await readdir(`${root}src`, {withFileTypes: true})) {
  if (!area.isDirectory()) continue;
  for (const name of await readdir(`${root}src/${area.name}`)) {
    if (name.startsWith('yona-') && name.endsWith('.ts')) entries.push(`src/${area.name}/${name}`);
  }
}
const result = await build({
  absWorkingDir: root,
  entryPoints: entries,
  outbase: 'src',
  outdir,
  bundle: true,
  splitting: true,
  format: 'esm',
  platform: 'browser',
  target: ['es2022'],
  plugins: [vueSfc()],
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
await writeFile(new URL('../../build/generated/frontend/meta.json', import.meta.url), JSON.stringify(result.metafile));
