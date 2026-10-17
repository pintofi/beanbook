"""Tests for the plugin loader and the Play bridge boundary.

Run from the repository root:

    python3 -m unittest discover -s engine-local/src/test/python -v

These run on the desktop, where Chaquopy's `java` module does not exist. That is a real
limit: it means they prove the *guard* refuses the import, not that Chaquopy's bridge is
reachable without it. The device half of that lives in `PluginBridgeTest`.
"""

import importlib
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

import plugins  # noqa: E402


class BridgeGuardTest(unittest.TestCase):
    def setUp(self):
        self.guard = plugins.BridgeGuard()
        self.addCleanup(self.guard.remove)
        for name in list(sys.modules):
            if name.split(".")[0] in ("java", "jnius", "android", "javalang"):
                del sys.modules[name]

    def test_blocks_each_bridge_module_and_its_submodules(self):
        self.guard.install()
        for name in ("java", "java.lang", "jnius", "android", "android.os", "javax.crypto"):
            with self.assertRaises(plugins.BridgeBlocked, msg=name):
                __import__(name)

    def test_import_module_is_blocked_even_when_the_module_is_already_cached(self):
        # importlib.import_module returns straight from sys.modules without consulting a
        # single finder. On a device `java` is always already imported — the app uses it —
        # so this was the one path that stayed open once the guard stopped evicting it.
        sys.modules["java"] = types.ModuleType("java")
        self.addCleanup(sys.modules.pop, "java", None)
        self.guard.install()

        with self.assertRaises(plugins.BridgeBlocked):
            importlib.import_module("java")

    def test_import_module_still_works_for_everything_else(self):
        self.guard.install()
        self.assertIsNotNone(importlib.import_module("fractions"))

    def test_import_module_is_restored_afterwards(self):
        before = importlib.import_module
        self.guard.install()
        self.guard.remove()
        self.assertIs(before, importlib.import_module)

    def test_refusal_says_which_module_and_why(self):
        self.guard.install()
        with self.assertRaises(plugins.BridgeBlocked) as caught:
            __import__("java.lang")
        # The parent package is imported first, so that is what gets refused and what the
        # message names. Saying "java.lang" here would be reporting a step that never ran.
        message = str(caught.exception)
        self.assertIn("java", message)
        self.assertIn("Play", message)



    def test_a_package_that_only_looks_like_the_bridge_is_left_alone(self):
        # javalang is a real package. Blocking it would be a bug a user could not work
        # around, and the name is close enough that a prefix check gets it wrong.
        self.guard.install()
        sys.modules["javalang"] = types.ModuleType("javalang")
        self.addCleanup(sys.modules.pop, "javalang", None)
        import javalang  # noqa: F401 — the import itself is the assertion

    def test_a_bridge_already_in_the_module_cache_is_still_refused(self):
        # `java` is Chaquopy's own module and the app itself needs it, so the guard must not
        # evict it — two copies of the bridge would be worse than none. It does not need to:
        # the wrapper around builtins.__import__ runs before the module cache is consulted.
        fake = types.ModuleType("java")
        fake.marker = "the real bridge"
        sys.modules["java"] = fake
        self.addCleanup(sys.modules.pop, "java", None)

        self.guard.install()

        self.assertIn("java", sys.modules, "the app's own Chaquopy module was evicted")
        with self.assertRaises(plugins.BridgeBlocked):
            exec("import java", {})

    def test_removing_the_guard_leaves_the_import_system_as_it_was(self):
        before = list(sys.meta_path)
        self.guard.install()
        self.assertIsNot(before, sys.meta_path)
        self.guard.remove()
        self.assertEqual(before, sys.meta_path)

    def test_installing_twice_does_not_stack_up_finders(self):
        self.guard.install()
        self.guard.install()
        self.assertEqual(1, sys.meta_path.count(self.guard))

    def test_ordinary_imports_still_work_with_the_guard_installed(self):
        self.guard.install()
        import json  # noqa: F401
        import decimal  # noqa: F401


class DelegationTest(unittest.TestCase):
    """What the guard delegates to, and why it matters more than it looks.

    An earlier version delegated to `importlib.__import__` and also pulled Chaquopy's path
    finder out of `sys.meta_path`. On a device, Beancount's parser then failed
    `assert isinstance(number, Decimal)` on an object whose repr was `Decimal('12.00')` —
    two `decimal` modules in one process. The exact trigger was never isolated (see
    `BridgeGuard`), which is all the more reason to pin the rule that removed it: hand every
    ordinary import straight back to whatever `builtins.__import__` already was, and let
    Chaquopy load Chaquopy's packages.
    """

    def setUp(self):
        self.guard = plugins.BridgeGuard()
        self.addCleanup(self.guard.remove)

    def test_ordinary_imports_are_handed_to_the_import_that_was_already_installed(self):
        import builtins

        seen = []
        original = builtins.__import__

        def recording_import(name, *args, **kwargs):
            seen.append(name)
            return original(name, *args, **kwargs)

        builtins.__import__ = recording_import
        self.addCleanup(setattr, builtins, "__import__", original)

        self.guard.install()
        exec("import fractions", {})

        self.assertIn("fractions", seen, "the guard bypassed the installed import")

    def test_a_name_only_java_could_provide_is_refused(self):
        # Stands in for Chaquopy's import_override: it satisfies names that no Python module
        # provides. androidx is on no denylist, so the only thing that can catch it is the
        # rule "Python could not have provided this".
        import builtins

        original = builtins.__import__

        def java_ish_import(name, *args, **kwargs):
            root = name.split(".")[0]
            if root in ("androidx", "dalvik"):
                module = types.ModuleType(name)
                sys.modules[name] = module
                return module
            return original(name, *args, **kwargs)

        builtins.__import__ = java_ish_import
        self.addCleanup(setattr, builtins, "__import__", original)
        self.addCleanup(sys.modules.pop, "androidx", None)

        self.guard.install()

        with self.assertRaises(plugins.BridgeBlocked):
            exec("import androidx.core", {})
        self.assertNotIn("androidx", sys.modules, "the refused module was left behind")

    def test_a_module_that_genuinely_does_not_exist_still_says_so(self):
        # Reporting a typo as "the bridge is not available" would send someone hunting for
        # a policy problem they do not have.
        self.guard.install()

        with self.assertRaises(ModuleNotFoundError):
            exec("import definitely_not_a_real_module_xyz", {})


class LoadPluginsTest(unittest.TestCase):
    def setUp(self):
        import tempfile

        self.dir = tempfile.mkdtemp(prefix="plugins-test-")
        self.addCleanup(lambda: __import__("shutil").rmtree(self.dir, ignore_errors=True))

    def write(self, name, body):
        Path(self.dir, name + ".py").write_text(body)
        sys.modules.pop(name, None)
        self.addCleanup(sys.modules.pop, name, None)

    def test_a_working_plugin_is_reported_as_loaded(self):
        self.write("good", "__plugins__ = []\n")

        result = plugins.load_plugins(self.dir, ["good"], allow_bridge=True)

        self.assertEqual(1, result["loaded"])
        self.assertEqual(0, result["failed"])
        self.assertTrue(result["runs"][0]["ok"])

    def test_one_broken_plugin_does_not_stop_the_others(self):
        # A plugin that explodes should cost its own feature and nothing else.
        self.write("fine_one", "value = 1\n")
        self.write("broken", "raise RuntimeError('I am broken')\n")
        self.write("fine_two", "value = 2\n")

        result = plugins.load_plugins(
            self.dir, ["fine_one", "broken", "fine_two"], allow_bridge=True
        )

        self.assertEqual(2, result["loaded"])
        self.assertEqual(1, result["failed"])
        failed = next(r for r in result["runs"] if not r["ok"])
        self.assertEqual("RuntimeError", failed["error_type"])
        self.assertIn("I am broken", failed["error"])

    def test_a_plugin_that_calls_sys_exit_is_caught_like_any_other_failure(self):
        # SystemExit does not inherit from Exception. Catching only Exception here would
        # let one plugin take the interpreter down with it.
        self.write("quitter", "import sys\nsys.exit(3)\n")

        result = plugins.load_plugins(self.dir, ["quitter"], allow_bridge=True)

        self.assertEqual(1, result["failed"])
        self.assertEqual("SystemExit", result["runs"][0]["error_type"])

    def test_plugin_output_is_captured_rather_than_printed(self):
        self.write("chatty", "print('hello from a plugin')\n")

        result = plugins.load_plugins(self.dir, ["chatty"], allow_bridge=True)

        self.assertIn("hello from a plugin", result["runs"][0]["output"])

    def test_a_plugin_that_prints_endlessly_cannot_fill_the_log(self):
        self.write("noisy", "print('x' * 100000)\n")

        result = plugins.load_plugins(self.dir, ["noisy"], allow_bridge=True)

        self.assertLessEqual(len(result["runs"][0]["output"]), 4000)

    def test_a_plugin_importing_the_bridge_fails_and_is_named_in_the_refusals(self):
        self.write("bridged", "import java\n")

        result = plugins.load_plugins(self.dir, ["bridged"], allow_bridge=False)

        self.assertEqual(1, result["failed"])
        self.assertEqual("BridgeBlocked", result["runs"][0]["error_type"])
        self.assertIn("java", result["bridge_refusals"])

    def test_module_names_may_arrive_as_a_comma_separated_string(self):
        # This is the form that crosses the Java bridge, so it is the form most likely to
        # break without anyone noticing until a device run.
        self.write("one", "value = 1\n")
        self.write("two", "value = 2\n")

        result = plugins.load_plugins(self.dir, "one, two", allow_bridge=True)

        self.assertEqual(2, result["loaded"])

    def test_the_plugin_directory_is_not_left_on_the_import_path(self):
        self.write("good", "__plugins__ = []\n")
        before = list(sys.path)

        plugins.load_plugins(self.dir, ["good"], allow_bridge=True)

        self.assertEqual(before, sys.path)

    def test_the_guard_is_removed_even_when_a_plugin_raises(self):
        self.write("broken", "raise RuntimeError('boom')\n")
        before = list(sys.meta_path)

        plugins.load_plugins(self.dir, ["broken"], allow_bridge=False)

        self.assertEqual(before, sys.meta_path)


class LoadJournalTest(unittest.TestCase):
    """The marker that survives a plugin taking the process down with it."""

    def setUp(self):
        import tempfile

        self.dir = tempfile.mkdtemp(prefix="journal-test-")
        self.addCleanup(lambda: __import__("shutil").rmtree(self.dir, ignore_errors=True))
        self.journal = str(Path(self.dir, "loading.marker"))

    def write(self, name, body):
        Path(self.dir, name + ".py").write_text(body)
        sys.modules.pop(name, None)
        self.addCleanup(sys.modules.pop, name, None)

    def test_the_marker_is_gone_after_a_clean_run(self):
        self.write("fine", "value = 1\n")

        plugins.load_plugins(self.dir, "fine", allow_bridge=True, journal_path=self.journal)

        self.assertFalse(Path(self.journal).exists())

    def test_the_marker_is_gone_even_when_a_plugin_raises(self):
        # An exception is caught and reported, so the process is fine and the plugin is not
        # to blame for a crash. Leaving the marker would disable a plugin that merely failed.
        self.write("broken", "raise RuntimeError('boom')\n")

        plugins.load_plugins(self.dir, "broken", allow_bridge=True, journal_path=self.journal)

        self.assertFalse(Path(self.journal).exists())

    def test_the_marker_names_the_plugin_being_imported_when_the_process_dies(self):
        # os._exit skips every finally block, atexit handler and buffer flush — the closest
        # thing to a segfault that can be arranged on purpose. Run in a child so the test
        # survives it.
        import subprocess
        import textwrap

        Path(self.dir, "fine.py").write_text("value = 1\n")
        Path(self.dir, "killer.py").write_text("import os\nos._exit(9)\n")

        script = textwrap.dedent(
            f"""
            import sys
            sys.path.insert(0, {str(Path(__file__).resolve().parents[2] / "main" / "python")!r})
            import plugins
            plugins.load_plugins({self.dir!r}, "fine,killer", allow_bridge=True,
                                 journal_path={self.journal!r}, journal_label="tidy-up")
            """
        )
        result = subprocess.run([sys.executable, "-c", script], capture_output=True)

        self.assertEqual(9, result.returncode, "the child was supposed to die")
        self.assertTrue(Path(self.journal).exists(), "no marker survived the crash")
        self.assertEqual(["tidy-up", "killer"], Path(self.journal).read_text().split("\n"))


class RedactTest(unittest.TestCase):
    def test_a_named_secret_never_survives_into_the_log(self):
        text = "connecting with token sk-live-abcdef and finishing"

        cleaned = plugins.redact(text, secrets=["sk-live-abcdef"])

        self.assertNotIn("sk-live-abcdef", cleaned)
        self.assertIn("connecting with token", cleaned)

    def test_redaction_of_nothing_changes_nothing(self):
        self.assertEqual("plain", plugins.redact("plain", secrets=[]))
        self.assertEqual("plain", plugins.redact("plain", secrets=[""]))


class ActivationTest(unittest.TestCase):
    """Making installed plugins importable, and — just as important — un-importable again."""

    def setUp(self):
        import tempfile

        self.dir = tempfile.mkdtemp(prefix="activate-test-")
        self.addCleanup(lambda: __import__("shutil").rmtree(self.dir, ignore_errors=True))
        Path(self.dir, "userplug.py").write_text("VALUE = 1\n")
        sys.modules.pop("userplug", None)
        self.addCleanup(sys.modules.pop, "userplug", None)

    def test_a_plugin_is_importable_inside_the_block(self):
        with plugins.activated(self.dir, allow_bridge=True):
            import userplug

            self.assertEqual(1, userplug.VALUE)

    def test_the_path_is_removed_afterwards(self):
        before = list(sys.path)
        with plugins.activated(self.dir, allow_bridge=True):
            pass
        self.assertEqual(before, sys.path)

    def test_a_plugin_stops_being_importable_once_it_is_no_longer_activated(self):
        # The bug this exists for. importlib.import_module returns a cached module without
        # consulting sys.path, so a plugin that has been imported once keeps working after it
        # is disabled or uninstalled — for the whole life of the process. A user who turns
        # off a misbehaving plugin would see it carry on.
        with plugins.activated(self.dir, allow_bridge=True):
            importlib.import_module("userplug")
        self.assertNotIn("userplug", sys.modules)

        with self.assertRaises(ImportError):
            with plugins.activated("", allow_bridge=True):
                importlib.import_module("userplug")

    def test_an_edited_plugin_is_picked_up_on_the_next_activation(self):
        with plugins.activated(self.dir, allow_bridge=True):
            self.assertEqual(1, importlib.import_module("userplug").VALUE)

        # Longer, not just different: CPython decides a cached .pyc is fresh from the source
        # file's size and mtime-in-seconds, so an edit that keeps both the same within one
        # second is served stale. That is a real, if narrow, hole and it is not one this
        # layer can close — hash-based .pyc files are not the default.
        Path(self.dir, "userplug.py").write_text("VALUE = 22  # edited\n")

        with plugins.activated(self.dir, allow_bridge=True):
            self.assertEqual(22, importlib.import_module("userplug").VALUE)

    def test_a_plugin_installed_since_the_last_load_is_found(self):
        with plugins.activated(self.dir, allow_bridge=True):
            importlib.import_module("userplug")

        Path(self.dir, "arrived_later.py").write_text("VALUE = 3\n")
        self.addCleanup(sys.modules.pop, "arrived_later", None)

        # FileFinder caches directory listings, so without invalidate_caches() this raises.
        with plugins.activated(self.dir, allow_bridge=True):
            self.assertEqual(3, importlib.import_module("arrived_later").VALUE)

    def test_modules_that_are_not_plugins_are_left_alone(self):
        import json as before

        with plugins.activated(self.dir, allow_bridge=True):
            pass

        self.assertIn("json", sys.modules)
        self.assertIs(before, sys.modules["json"])

    def test_nothing_happens_when_there_are_no_plugin_directories(self):
        before_path = list(sys.path)
        before_modules = set(sys.modules)

        with plugins.activated("", allow_bridge=False):
            pass

        self.assertEqual(before_path, sys.path)
        self.assertEqual(before_modules, set(sys.modules))


class ScriptRunnerTest(unittest.TestCase):
    """Running a user script with a deadline, and stopping one that will not stop itself."""

    def setUp(self):
        import tempfile

        self.dir = tempfile.mkdtemp(prefix="script-test-")
        self.addCleanup(lambda: __import__("shutil").rmtree(self.dir, ignore_errors=True))

    def script(self, body):
        path = Path(self.dir, "script.py")
        path.write_text(body)
        return str(path)

    def test_a_script_that_finishes_reports_its_output(self):
        path = self.script("print('total:', 2 + 2)\n")

        result = plugins.run_script(path, timeout_seconds=5)

        self.assertTrue(result["ok"], result["error"])
        self.assertIn("total: 4", result["output"])
        self.assertFalse(result["timed_out"])

    def test_the_ledger_path_is_available_to_the_script(self):
        path = self.script("print('ledger is', ledger_path)\n")

        result = plugins.run_script(path, ledger_path="/tmp/main.beancount", timeout_seconds=5)

        self.assertIn("/tmp/main.beancount", result["output"])

    def test_a_script_written_as_a_program_runs_as_one(self):
        """`if __name__ == "__main__"` and `sys.argv[1]`, as on the desktop."""
        path = self.script(
            "import sys\n"
            "def main(ledger):\n"
            "    print('ledger:', ledger)\n"
            "if __name__ == '__main__':\n"
            "    main(sys.argv[1])\n"
            "    sys.exit(0)\n"
        )
        before = sys.argv

        result = plugins.run_script(path, ledger_path="/tmp/main.beancount", timeout_seconds=5)

        self.assertTrue(result["ok"], result["error"])
        self.assertIn("ledger: /tmp/main.beancount", result["output"])
        self.assertIs(before, sys.argv)

    def test_a_non_zero_exit_is_a_failure(self):
        path = self.script("import sys\nsys.exit(3)\n")

        result = plugins.run_script(path, timeout_seconds=5)

        self.assertFalse(result["ok"])
        self.assertEqual("SystemExit", result["error_type"])

    def test_long_output_is_kept_and_a_cut_is_said(self):
        path = self.script("print('x' * 250000)\n")

        result = plugins.run_script(path, timeout_seconds=5)

        self.assertTrue(result["output"].startswith("x" * 100000))
        self.assertIn("[output cut at", result["output"])

    def test_a_script_runs_in_the_folder_of_its_ledger(self):
        """a relative path in a script names a file beside the ledger, not the app."""
        ledger = Path(self.dir, "main.beancount")
        ledger.write_text("")
        Path(self.dir, "statements.csv").write_text("date,amount\n")
        path = self.script(
            "import os\n"
            "print('here:', os.getcwd())\n"
            "print('read:', open('statements.csv').read().strip())\n"
        )

        before = os.getcwd()
        result = plugins.run_script(path, ledger_path=str(ledger), timeout_seconds=5)

        self.assertTrue(result["ok"], result["error"])
        self.assertIn("date,amount", result["output"])
        self.assertIn(os.path.realpath(self.dir), os.path.realpath(
            result["output"].split("here:")[1].splitlines()[0].strip()
        ))
        self.assertEqual(before, os.getcwd())

    def test_an_endless_loop_is_stopped_by_the_deadline(self):
        path = self.script("while True:\n    pass\n")

        result = plugins.run_script(path, timeout_seconds=0.4)

        self.assertTrue(result["timed_out"])
        self.assertFalse(result["ok"])
        self.assertLess(result["duration_ms"], 3000, "the deadline was not enforced promptly")

    def test_a_script_cannot_swallow_its_own_deadline(self):
        # `except Exception` is everywhere in real scripts. If the stop were an Exception,
        # this loop would keep running and the app would report a clean finish.
        path = self.script(
            "while True:\n"
            "    try:\n"
            "        pass\n"
            "    except Exception:\n"
            "        pass\n"
        )

        result = plugins.run_script(path, timeout_seconds=0.4)

        self.assertTrue(result["timed_out"])
        self.assertLess(result["duration_ms"], 3000)

    def test_a_script_can_be_cancelled_from_another_thread(self):
        import threading

        path = self.script("while True:\n    pass\n")
        handle = "run-1"
        threading.Timer(0.3, plugins.cancel_script, args=(handle,)).start()

        result = plugins.run_script(path, timeout_seconds=30, handle=handle)

        self.assertTrue(result["cancelled"], result)
        self.assertFalse(result["timed_out"])
        self.assertLess(result["duration_ms"], 5000)

    def test_a_cancelled_handle_does_not_stop_the_next_run(self):
        plugins.cancel_script("run-2")
        path = self.script("value = 1\n")

        result = plugins.run_script(path, timeout_seconds=5, handle="run-2")

        self.assertTrue(result["ok"], result)
        self.assertFalse(result["cancelled"])

    def test_a_script_blocked_in_a_c_call_overruns_and_the_result_says_so(self):
        # The honest limit. A trace function fires between bytecode lines, so a single long
        # C call cannot be interrupted. This asserts the documented behaviour rather than
        # pretending the deadline is a guarantee.
        path = self.script("import time\ntime.sleep(1.0)\n")

        result = plugins.run_script(path, timeout_seconds=0.2)

        self.assertGreater(result["duration_ms"], 900, "sleep was somehow interrupted")
        self.assertGreater(
            result["overran_by_ms"], 0, "an overrun must be reported, not hidden"
        )

    def test_a_failing_script_reports_the_error_without_raising(self):
        path = self.script("raise ValueError('nope')\n")

        result = plugins.run_script(path, timeout_seconds=5)

        self.assertFalse(result["ok"])
        self.assertEqual("ValueError", result["error_type"])
        self.assertIn("nope", result["error"])

    def test_a_script_cannot_reach_the_bridge_unless_the_edition_allows_it(self):
        path = self.script("import java\n")

        result = plugins.run_script(path, timeout_seconds=5, allow_bridge=False)

        self.assertEqual("BridgeBlocked", result["error_type"])

    def test_tracing_is_off_again_afterwards(self):
        # A trace function left installed would slow every later import in the process to a
        # crawl, and would keep raising on a deadline that has long since passed.
        plugins.run_script(self.script("value = 1\n"), timeout_seconds=5)

        self.assertIsNone(sys.gettrace())



class CompanionLibraryTest(unittest.TestCase):
    """numpy's libopenblas has to be loaded in the engine process, not the app's."""

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="companions-")
        self.addCleanup(lambda: __import__("shutil").rmtree(self.dir, ignore_errors=True))
        self.lib = Path(self.dir, "chaquopy", "lib")
        self.lib.mkdir(parents=True)

    def test_a_real_library_is_loaded(self):
        import glob
        import shutil

        import ctypes

        # One this interpreter can load: /usr/lib32 holds a 32-bit copy on multilib systems.
        def loads(path):
            try:
                ctypes.CDLL(path)
                return True
            except OSError:
                return False

        found = [
            p for p in glob.glob("/usr/lib*/libz.so.1") + glob.glob("/lib*/libz.so.1")
            if loads(p)
        ]
        if not found:
            self.fail("this test needs a libz.so.1 on the machine to stand in for libopenblas")
        shutil.copy(found[0], self.lib / "libz.so.1")

        self.assertEqual(["libz.so.1"], plugins.preload_companion_libraries(self.dir))

    def test_something_that_is_not_a_library_is_left_for_the_import_to_report(self):
        (self.lib / "libbroken.so").write_bytes(b"not an ELF file")

        self.assertEqual([], plugins.preload_companion_libraries(self.dir))

    def test_a_directory_without_companions_does_nothing(self):
        self.assertEqual([], plugins.preload_companion_libraries(tempfile.gettempdir()))


if __name__ == "__main__":
    unittest.main()


class LoaderCacheTest(unittest.TestCase):
    """Beancount's pickle cache must not survive a change of plugins."""

    def setUp(self):
        import tempfile

        self.work = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: __import__("shutil").rmtree(self.work, ignore_errors=True))
        self.addCleanup(lambda: plugins.use_loader_cache([]))

    def test_no_plugins_keeps_beancounts_own_name(self):
        from beancount import loader

        self.assertEqual(loader.PICKLE_CACHE_FILENAME, plugins.loader_cache_pattern([]))

    def test_installing_a_plugin_changes_the_cache_name(self):
        site = self.work / "site"
        site.mkdir()
        before = plugins.loader_cache_pattern([str(site)])
        (site / "interpolate").mkdir()
        after = plugins.loader_cache_pattern([str(site)])
        self.assertNotEqual(before, after)
        self.assertTrue(after.startswith(".{filename}.") and after.endswith(".picklecache"))

    def test_a_ledger_whose_plugin_was_missing_loads_cleanly_once_it_is_installed(self):
        ledger = self.work / "main.beancount"
        ledger.write_text('plugin "late_plugin"\n\n2020-01-01 open Assets:Cash\n')
        site = self.work / "site"
        site.mkdir()

        from beancount import loader

        # Big enough ledgers are cached; force it for a tiny one.
        threshold = loader.PICKLE_CACHE_THRESHOLD
        loader.PICKLE_CACHE_THRESHOLD = 0
        self.addCleanup(lambda: setattr(loader, "PICKLE_CACHE_THRESHOLD", threshold))

        with plugins.activated(str(site), allow_bridge=True):
            _, errors, _ = loader.load_file(str(ledger))
        self.assertTrue(any("late_plugin" in str(e.message) for e in errors))

        (site / "late_plugin.py").write_text(
            "__plugins__ = ['run']\ndef run(entries, options_map):\n    return entries, []\n"
        )
        with plugins.activated(str(site), allow_bridge=True):
            _, errors, _ = loader.load_file(str(ledger))
        self.assertEqual([], errors, "the cached failure was served after the plugin arrived")

    def test_the_cache_stays_out_of_the_project_folder(self):
        """the cache sat in the project, was mirrored, and conflicted on every load."""
        import os

        root = self.work / "app-cache"
        os.environ["BEANBOOK_LOADER_CACHE_DIR"] = str(root)
        self.addCleanup(lambda: os.environ.pop("BEANBOOK_LOADER_CACHE_DIR", None))
        project = self.work / "project"
        project.mkdir()
        ledger = project / "main.beancount"
        ledger.write_text("2020-01-01 open Assets:Cash\n")
        (project / ".main.beancount.picklecache").write_text("left over from before")

        from beancount import loader

        threshold = loader.PICKLE_CACHE_THRESHOLD
        loader.PICKLE_CACHE_THRESHOLD = 0
        self.addCleanup(lambda: setattr(loader, "PICKLE_CACHE_THRESHOLD", threshold))
        pattern = plugins.use_loader_cache([])
        loader.load_file(str(ledger))
        plugins.discard_other_loader_caches(str(ledger), pattern)

        self.assertEqual([], sorted(p.name for p in project.glob("*picklecache")))
        self.assertEqual(1, len(list(root.glob("*main.beancount*picklecache"))))

    def test_two_projects_with_the_same_file_name_do_not_share_a_cache(self):
        import os

        os.environ["BEANBOOK_LOADER_CACHE_DIR"] = str(self.work / "app-cache")
        self.addCleanup(lambda: os.environ.pop("BEANBOOK_LOADER_CACHE_DIR", None))
        one = plugins._cache_filename(".{filename}.picklecache", str(self.work / "a" / "main.beancount"))
        two = plugins._cache_filename(".{filename}.picklecache", str(self.work / "b" / "main.beancount"))
        self.assertNotEqual(one, two)

    def test_caches_for_other_plugin_sets_are_discarded(self):
        ledger = self.work / "main.beancount"
        ledger.write_text("")
        (self.work / ".main.beancount.aaaa.picklecache").write_text("old")
        (self.work / ".main.beancount.bbbb.picklecache").write_text("current")
        removed = plugins.discard_other_loader_caches(str(ledger), ".{filename}.bbbb.picklecache")
        self.assertEqual([".main.beancount.aaaa.picklecache"], removed)
        self.assertTrue((self.work / ".main.beancount.bbbb.picklecache").exists())
