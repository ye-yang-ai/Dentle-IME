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
    if "t9_initials" not in columns:
        connection.execute("ALTER TABLE entries ADD COLUMN t9_initials TEXT")
    connection.execute("DROP INDEX IF EXISTS entries_t9")
    connection.execute("DROP INDEX IF EXISTS entries_t9_initials")
    rows = connection.execute("SELECT rowid, pinyin, initials FROM entries")
    connection.executemany(
        "UPDATE entries SET t9_code = ?, t9_initials = ? WHERE rowid = ?",
        ((pinyin.translate(T9), initials.translate(T9) if initials else "", rowid)
         for rowid, pinyin, initials in rows),
    )
    connection.execute(
        "CREATE INDEX entries_t9 ON entries (t9_code, rank DESC, freq DESC)"
    )
    connection.execute(
        "CREATE INDEX entries_t9_initials ON entries (t9_initials, rank DESC, freq DESC)"
    )
    transition_columns = {row[1] for row in connection.execute("PRAGMA table_info(transitions)")}
    if "t9_initials" not in transition_columns:
        connection.execute("ALTER TABLE transitions ADD COLUMN t9_initials TEXT")
    connection.execute("DROP INDEX IF EXISTS transitions_t9_initials")
    transitions = connection.execute("SELECT rowid, initials FROM transitions")
    connection.executemany(
        "UPDATE transitions SET t9_initials = ? WHERE rowid = ?",
        ((initials.translate(T9) if initials else "", rowid)
         for rowid, initials in transitions),
    )
    connection.execute(
        "CREATE INDEX transitions_t9_initials ON transitions (t9_initials, score)"
    )


if __name__ == "__main__":
    with sqlite3.connect(DATABASE) as database:
        add_t9_index(database)
    with sqlite3.connect(DATABASE) as database:
        database.execute("VACUUM")
