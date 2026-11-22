"""Add indexed T9 codes to the checked-in offline pinyin database."""

from pathlib import Path
import sqlite3


DATABASE = Path(__file__).resolve().parents[1] / "app/src/main/assets/pinyin.db"
T9 = str.maketrans({letter: digit for digit, letters in {
    "2": "abc", "3": "def", "4": "ghi", "5": "jkl",
    "6": "mno", "7": "pqrs", "8": "tuv", "9": "wxyz",
}.items() for letter in letters})


def add_t9_index(connection: sqlite3.Connection) -> None:
    columns = {row[1] for row in connection.execute("PRAGMA table_info(entries)")}
    if "t9_code" not in columns:
        connection.execute("ALTER TABLE entries ADD COLUMN t9_code TEXT")
    connection.execute("DROP INDEX IF EXISTS entries_t9")
    rows = connection.execute("SELECT rowid, pinyin FROM entries")
    connection.executemany(
        "UPDATE entries SET t9_code = ? WHERE rowid = ?",
        ((pinyin.translate(T9), rowid) for rowid, pinyin in rows),
    )
    connection.execute(
        "CREATE INDEX entries_t9 ON entries (t9_code, rank DESC, freq DESC)"
    )


if __name__ == "__main__":
    with sqlite3.connect(DATABASE) as database:
        add_t9_index(database)
    with sqlite3.connect(DATABASE) as database:
        database.execute("VACUUM")
