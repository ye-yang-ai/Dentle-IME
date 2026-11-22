"""Add the character-level pinyin index to an existing checked-in database.

This is useful when regenerating the full jieba database is not necessary. The
same logic is also used by generate_pinyin_db.py for future rebuilds.
"""

from pathlib import Path
import sqlite3

from generate_pinyin_db import HANZI_CODEPOINTS, readings
from build_initials_index import add_initials_index


DATABASE = Path(__file__).resolve().parents[1] / "app/src/main/assets/pinyin.db"


def main() -> None:
    with sqlite3.connect(DATABASE) as connection:
        rows = []
        for codepoint in HANZI_CODEPOINTS:
            word = chr(codepoint)
            for key in readings(word):
                if key.isascii() and key.isalpha():
                    rows.append((key, word, 1, 0, 1))
        connection.executemany(
            "INSERT INTO entries (pinyin, word, freq, domain, rank) "
            "SELECT ?, ?, ?, ?, ? WHERE NOT EXISTS ("
            "SELECT 1 FROM entries WHERE pinyin = ? AND word = ?)",
            ((key, word, freq, domain, rank, key, word)
             for key, word, freq, domain, rank in rows),
        )
        add_initials_index(connection)
        connection.commit()
        connection.execute("VACUUM")
        print(f"Checked {len(rows)} character readings in {DATABASE}")


if __name__ == "__main__":
    main()
