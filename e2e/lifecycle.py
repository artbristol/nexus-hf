"""
Runs the e2e suite against a Nexus of its own, for Maven's integration-test phases (`mvn verify`):

  start   (pre-integration-test)   Nexus with the plugin, on a free port with fresh data; the venv
  test    (integration-test)       the suite; its result is kept for `verify`, so that `stop` runs
  stop    (post-integration-test)  Nexus
  verify  (verify)                 fails if the suite failed

Maven assembles Nexus from Maven Central: the nexus-base-overlay zip (bin/, etc/, deploy/) and the
nexus-repository-core jar, which is what a Nexus Repository Core installation consists of. Paths
are passed in by Maven; state is kept in a JSON file next to the installation.
"""

import argparse
import json
import os
import shutil
import signal
import socket
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request

E2E = os.path.dirname(os.path.abspath(__file__))
PASSWORD = "admin123"  # with nexus.security.randompassword=false; Nexus listens on 127.0.0.1 only
START_TIMEOUT = 600


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("step", choices=["start", "test", "stop", "verify"])
    parser.add_argument("--home", required=True, help="the Nexus installation (bin/, etc/, deploy/)")
    parser.add_argument("--plugin", help="the plugin jar, for start")
    parser.add_argument("--python", default=sys.executable, help="Python for the venv, for start")
    args = parser.parse_args()
    state_file = os.path.join(os.path.dirname(os.path.abspath(args.home)), "e2e-state.json")
    globals()[args.step](args, state_file)


def start(args, state_file):
    home = os.path.abspath(args.home)
    work = os.path.join(os.path.dirname(home), "sonatype-work")  # where bin/nexus puts it
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(os.path.join(work, "nexus3", "etc"))
    port = free_port()
    with open(os.path.join(work, "nexus3", "etc", "nexus.properties"), "w") as f:
        f.write("application-host=127.0.0.1\napplication-port=%d\n"
                "nexus.security.randompassword=false\nnexus.skipDefaultRepositories=true\n" % port)
    for name in os.listdir(os.path.join(home, "deploy")):
        if name.endswith(".jar"):
            os.remove(os.path.join(home, "deploy", name))
    shutil.copy(args.plugin, os.path.join(home, "deploy"))
    launcher = os.path.join(home, "bin", "nexus")
    os.chmod(launcher, os.stat(launcher).st_mode | stat.S_IXUSR)  # zips lose it

    log = open(os.path.join(os.path.dirname(home), "nexus.out"), "w")
    env = dict(os.environ, INSTALL4J_ADD_VM_PARAMS="-Dloader.path=deploy")
    nexus = subprocess.Popen([launcher, "run"], cwd=home, env=env, stdout=log, stderr=subprocess.STDOUT,
                             start_new_session=True)
    url = "http://127.0.0.1:%d" % port
    save(state_file, {"pid": nexus.pid, "url": url})
    print("Starting Nexus at %s (log: %s)" % (url, log.name), flush=True)

    # Maven skips the later phases if this one fails, `stop` included
    try:
        venv = os.path.join(os.path.dirname(home), "venv")
        if not os.path.exists(venv):
            subprocess.run([args.python, "-m", "venv", venv], check=True)
        subprocess.run([os.path.join(venv, "bin", "python"), "-m", "pip", "install", "-q", "--require-hashes",
                        "-r", os.path.join(E2E, "requirements.txt")], check=True)

        deadline = time.monotonic() + START_TIMEOUT
        while not writable(url):
            if nexus.poll() is not None:
                raise RuntimeError("Nexus exited with %d, see %s" % (nexus.returncode, log.name))
            if time.monotonic() > deadline:
                raise RuntimeError("Nexus did not start within %d s, see %s" % (START_TIMEOUT, log.name))
            time.sleep(2)
    except BaseException:
        stop(args, state_file)
        raise
    print("Nexus is up", flush=True)


def test(args, state_file):
    state = load(state_file)
    if "url" not in state:
        sys.exit("Nexus was not started")
    venv_python = os.path.join(os.path.dirname(os.path.abspath(args.home)), "venv", "bin", "python")
    env = dict(os.environ, NEXUS_URL=state["url"], NEXUS_USER="admin", NEXUS_PASSWORD=PASSWORD)
    result = subprocess.run([venv_python, "-m", "pytest", "-ra"], cwd=E2E, env=env)
    state["exit"] = result.returncode
    save(state_file, state)


def stop(args, state_file):
    state = load(state_file)
    if "pid" not in state:
        return
    try:
        os.killpg(state["pid"], signal.SIGTERM)
    except ProcessLookupError:
        return
    for _ in range(60):
        try:
            os.killpg(state["pid"], 0)
        except ProcessLookupError:
            return
        time.sleep(1)
    os.killpg(state["pid"], signal.SIGKILL)


def verify(args, state_file):
    code = load(state_file).get("exit")
    if code != 0:
        sys.exit("The e2e suite failed (pytest exit code %s)" % code)


def writable(url):
    try:
        with urllib.request.urlopen(url + "/service/rest/v1/status/writable", timeout=5) as response:
            return response.status == 200
    except (urllib.error.URLError, OSError):
        return False


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def save(path, state):
    with open(path, "w") as f:
        json.dump(state, f)


def load(path):
    try:
        with open(path) as f:
            return json.load(f)
    except FileNotFoundError:
        return {}


if __name__ == "__main__":
    main()
