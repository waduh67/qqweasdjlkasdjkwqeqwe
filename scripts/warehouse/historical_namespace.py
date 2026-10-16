"""Adapt current test overlays to the package of an immutable historical application."""
from pathlib import Path
import re


def application_package(root: Path) -> str:
    files = list((root / 'server/src/main/kotlin').rglob('FtthApplication.kt'))
    if len(files) != 1:
        raise ValueError('Expected one historical application entry point')
    match = re.search(r'^package\s+([A-Za-z_][\w.]*)\s*$', files[0].read_text(), re.M)
    if not match:
        raise ValueError('Application package declaration not found')
    return match.group(1)


class HistoricalNamespace:
    def __init__(self, current: Path, historical: Path):
        self.current = application_package(current)
        self.historical = application_package(historical)

    def class_name(self, name: str) -> str:
        return name.replace(self.current + '.', self.historical + '.')

    def path(self, name: str) -> str:
        return name.replace(self.current.replace('.', '/') + '/', self.historical.replace('.', '/') + '/')

    def content(self, data: bytes) -> bytes:
        # Only called for Kotlin harness files, never database migrations or product source.
        return self.path(self.class_name(data.decode('utf-8'))).encode('utf-8')
