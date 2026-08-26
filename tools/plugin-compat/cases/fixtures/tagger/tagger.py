"""A real Beancount plugin: tags every transaction over a threshold."""
from beancount.core import data

__plugins__ = ["tag_large"]

THRESHOLD = 100


def tag_large(entries, options_map):
    out = []
    for entry in entries:
        if isinstance(entry, data.Transaction):
            total = sum(
                abs(p.units.number)
                for p in entry.postings
                if p.units is not None and p.units.number > 0
            )
            if total >= THRESHOLD:
                entry = entry._replace(tags=(entry.tags or frozenset()) | {"large"})
        out.append(entry)
    return out, []
