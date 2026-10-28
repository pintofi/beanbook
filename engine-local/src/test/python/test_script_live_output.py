"""A script prints live and has no fixed deadline."""

import os
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))

import plugins  # noqa: E402


class LiveScriptTest(unittest.TestCase):
    def _script(self, body):
        directory = tempfile.mkdtemp()
        path = Path(directory) / "s.py"
        path.write_text(body)
        return str(path)

    def test_no_deadline_when_timeout_is_zero(self):
        path = self._script("import time\nfor i in range(3):\n    print('tick', i)\n    time.sleep(0.4)\n")
        result = plugins.run_script(path, timeout_seconds=0, handle="t1")
        self.assertTrue(result["ok"], result)
        self.assertFalse(result["timed_out"])
        self.assertIn("tick 2", result["output"])

    def test_a_positive_timeout_still_stops_a_loop(self):
        path = self._script("while True:\n    pass\n")
        result = plugins.run_script(path, timeout_seconds=0.5, handle="t2")
        self.assertTrue(result["timed_out"], result)

    def test_output_reaches_the_live_file_while_the_script_runs(self):
        live = os.path.join(tempfile.gettempdir(), "beanbook-script-t3.live")
        seen = []
        path = self._script(
            "import time\nprint('first line', flush=True)\ntime.sleep(1.2)\nprint('second')\n"
        )

        import threading

        def watch():
            for _ in range(20):
                time.sleep(0.2)
                if os.path.exists(live):
                    text = Path(live).read_text()
                    if "first line" in text and "second" not in text:
                        seen.append(text)
                        return

        thread = threading.Thread(target=watch)
        thread.start()
        result = plugins.run_script(path, timeout_seconds=0, handle="t3")
        thread.join()

        self.assertTrue(seen, "the first line was not readable before the script ended")
        self.assertIn("second", result["output"])
        self.assertFalse(os.path.exists(live), "the live file is removed when the script ends")


    def test_the_live_file_goes_where_the_app_says_not_into_tempfiles_own_folder(self):
        # On Android tempfile's folder is inside the app's cache folder, not the cache folder.
        cache = tempfile.mkdtemp()
        inner = os.path.join(cache, "chaquopy", "tmp")
        os.makedirs(inner)
        path = self._script("import time\nprint('hello', flush=True)\ntime.sleep(0.8)\n")
        saved = tempfile.tempdir
        tempfile.tempdir = inner
        try:
            found = []

            def watch():
                live = os.path.join(cache, "beanbook-script-t4.live")
                for _ in range(30):
                    if os.path.isfile(live) and "hello" in open(live).read():
                        found.append(True)
                        return
                    time.sleep(0.05)

            watcher = threading.Thread(target=watch)
            watcher.start()
            plugins.run_script(path, timeout_seconds=0, handle="t4", live_dir=cache)
            watcher.join()
            self.assertTrue(found, "the folder the app named never held the script's output")
            self.assertEqual([], os.listdir(inner), "nothing should have been written to tempfile's folder")
        finally:
            tempfile.tempdir = saved


if __name__ == "__main__":
    unittest.main()
