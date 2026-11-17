import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parent.parent
module = root / "vendor/miuix"
patch = root / "tools/miuix-build-directory.patch"
if not (module / "build-logic/src/main/kotlin/BuildConfig.kt").is_file():
    raise SystemExit("Initialize miuix first: git submodule update --init --recursive")
command = ["git", "-C", str(module), "apply"]
already_applied = subprocess.run(command + ["--reverse", "--check", str(patch)], capture_output=True)
if already_applied.returncode != 0:
    subprocess.run(command + ["--check", str(patch)], check=True)
    subprocess.run(command + [str(patch)], check=True)
