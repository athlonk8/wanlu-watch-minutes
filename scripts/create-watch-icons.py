"""Export the generated watch artwork at Android launcher resource densities."""
from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[1]
source = Image.open(root / 'watchrec_app/artwork/wanlu-icon.png').convert('RGBA')
resources = root / 'watchrec_app/app/src/main/res'
for density, scale in [('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)]:
    directory = resources / f'mipmap-{density}'
    directory.mkdir(parents=True, exist_ok=True)
    for name, base_size in [('ic_launcher', 48), ('ic_launcher_foreground', 108)]:
        size = int(base_size * scale)
        source.resize((size, size), Image.Resampling.LANCZOS).save(directory / f'{name}.png', optimize=True)
adaptive = resources / 'mipmap-anydpi-v26'
adaptive.mkdir(parents=True, exist_ok=True)
(adaptive / 'ic_launcher.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
''', encoding='utf-8')
