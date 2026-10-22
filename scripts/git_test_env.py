"""Keep disposable Git fixtures independent of a caller's hook/worktree environment."""
from contextlib import contextmanager
import os


def clean_git_environment(environment=None):
    """Remove every Git override, including all `rev-parse --local-env-vars` names.

    A prefix-wide removal also covers indexed GIT_CONFIG_KEY_n/VALUE_n entries,
    future Git additions, object stores, config-file redirects and hook context.
    Test repositories deliberately do not inherit user/system Git configuration.
    """
    source = os.environ if environment is None else environment
    return {**{name: value for name, value in source.items() if not name.upper().startswith('GIT_')},
            'GIT_CONFIG_GLOBAL': os.devnull, 'GIT_CONFIG_NOSYSTEM': '1'}


@contextmanager
def isolated_git_environment():
    """Isolate indirect production Git calls too, restoring the caller even on error.

    Use only in sequential test fixtures, before creating or entering their repo.
    Production Git helpers must retain the checkout context supplied by Git hooks.
    """
    original = os.environ.copy()
    try:
        os.environ.clear()
        os.environ.update(clean_git_environment(original))
        yield
    finally:
        os.environ.clear()
        os.environ.update(original)


def isolate_git_for_test(test_case):
    """Register restoration without requiring TestCase.enterContext (Python 3.11)."""
    context = isolated_git_environment()
    context.__enter__()
    test_case.addCleanup(context.__exit__, None, None, None)
