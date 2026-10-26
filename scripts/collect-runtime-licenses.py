"""Include notices for the Python runtime and bundled backend dependencies."""
from importlib import metadata
from pathlib import Path
import shutil
import sys

target = Path(__file__).resolve().parents[1] / '.local/backend/wanlu-backend/THIRD_PARTY_NOTICES'
target.mkdir(parents=True, exist_ok=True)
shutil.copyfile(Path(sys.base_prefix) / 'LICENSE.txt', target / 'Python-LICENSE.txt')
for name in ('requests', 'urllib3', 'certifi', 'charset-normalizer', 'idna', 'pyinstaller'):
    distribution = metadata.distribution(name)
    notices = [p for p in distribution.files or []
               if 'licenses' in p.parts or p.name.upper().startswith(('LICENSE', 'COPYING', 'NOTICE'))]
    if not notices:
        raise RuntimeError(f'Missing license for {name}')
    directory = target / name
    directory.mkdir(exist_ok=True)
    for item in notices:
        source = Path(distribution.locate_file(item))
        if source.is_file():
            shutil.copyfile(source, directory / source.name)
print('Bundled runtime license notices collected.')
