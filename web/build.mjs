import { readFile, writeFile } from 'node:fs/promises';
const read = name => readFile(new URL(name, import.meta.url), 'utf8');
let html = await read('index.template.html');
for (const [marker, path] of [['STYLE', 'src/style.css'], ['CORE', 'src/core.js'], ['APP', 'src/app.js']]) html = html.replace(`/* ${marker} */`, await read(path));
await writeFile(new URL('index.html', import.meta.url), html);
console.log('Built self-contained web/index.html');
