"""Regressions in the device runner's handling of unresponsive SDK processes."""

import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import textwrap
import unittest


class DeviceBootTest(unittest.TestCase):
    def test_stuck_boot_connection_is_reconnected_and_tests_run_once(self):
        # PRs 9 and 31 exhausted the job timeout before tests began. A loop's
        # deadline cannot stop a blocked adb command. Exercise real timeout and
        # signals, including a process that ignores TERM, rather than mocking
        # timeout success or asserting the runner's command spelling. PR 39
        # booted Android but shell probes stayed stuck. Model an unresponsive
        # connection: killing only the probe must not make this fixture recover.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            shutil.copy2(Path(__file__).with_name("check-device"), root / "scripts/check-device")
            for suffix in ("foss/debug/app-foss-debug.apk", "androidTest/foss/debug/app-foss-debug-androidTest.apk"):
                artifact = root / "app/build/outputs/apk" / suffix
                artifact.parent.mkdir(parents=True, exist_ok=True)
                artifact.touch()
            binaries = root / "bin"
            binaries.mkdir()
            adb = binaries / "adb"
            adb.write_text("#!/usr/bin/env python3\n" + textwrap.dedent("""
                import os
                from pathlib import Path
                import signal
                import sys
                import time

                root = Path(os.environ["DEVICE_TEST_ROOT"])
                args = sys.argv[1:]
                if args[:1] == ["-s"]:
                    args = args[2:]
                if args == ["shell", "getprop", "sys.boot_completed"]:
                    marker = root / "blocked-probe"
                    if not (root / "reconnected").exists():
                        marker.write_text(str(os.getpid()))
                        signal.signal(signal.SIGTERM, signal.SIG_IGN)
                        while True:
                            time.sleep(1)
                    print("1")
                elif args == ["reconnect"]:
                    if sys.argv[1:3] != ["-s", "emulator-5556"]:
                        raise SystemExit("Reconnect must target only the test emulator")
                    (root / "reconnected").touch()
                elif args == ["shell", "getprop", "ro.build.version.sdk"]:
                    print("26")
                elif args == ["shell", "am", "wait-for-broadcast-idle"]:
                    print("All broadcast queues are idle!")
                elif args[:3] == ["shell", "am", "instrument"]:
                    with (root / "test-executions").open("a") as output:
                        output.write("run\\n")
                    print("OK (1 test)")
            """))
            adb.chmod(0o755)
            for name, content in (("avdmanager", "exit 0"), ("emulator", "exec sleep 600")):
                executable = binaries / name
                executable.write_text("#!/usr/bin/env bash\n" + content + "\n")
                executable.chmod(0o755)
            env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ["PATH"],
                       DEVICE_TEST_ROOT=str(root), AGENTKNOCK_EMULATOR_API="26",
                       AGENTKNOCK_EMULATOR_IMAGE="system-images;android-26;google_apis;x86_64")
            process = subprocess.Popen(
                ["bash", str(root / "scripts/check-device"), "foss", "instrumentation"],
                env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, start_new_session=True,
            )
            try:
                output, _ = process.communicate(timeout=45)
                self.assertEqual(0, process.returncode, output)
                self.assertEqual("run\n", (root / "test-executions").read_text())
            finally:
                # GNU timeout gives its child a separate process group. Clean
                # up the injected hang too if timeout handling regresses.
                if process.poll() is None:
                    marker = root / "blocked-probe"
                    if marker.exists():
                        try:
                            os.kill(int(marker.read_text()), signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                process.communicate()


if __name__ == "__main__":
    unittest.main()
