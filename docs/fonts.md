# Fonts

The app sets its type in the three faces nonfungible.cash uses. The files in
`app/src/main/res/font` were made from the site's own web fonts, the Latin
subsets that `@fontsource` packages, by fixing each at the weight the app uses.

| File | Face | Weight |
| --- | --- | --- |
| `bricolage_grotesque_800.ttf` | Bricolage Grotesque | 800, headings |
| `inter_400.ttf`, `inter_600.ttf`, `inter_700.ttf` | Inter | text |
| `jetbrains_mono_400.ttf` | JetBrains Mono | hashes, small labels |

All three are licensed under the [SIL Open Font License 1.1](https://openfontlicense.org),
which allows bundling them in an app; the licence asks that they not be sold on
their own and that derivatives keep the licence.

To make them again from `cashu-nft/cashu/nft/portfolio_web/node_modules`:

```sh
uv run --with fonttools --with brotli python - <<'PY'
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
font = TTFont("@fontsource-variable/inter/files/inter-latin-wght-normal.woff2")
fixed = instantiateVariableFont(font, {"wght": 600})
fixed.flavor = None
fixed.save("inter_600.ttf")
PY
```
