// Construye www/ con lo minimo: HTML, JS minificado, logo y 5 fuentes.
import { build } from 'esbuild';
import { mkdirSync, copyFileSync, readFileSync, writeFileSync, rmSync } from 'node:fs';

rmSync('www', { recursive: true, force: true });
mkdirSync('www/fonts', { recursive: true });

await build({
  entryPoints: ['src/app.js'], bundle: true, minify: true, format: 'iife',
  target: ['chrome90'], outfile: 'www/app.js', legalComments: 'none'
});

const html = readFileSync('src/index.html', 'utf8')
  .replace(/\n\s+/g, '\n').replace(/<!--[\s\S]*?-->/g, '');
writeFileSync('www/index.html', html);
copyFileSync('assets/logo.png', 'www/logo.png');

const F = 'node_modules/@fontsource/';
[['space-grotesk/files/space-grotesk-latin-600-normal.woff2', 'sg-600'],
 ['space-grotesk/files/space-grotesk-latin-700-normal.woff2', 'sg-700'],
 ['inter/files/inter-latin-400-normal.woff2', 'in-400'],
 ['inter/files/inter-latin-500-normal.woff2', 'in-500'],
 ['inter/files/inter-latin-600-normal.woff2', 'in-600']
].forEach(([src, name]) => copyFileSync(F + src, 'www/fonts/' + name + '.woff2'));
console.log('www built');
