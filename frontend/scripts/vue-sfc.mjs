import vue from 'unplugin-vue/esbuild';

export function vueSfc() {
  const plugin = vue({
    isProduction: true,
    sourceMap: false,
    customElement: /\.ce\.vue$/,
    template: {compilerOptions: {isCustomElement: tag => tag.startsWith('yona-')}},
  });
  return {
    name: plugin.name,
    setup(build) {
      return plugin.setup({
        ...build,
        onLoad(filter, load) {
          build.onLoad(filter, async args => {
            const result = await load(args);
            const id = args.path + (args.suffix ?? '');
            // esbuild's CSS loader exports {}, but CE styles must be CSS strings for the shadow root.
            if (result && /[?&]type=style(?:&|$)/.test(id) && /[?&]inline(?:&|$)/.test(id)) {
              return {...result, loader: 'text'};
            }
            return result;
          });
        },
      });
    },
  };
}
