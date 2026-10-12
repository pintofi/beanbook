"""What Fava reported about its extensions when it loaded the ledger (B101).

Filled in by `engine.start` once `create_app` has returned, read by `ledger.inspect`. Module
state rather than an argument because the two run in the same engine process on different
calls, and importing the extensions a second time to find out is exactly what crashed it.
"""

ready: bool = False
# True from the start of `engine.start` until Fava has loaded: `ledger.inspect` waits for it
# rather than answering "not known yet" for a ledger that names extensions.
starting: bool = False
errors: dict = {}
