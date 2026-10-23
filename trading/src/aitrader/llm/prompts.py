"""Versioned prompt files: src/aitrader/prompts/<prompt_id>/v<N>.md with two sections,
``## System`` and ``## User``. ``{{name}}`` placeholders are filled at render time. Files are
never edited in place; a change is a new version, and the file hash is logged with every call."""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

from aitrader.schemas.canonical import sha256_hex

PROMPT_DIR = Path(__file__).resolve().parents[1] / "prompts"
_SECTION = re.compile(r"^## (System|User)\s*$", re.MULTILINE)
_PLACEHOLDER = re.compile(r"\{\{(\w+)\}\}")


@dataclass(frozen=True)
class PromptTemplate:
    prompt_id: str
    version: str
    system: str
    user: str
    sha256: str

    def render(self, **values: str) -> tuple[str, str]:
        needed = set(_PLACEHOLDER.findall(self.system + self.user))
        if missing := needed - set(values):
            raise KeyError(f"prompt {self.prompt_id} needs {sorted(missing)}")
        if extra := set(values) - needed:
            raise KeyError(f"prompt {self.prompt_id} has no placeholders {sorted(extra)}")

        def fill(text: str) -> str:
            return _PLACEHOLDER.sub(lambda m: values[m.group(1)], text)

        return fill(self.system), fill(self.user)


def load_prompt(prompt_id: str, version: int, directory: Path = PROMPT_DIR) -> PromptTemplate:
    text = (directory / prompt_id / f"v{version}.md").read_text(encoding="utf-8")
    parts = _SECTION.split(text)
    sections = {parts[i]: parts[i + 1].strip() for i in range(1, len(parts) - 1, 2)}
    if set(sections) != {"System", "User"}:
        raise ValueError(f"prompt {prompt_id} v{version} needs exactly '## System' and '## User'")
    return PromptTemplate(prompt_id, f"v{version}", sections["System"], sections["User"], sha256_hex(text))
