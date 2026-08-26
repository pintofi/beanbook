"""A plugin that raises on import. Beancount reports it and carries on."""
raise RuntimeError("this plugin was never going to work")

__plugins__ = []
