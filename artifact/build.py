# Zlozi Ucto-OZ.html (artifact na claude.ai) z sablony a JS jadra.
# Spustenie: python3 artifact/build.py  -> artifact/Ucto-OZ.html (publikuje sa spolu s lib/)
from pathlib import Path
d = Path(__file__).parent
t = (d / 'page.template.html').read_text(encoding='utf-8')
t = t.replace('/*__UCTO_CORE__*/', (d / 'ucto-core.js').read_text(encoding='utf-8'))
t = t.replace('/*__UCTO_PDF__*/', (d / 'ucto-pdf.js').read_text(encoding='utf-8'))
(d / 'Ucto-OZ.html').write_text(t, encoding='utf-8')
print('artifact/Ucto-OZ.html')
