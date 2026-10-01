// Render the three static Google Sans Flex faces (made by build-map-fonts.sh) into MapLibre
// glyph ranges. Output folders carry the "Roboto ..." names composite_glyphs.py pairs with the
// Noto stacks. Usage: node flex_glyphs.js <outdir>   (run where flex-*.ttf and node_modules are)
const fontnik = require(require('path').resolve('node_modules/fontnik'));
const fs = require('fs'), path = require('path');
const out = process.argv[2];
const sets = [['flex-regular.ttf', 'Roboto Regular'], ['flex-bold.ttf', 'Roboto Bold'], ['flex-italic.ttf', 'Roboto Italic']];
(async () => {
  for (const [file, name] of sets) {
    const font = fs.readFileSync(file);
    const dir = path.join(out, name);
    fs.mkdirSync(dir, { recursive: true });
    for (let s = 0; s < 65536; s += 256) {
      const buf = await new Promise((res, rej) => fontnik.range({ font, start: s, end: s + 255 }, (e, d) => (e ? rej(e) : res(d))));
      fs.writeFileSync(path.join(dir, `${s}-${s + 255}.pbf`), buf);
    }
  }
})().catch((e) => { console.error(e); process.exit(1); });
