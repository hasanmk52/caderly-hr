# Cadrely logo kit

Brand colour: **Petrol** `#0F5568` (light) / `#6FBDD1` (dark)

| File | Use |
|---|---|
| `cadrely-mark.svg` | Primary mark, Petrol, with occupant. Anything 20 px and up. |
| `cadrely-mark-mono.svg` | Same geometry, `currentColor`. Inline it in HTML and set `color:`. |
| `cadrely-mark-small.svg` | Occupant removed. Use below 20 px — the square fuses with the arms. |
| `cadrely-favicon.svg` | Favicon. Switches to `#6FBDD1` under `prefers-color-scheme: dark`. |
| `cadrely-appicon.svg` | 512 px tile, white mark on Petrol. Export PNG at 512/192/180. |
| `cadrely-lockup.svg` | Horizontal lockup. Outline the text before distributing. |

## Geometry — do not redraw by eye

- Grid 64 × 64. Centreline square 13 → 51, corner radius 8.
- Stroke 10. Butt caps, round joins.
- Arms: top ends at x=40, bottom at x=48. **Never equalise them.**
- Occupant: 12 × 12, rx 2, at x=41 y=26 (vertically centred on y=32).
- Outer corner radius 13, inner corner radius 3. Soft outside, sharp inside — this is the mark's whole point of view. Do not round the inner corners.
- Clear space: 10 units (one stroke width) on all four sides.

## Never

Close the frame · mirror it to open leftward · centre the occupant inside the
frame · round the inner corners · put it in a circle · add a second occupant ·
use it as a bullet in body copy · animate it as a perpetual spinner.

## Web lockup (preferred over the SVG lockup)

```html
<a class="brand" href="/">
  <svg class="brand-mark" viewBox="0 0 64 64" aria-hidden="true">
    <path d="M40 13H21A8 8 0 0 0 13 21V43A8 8 0 0 0 21 51H48"
          fill="none" stroke="currentColor" stroke-width="10"
          stroke-linecap="butt" stroke-linejoin="round"/>
    <rect x="41" y="26" width="12" height="12" rx="2" fill="currentColor"/>
  </svg>
  <span>Cadrely</span>
</a>
```

```css
.brand { display:inline-flex; align-items:center; gap:.34em;
         text-decoration:none; color:#15171C; }
.brand-mark { width:1.15em; height:1.15em; color:#0F5568; }
.brand span { font: 600 1.05rem/1 "IBM Plex Sans", sans-serif;
              letter-spacing:-.036em; }
```
