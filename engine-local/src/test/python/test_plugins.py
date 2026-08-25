"""Tests for the plugin loader and the Play bridge boundary.

Run from the repository root:

    python3 -m unittest discover -s engine-local/src/test/python -v

These run on the desktop, where Chaquopy's `java` module does not exist. That is a real
limit: it means they prove the *guard* refuses the import, not that Chaquopy's bridge is
reachable without it. The device half of that lives in `PluginBridgeTest`.
"""

import sys
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

    def test_a_bridge_already_in_the_module_cache_is_evicted_on_install(self):
        # Without this, `import java` is served straight from sys.modules and no finder is
        # ever consulted — the guard would be installed and completely bypassed.
        fake = types.ModuleType("java")
        fake.marker = "the real bridge"
        sys.modules["java"] = fake

        self.guard.install()

        self.assertNotIn("java", sys.modules)
        with self.assertRaises(plugins.BridgeBlocked):
            __import__("java")

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


class JavaFinderDetachmentTest(unittest.TestCase):
    """The half the name check cannot do.

    Chaquopy resolves *any* Java package name, so `com.chaquo.python`, `androidx.…` and
    `dalvik.system` are all importable and no denylist of names covers them. The guard
    therefore removes Chaquopy's importer from `sys.meta_path` outright.

    Chaquopy is not on the desktop, so this substitutes a finder that looks like it — same
    shape, same detection surface — and checks it is taken out and put back. Whether the
    real one is detected is a device question, answered by `PluginBridgeTest`.
    """

    class FakeJavaLoader:
        """A loader that identifies the module as having come from Java, as Chaquopy's does."""

        def create_module(self, spec):
            return None

        def exec_module(self, module):
            module.java_backed = True

    FakeJavaLoader.__module__ = "com.chaquo.python.import_hook"

    class FakeChaquopyFinder:
        """Stands in for Chaquopy's importer: resolves any Java-looking package."""

        def find_spec(self, fullname, path=None, target=None):
            if fullname.split(".")[0] in ("java", "com", "androidx", "dalvik", "org"):
                import importlib.util

                loader = JavaFinderDetachmentTest.FakeJavaLoader()
                spec = importlib.util.spec_from_loader(fullname, loader=loader)
                spec.submodule_search_locations = []
                return spec
            return None

    # Declared so _is_java_finder sees the same thing it would see for the real one.
    FakeChaquopyFinder.__module__ = "com.chaquo.python.import_hook"

    def setUp(self):
        self.finder = self.FakeChaquopyFinder()
        sys.meta_path.append(self.finder)
        self.addCleanup(
            lambda: self.finder in sys.meta_path and sys.meta_path.remove(self.finder)
        )
        for name in list(sys.modules):
            if name.split(".")[0] in ("java", "com", "androidx", "dalvik"):
                del sys.modules[name]

    def test_the_fake_finder_really_does_resolve_java_packages(self):
        # Without this the rest of the class could pass against a finder that never worked.
        import androidx  # noqa: F401
        self.assertIn("androidx", sys.modules)
        self.assertTrue(getattr(sys.modules["androidx"], "java_backed", False))

    def test_the_java_finder_is_detached_while_the_guard_is_installed(self):
        guard = plugins.BridgeGuard().install()
        self.addCleanup(guard.remove)

        self.assertNotIn(self.finder, sys.meta_path, "Chaquopy's importer was left in place")
        # androidx is not on any denylist. It must still fail, because the thing that
        # resolved it is gone.
        with self.assertRaises(ImportError):
            __import__("androidx")

    def test_the_java_finder_is_put_back_afterwards(self):
        before = list(sys.meta_path)
        guard = plugins.BridgeGuard().install()
        guard.remove()

        self.assertEqual(before, sys.meta_path)
        import androidx  # noqa: F401 — works again, so the app itself is unaffected

    def test_a_java_module_already_imported_is_evicted_too(self):
        import androidx  # noqa: F401
        self.assertIn("androidx", sys.modules)

        guard = plugins.BridgeGuard().install()
        self.addCleanup(guard.remove)

        self.assertNotIn("androidx", sys.modules, "a cached Java module would bypass the guard")


class ImportHookTest(unittest.TestCase):
    """The `builtins.__import__` half of the guard.

    This is the half that matters. On a device, a `sys.meta_path` finder blocked
    `importlib.import_module("java")` while the ordinary statement `from java.lang import
    System` went straight past it, because Chaquopy replaces `builtins.__import__` and the
    statement form calls that. A guard that only watched meta_path looked like it worked.
    """

    def setUp(self):
        self.guard = plugins.BridgeGuard()
        self.addCleanup(self.guard.remove)
        self.original = __builtins__["__import__"] if isinstance(__builtins__, dict) else __builtins__.__import__

    def test_the_import_builtin_is_replaced_and_put_back(self):
        import builtins

        before = builtins.__import__
        self.guard.install()
        self.assertIsNot(before, builtins.__import__, "builtins.__import__ was not replaced")
        self.guard.remove()
        self.assertIs(before, builtins.__import__, "builtins.__import__ was not restored")

    def test_an_import_statement_is_refused_not_only_import_module(self):
        self.guard.install()

        # The statement form compiles to a builtins.__import__ call. This is the exact
        # shape that slipped through the meta_path-only guard on a device.
        with self.assertRaises(plugins.BridgeBlocked):
            exec("from java.lang import System", {})
        with self.assertRaises(plugins.BridgeBlocked):
            exec("import android.os", {})

    def test_ordinary_imports_are_delegated_untouched(self):
        self.guard.install()

        scope = {}
        exec("import json\nimport xml.etree.ElementTree as ET\nfrom decimal import Decimal", scope)
        self.assertIn("Decimal", scope)

    def test_a_relative_import_inside_a_plugin_package_still_works(self):
        import tempfile
        import shutil

        directory = tempfile.mkdtemp(prefix="relimport-")
        self.addCleanup(shutil.rmtree, directory, True)
        pkg = Path(directory, "relpkg")
        pkg.mkdir()
        (pkg / "__init__.py").write_text("from . import helper\nVALUE = helper.VALUE\n")
        (pkg / "helper.py").write_text("VALUE = 7\n")

        result = plugins.load_plugins(directory, "relpkg", allow_bridge=False)

        self.assertEqual(1, result["loaded"], result["runs"])


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


class RedactTest(unittest.TestCase):
    def test_a_named_secret_never_survives_into_the_log(self):
        text = "connecting with token sk-live-abcdef and finishing"

        cleaned = plugins.redact(text, secrets=["sk-live-abcdef"])

        self.assertNotIn("sk-live-abcdef", cleaned)
        self.assertIn("connecting with token", cleaned)

    def test_redaction_of_nothing_changes_nothing(self):
        self.assertEqual("plain", plugins.redact("plain", secrets=[]))
        self.assertEqual("plain", plugins.redact("plain", secrets=[""]))


if __name__ == "__main__":
    unittest.main()
