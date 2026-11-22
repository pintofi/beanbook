"""Fava's own filter decides which entries a filter keeps; the engine only reports positions.

Run from the repository root:

    <python-with-beancount-and-fava> -m unittest discover -s engine-local/src/test/python -v

The filter box in Home, Journal and Balances takes Fava's filter grammar (`#tag`, `^link`, `-`,
`,`, parentheses, `any()`, `key:"regex"`, plain words). It must not be a second implementation, so
these tests compare the engine's answer with Fava's own `FavaLedger.get_filtered`, which is what
Fava's web pages use, and check that the positions line up with the journal `inspect` returns.
"""

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

import ledger  # noqa: E402

LEDGER = """
option "operating_currency" "USD"

2026-01-01 open Assets:Cash
2026-01-01 open Assets:Broker
2026-01-01 open Expenses:Food
2026-01-01 open Expenses:Hotel
2026-01-01 open Equity:Opening

2026-01-02 * "opening"
  Assets:Cash        1000.00 USD
  Equity:Opening

2026-01-05 * "Hotel Aurora" "conference" #trip #work ^inv-1
  Expenses:Hotel      200.00 USD
  Assets:Cash

2026-01-06 * "Cafe" "lunch on the trip" #trip
  Expenses:Food        30.00 USD
  Assets:Cash

2026-02-01 * "Grocer" "weekly shop" ^inv-1
  Expenses:Food        12.00 USD
  Assets:Cash

2026-03-01 * "Landlord" "rent" #work
  Expenses:Hotel      900.00 USD
  Assets:Cash

2026-03-09 note Expenses:Food "mentions #trip only in the comment"
"""


class MatchingTransactionsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.path = str(Path(self.dir.name) / "main.beancount")
        Path(self.path).write_text(LEDGER, encoding="utf-8")
        from fava.core import FavaLedger

        self.fava = FavaLedger(self.path)

    def journal(self, **range_):
        return json.loads(ledger.inspect(self.path, **range_))["journal"]

    def kept(self, expression, **range_):
        answer = json.loads(ledger.matching_transactions(self.path, expression, **range_))
        self.assertTrue(answer["ok"], answer)
        journal = self.journal(**range_)
        self.assertEqual(answer["transaction_count"], len(journal))
        return [journal[i]["narration"] for i in answer["positions"]]

    def fava_kept(self, expression):
        from beancount.core import data

        entries = self.fava.get_filtered(filter=expression).entries
        return [e.narration for e in entries if isinstance(e, data.Transaction)]

    def test_positions_agree_with_favas_own_filtered_ledger(self):
        for expression in [
            "#trip",
            "-#trip",
            "- #trip",
            "--#trip",
            "#trip -#trip",
            "#trip, ^inv-1",
            "(#trip, ^inv-1) -#work",
            "-(#trip ^inv-1)",
            "^inv-1",
            "-^inv-1",
            "Grocer",
            "lunch",
            "#Trip",
            "any(account:Food)",
            "payee:Cafe",
        ]:
            with self.subTest(expression):
                self.assertEqual(self.kept(expression), self.fava_kept(expression))

    def test_a_minus_leaves_entries_out_and_a_plain_word_is_a_text_search(self):
        self.assertEqual(self.kept("-#trip"), ["opening", "weekly shop", "rent"])
        self.assertEqual(self.kept("#trip"), ["conference", "lunch on the trip"])
        # As in Fava, a bare word searches payee and narration, it is not a tag.
        self.assertEqual(self.kept("trip"), ["lunch on the trip"])

    def test_commas_and_parentheses_work_as_they_do_in_fava(self):
        self.assertEqual(self.kept("#trip, ^inv-1"), ["conference", "lunch on the trip", "weekly shop"])
        self.assertEqual(self.kept("(#trip, ^inv-1) -#work"), ["lunch on the trip", "weekly shop"])

    def test_a_filter_that_cannot_be_read_is_an_error_with_favas_message(self):
        for expression in ["#trip,", "#", "-", "()", "(#trip", "#trip)"]:
            with self.subTest(expression):
                answer = json.loads(ledger.matching_transactions(self.path, expression))
                self.assertFalse(answer["ok"])
                self.assertNotIn("positions", answer)
                self.assertTrue(answer["filter_error"])

    def test_the_answer_describes_the_ledger_the_summary_came_from(self):
        summary = json.loads(ledger.inspect(self.path))
        answer = json.loads(ledger.matching_transactions(self.path, "#trip"))
        self.assertEqual(answer["input_hash"], summary["input_hash"])
        self.assertEqual(answer["transaction_count"], len(summary["journal"]))
        # Only touched: the same ledger, so the same fingerprint.
        import os

        os.utime(self.path, (1, 1))
        touched = json.loads(ledger.matching_transactions(self.path, "#trip"))
        self.assertEqual(touched["input_hash"], summary["input_hash"])
        # Changed: a different ledger.
        Path(self.path).write_text(LEDGER + "\n; edited\n", encoding="utf-8")
        changed = json.loads(ledger.matching_transactions(self.path, "#trip"))
        self.assertNotEqual(changed["input_hash"], summary["input_hash"])

    def test_positions_follow_the_same_date_cut_as_the_journal(self):
        window = {"date_from": "2026-02-01", "date_to": "2026-03-31"}
        self.assertEqual(self.kept("-#work", **window), ["weekly shop"])
        answer = json.loads(ledger.matching_transactions(self.path, "#trip", **window))
        self.assertEqual(answer["positions"], [])
        self.assertEqual(answer["transaction_count"], 2)


class EntryCacheTest(unittest.TestCase):
    """A second filter on the same ledger reuses the loaded entries, and only when that is safe."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.addCleanup(ledger.drop_entry_cache)
        ledger.drop_entry_cache()
        self.path = str(Path(self.dir.name) / "main.beancount")
        Path(self.path).write_text(LEDGER, encoding="utf-8")
        self.age(self.path)

    @staticmethod
    def age(path):
        # Old enough that the load is not treated as racing an edit.
        os.utime(path, (1_000_000_000, 1_000_000_000))

    def ask(self, expression="#trip", **kwargs):
        return json.loads(ledger.matching_transactions(self.path, expression, **kwargs))

    def test_the_second_filter_on_an_unchanged_ledger_does_not_load_again(self):
        first = self.ask("#trip")
        second = self.ask("-#trip")
        self.assertFalse(first["cached"])
        self.assertTrue(second["cached"])
        self.assertEqual(second["load_ms"], 0.0)
        self.assertEqual(second["positions"], self.ask("-#trip")["positions"])
        self.assertEqual(second["input_hash"], first["input_hash"])

    def test_an_edit_is_never_answered_from_the_old_entries(self):
        self.ask()
        added = '\n2026-04-01 * "New" "added later" #trip\n  Expenses:Food 1.00 USD\n  Assets:Cash\n'
        Path(self.path).write_text(LEDGER + added)
        self.age(self.path)
        again = self.ask()
        self.assertFalse(again["cached"])
        self.assertEqual(again["transaction_count"], 6)

    def test_a_file_that_was_only_touched_still_hits(self):
        self.ask()
        os.utime(self.path, (1_100_000_000, 1_100_000_000))
        self.assertTrue(self.ask()["cached"])

    def test_a_load_that_may_have_raced_an_edit_is_not_kept(self):
        Path(self.path).touch()  # modified just now, inside the window
        self.ask()
        self.assertFalse(self.ask()["cached"])

    def test_another_plugin_set_or_ledger_is_a_different_entry(self):
        self.ask()
        self.assertFalse(self.ask(plugin_dirs=self.dir.name)["cached"])
        other = Path(self.dir.name) / "other.beancount"
        other.write_text(LEDGER, encoding="utf-8")
        self.age(str(other))
        self.assertFalse(json.loads(ledger.matching_transactions(str(other), "#trip"))["cached"])
        self.assertFalse(self.ask()["cached"])  # one ledger at a time: the first was replaced

    def test_dropping_the_cache_makes_the_next_filter_load(self):
        self.ask()
        ledger.drop_entry_cache()
        self.assertFalse(self.ask()["cached"])


VIEW_LEDGER = """
option "operating_currency" "USD"
2020-01-01 open Assets:Bank
2020-01-01 open Assets:Broker
2020-01-01 open Income:Salary
2020-01-01 open Expenses:Rent
2020-01-01 open Expenses:Food

2020-01-05 * "Employer" "salary"
  Assets:Bank          1000.00 USD
  Income:Salary

2020-02-01 * "Landlord" "Feb rent" #home
  Expenses:Rent         250.00 USD
  Assets:Bank

2020-02-10 * "Broker" "buy"
  Assets:Broker              2 ACME {50.00 USD}
  Assets:Bank          -100.00 USD

2020-02-20 price ACME 60.00 USD

2020-03-01 * "Landlord" "Mar rent" #home
  Expenses:Rent         250.00 USD
  Assets:Bank

2020-03-05 * "Cafe" "coffee" #trip
  Expenses:Food          10.00 USD
  Assets:Bank

2020-04-01 price ACME 80.00 USD

2020-04-15 * "Market" "big shop"
  Expenses:Food         400.00 USD
  Assets:Bank
"""


class ViewTest(unittest.TestCase):
    """The view is Fava's own filtered ledger: same entries, same opening balances, same balances."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.addCleanup(ledger.drop_entry_cache)
        self.path = str(Path(self.dir.name) / "main.beancount")
        Path(self.path).write_text(VIEW_LEDGER, encoding="utf-8")
        from fava.core import FavaLedger

        self.fava = FavaLedger(self.path)
        self.journal = json.loads(ledger.inspect(self.path))["journal"]

    def view(self, **kwargs):
        answer = json.loads(ledger.view(self.path, **kwargs))
        self.assertTrue(answer["ok"], answer)
        return answer

    def narrations(self, answer):
        return [self.journal[i]["narration"] for i in answer["positions"]]

    def fava_filtered(self, time="", filter=""):
        return self.fava.get_filtered(time=time or None, filter=filter or None)

    def test_the_kept_transactions_and_opening_rows_are_favas(self):
        from beancount.core import data

        for time, expression in [
            ("2020-03", ""),
            ("", "#home"),
            ("2020-03", "#home"),
            ("2020-02", "-#home"),
            ("2020-Q1", "#trip, ^none"),
            ("FY2020", ""),
        ]:
            with self.subTest(time=time, filter=expression):
                answer = self.view(time=time, tags=expression)
                theirs = [e for e in self.fava_filtered(time, expression).entries if isinstance(e, data.Transaction)]
                # Beancount marks the rows it makes up for a period "S" (summarization) or "C" (conversion).
                real = [e for e in theirs if e.flag not in ("S", "C")]
                made_up = [e for e in theirs if e.flag in ("S", "C")]
                self.assertEqual(sorted(e.narration for e in real), sorted(self.narrations(answer)))
                self.assertEqual(len(made_up), len(answer["summary_rows"]))
                ours = sorted(
                    (p["account"], p["number"], p["currency"])
                    for row in answer["summary_rows"]
                    for p in row["postings"]
                    if p.get("number") is not None
                )
                favas = sorted(
                    (p.account, format(p.units.number, "f"), p.units.currency)
                    for e in made_up
                    for p in e.postings
                    if p.units
                )
                self.assertEqual(
                    [(a, c) for a, _n, c in ours], [(a, c) for a, _n, c in favas]
                )

    def test_a_period_shows_balances_at_its_end_not_a_plain_cut(self):
        plain = json.loads(ledger.inspect(self.path, date_from="2020-03-01", date_to="2020-03-31"))
        march = self.view(time="2020-03")
        bank = next(a for a in march["accounts"] if a["name"] == "Assets:Bank")
        plain_bank = next(a for a in plain["accounts"] if a["name"] == "Assets:Bank")
        # 1000 - 250 - 100 (earlier months carried in) - 250 - 10 in March
        self.assertEqual(bank["positions"], [{"number": "390.00", "currency": "USD"}])
        self.assertNotEqual(bank["positions"], plain_bank["positions"])
        broker = next(a for a in march["accounts"] if a["name"] == "Assets:Broker")
        # Two ACME at the 60 USD price of 20 February, the latest on or before the end of March.
        self.assertEqual(broker["at_value"], [{"number": "120.00", "currency": "USD"}])
        self.assertEqual((march["begin"], march["end"]), ("2020-03-01", "2020-03-31"))

    def test_balances_under_a_filter_match_favas_tree(self):
        from decimal import Decimal

        answer = self.view(time="2020-03", tags="#home")
        tree = self.fava_filtered("2020-03", "#home").root_tree
        for row in answer["accounts"]:
            held = {}
            for (currency, _cost), number in tree.get(row["name"]).balance.items():
                held[currency] = held.get(currency, Decimal(0)) + number
            theirs = sorted((c, n) for c, n in held.items() if n != 0)
            ours = sorted((a["currency"], Decimal(a["number"])) for a in row["positions"])
            self.assertEqual(theirs, ours, row["name"])

    def test_the_units_range_is_favas_own_amount_filter(self):
        for low, high, expression in [("200", "", ">=200"), ("", "20", "<=20"), ("5", "300", ">=5 <=300")]:
            with self.subTest(expression):
                ours = self.view(amount_min=low, amount_max=high)
                self.assertEqual(self.narrations(ours), self.narrations(self.view(tags=expression)))
                theirs = [
                    e.narration for e in self.fava_filtered("", expression).entries if hasattr(e, "narration")
                ]
                self.assertEqual(sorted(self.narrations(ours)), sorted(theirs))

    def test_cost_and_market_ranges_weigh_postings_on_their_own_basis(self):
        # The purchase is 2 ACME: 100 USD at cost and 160 USD at the 80 USD price of the last day.
        self.assertIn("buy", self.narrations(self.view(amount_max="5")))
        self.assertNotIn("buy", self.narrations(self.view(amount_max="5", amount_basis="cost")))
        self.assertIn("buy", self.narrations(self.view(amount_min="150", amount_basis="market")))
        self.assertEqual(
            ["salary", "big shop"], self.narrations(self.view(amount_min="300", amount_basis="cost")),
        )

    def test_an_input_that_cannot_be_used_is_refused_with_its_field_and_a_reason(self):
        cases = [
            ({"time": "nonsense"}, "time"),
            ({"time": "2020-02-30"}, "time"),
            ({"tags": "#trip,"}, "tags"),
            ({"text": "(unclosed"}, "text"),
            ({"amount_min": "abc"}, "amount"),
            ({"amount_min": "-5"}, "amount"),
            ({"amount_min": "10", "amount_max": "5"}, "amount"),
            ({"amount_basis": "weird"}, "amount"),
        ]
        for kwargs, field in cases:
            with self.subTest(kwargs):
                answer = json.loads(ledger.view(self.path, **kwargs))
                self.assertFalse(answer["ok"])
                self.assertEqual(answer["field"], field)
                self.assertTrue(answer["message"])
                self.assertNotIn("positions", answer)

    def test_no_input_at_all_is_the_whole_ledger(self):
        answer = self.view()
        self.assertEqual(len(answer["positions"]), answer["transaction_count"])
        self.assertEqual(answer["summary_rows"], [])
        self.assertEqual(answer["input_hash"], json.loads(ledger.inspect(self.path))["input_hash"])


if __name__ == "__main__":
    unittest.main()
