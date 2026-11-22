"""Add a syllable trie for mixed full-pinyin and initials lookup."""

from pathlib import Path
from collections import Counter
import sqlite3

from pypinyin import Style, pinyin


DATABASE = Path(__file__).resolve().parents[1] / "app/src/main/assets/pinyin.db"


def syllables(word: str, key: str) -> tuple[str, ...] | None:
    readings = [tuple(reading.replace("ü", "v") for reading in choices)
                for choices in pinyin(word, style=Style.NORMAL, heteronym=True)]
    memo = {}

    def align(position: int, offset: int) -> tuple[str, ...] | None:
        if position == len(readings):
            return () if offset == len(key) else None
        state = (position, offset)
        if state not in memo:
            memo[state] = None
            for reading in readings[position]:
                if key.startswith(reading, offset):
                    rest = align(position + 1, offset + len(reading))
                    if rest is not None:
                        memo[state] = (reading,) + rest
                        break
        return memo[state]

    return align(0, 0)


def add_mixed_index(connection: sqlite3.Connection) -> None:
    connection.execute("DROP TABLE IF EXISTS mixed_entries")
    connection.execute("DROP TABLE IF EXISTS mixed_nodes")
    connection.execute("DROP TABLE IF EXISTS mixed_pairs")
    connection.execute(
        "CREATE TABLE mixed_nodes (id INTEGER PRIMARY KEY, parent INTEGER NOT NULL, "
        "syllable TEXT NOT NULL)"
    )
    connection.execute(
        "CREATE TABLE mixed_entries (node INTEGER NOT NULL, entry_rowid INTEGER NOT NULL)"
    )
    nodes: dict[tuple[int, str], int] = {}
    next_id = 1
    for rowid, word, key in connection.execute("SELECT rowid, word, pinyin FROM entries"):
        parts = syllables(word, key)
        if not parts:
            continue
        parent = 0
        for part in parts:
            edge = (parent, part)
            node = nodes.get(edge)
            if node is None:
                node = next_id
                next_id += 1
                nodes[edge] = node
                connection.execute(
                    "INSERT INTO mixed_nodes VALUES (?, ?, ?)", (node, parent, part)
                )
            parent = node
        connection.execute("INSERT INTO mixed_entries VALUES (?, ?)", (parent, rowid))
    connection.execute("CREATE INDEX mixed_nodes_parent ON mixed_nodes (parent)")
    connection.execute("CREATE INDEX mixed_entries_node ON mixed_entries (node)")
    pair_counts: Counter[str] = Counter()
    for left, right in connection.execute("SELECT left_word, right_word FROM transitions"):
        text = left + right
        pair_counts.update({text[index:index + 2] for index in range(len(text) - 1)})
    connection.execute("CREATE TABLE mixed_pairs (pair TEXT PRIMARY KEY, frequency INTEGER NOT NULL)")
    connection.executemany("INSERT INTO mixed_pairs VALUES (?, ?)", pair_counts.items())


if __name__ == "__main__":
    with sqlite3.connect(DATABASE) as database:
        add_mixed_index(database)
        database.commit()
        database.execute("VACUUM")
