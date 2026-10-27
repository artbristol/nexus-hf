"""
Fixtures for the end-to-end tests. They need a running Nexus Repository Core with the plugin
installed, and access to huggingface.co. Configuration, from the environment:

  NEXUS_URL        default http://localhost:8081
  NEXUS_USER       default admin
  NEXUS_PASSWORD   required (creates and deletes repositories, toggles anonymous access)
  E2E_RELAY_HOST   address Nexus reaches the relay at (default 127.0.0.1: Nexus on this machine)
  E2E_RELAY_CDN_HOST  another name for the same address, standing in for the CDN (default localhost)

See DEVELOPMENT.md.
"""

import base64
import hashlib
import os
import uuid

import pytest

# before huggingface_hub is imported anywhere: no telemetry, no progress bars, no XET opt-out (the proxy must cope)
os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
os.environ["HF_HUB_DISABLE_PROGRESS_BARS"] = "1"
os.environ.pop("HF_HUB_DISABLE_XET", None)
os.environ.pop("HF_HUB_OFFLINE", None)

from nexus import Nexus
from relay import Relay


@pytest.fixture(scope="session")
def nexus():
    password = os.environ.get("NEXUS_PASSWORD")
    if not password:
        pytest.exit("Set NEXUS_PASSWORD (and NEXUS_URL, NEXUS_USER if not the defaults)", returncode=2)
    return Nexus(os.environ.get("NEXUS_URL", "http://localhost:8081"), os.environ.get("NEXUS_USER", "admin"),
                 password)


@pytest.fixture(scope="session")
def relay():
    relay = Relay(host=os.environ.get("E2E_RELAY_HOST", "127.0.0.1"), cdn_host=os.environ.get("E2E_RELAY_CDN_HOST"))
    yield relay
    relay.close()
    print("\nrelay: %d requests to the Hub, %.1f MB" % (len(relay.requests), relay.bytes / 1e6))


@pytest.fixture(scope="session")
def relay_allowed(nexus, relay):
    """Nexus' SSRF protection refuses private addresses such as the relay's; allow just that one, for the run."""
    original = nexus.call("GET", "/service/rest/v1/security/ssrf-protection")
    host = relay.base_url.split("//")[1].split(":")[0]
    if original["enabled"] and host not in original["allowedIPs"]:
        nexus.call("PUT", "/service/rest/v1/security/ssrf-protection",
                   dict(original, allowedIPs=original["allowedIPs"] + [host]))
    yield
    nexus.call("PUT", "/service/rest/v1/security/ssrf-protection", original)


@pytest.fixture(autouse=True)
def stop_on_relay_failure(relay):
    """A rate limit or an exhausted budget ends the whole run, not just one test."""
    yield
    relay.clear_faults()
    if relay.failure:
        pytest.exit(relay.failure, returncode=3)


@pytest.fixture(scope="session")
def anonymous_access(nexus):
    """The tests download anonymously, as most users of a public proxy would."""
    was_enabled = nexus.is_anonymous_access_enabled()
    nexus.anonymous_access(True)
    yield
    nexus.anonymous_access(was_enabled)


class Repository:
    def __init__(self, nexus, name):
        self.nexus = nexus
        self.name = name
        self.url = "%s/repository/%s" % (nexus.url, name)

    def assets(self):
        return self.nexus.assets(self.name)

    def file_assets(self):
        return [a for a in self.assets() if not a["path"].startswith(("/.meta/", "/api/"))]


@pytest.fixture
def repository(nexus, relay, relay_allowed, anonymous_access, request):
    """A fresh Hugging Face proxy repository, empty, behind the relay. Options via the `repo` marker."""
    marker = request.node.get_closest_marker("repo")
    options = dict(marker.kwargs) if marker else {}
    name = "e2e-%s" % uuid.uuid4().hex[:8]
    nexus.create_proxy(name, relay.base_url, **options)
    yield Repository(nexus, name)
    try:
        nexus.delete_repository(name)
    except AssertionError as e:
        if " 404 " not in str(e):  # deleted by the test
            raise


XET_REALM = "HuggingFaceXetToken"


@pytest.fixture(scope="module")
def xet_realm(nexus):
    original = nexus.active_realms()
    if XET_REALM not in original:
        nexus.set_active_realms(original + [XET_REALM])
    yield
    nexus.set_active_realms(original)


class Reader:
    """A user who may only read the repository, and the endpoint with their credentials in it."""

    def __init__(self, repository, user, password):
        self.user = user
        self.password = password
        scheme, rest = repository.url.split("://", 1)
        self.endpoint = "%s://%s:%s@%s" % (scheme, user, password, rest)
        self.basic = "Basic " + base64.b64encode(("%s:%s" % (user, password)).encode()).decode()


@pytest.fixture
def reader(nexus, repository, xet_realm):
    user = "e2e-reader-" + uuid.uuid4().hex[:8]
    password = uuid.uuid4().hex
    nexus.create_reader(user, password, repository.name)
    yield Reader(repository, user, password)
    nexus.delete_reader(user)


def pytest_configure(config):
    config.addinivalue_line("markers", "repo(**options): options of the test repository (see Nexus.create_proxy)")


# helpers shared by the test modules

def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git_blob_id(path):
    with open(path, "rb") as f:
        data = f.read()
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def verify_against_hub(api, repo_id, repo_type, folder, commit):
    """Checks every file of the snapshot in `folder` against the Hub's metadata for `commit`."""
    info = api.repo_info(repo_id, repo_type=repo_type, revision=commit, files_metadata=True)
    for sibling in info.siblings:
        path = os.path.join(folder, sibling.rfilename)
        assert os.path.isfile(path), "missing " + sibling.rfilename
        if sibling.lfs is not None:
            assert sha256(path) == sibling.lfs.sha256, sibling.rfilename
        else:
            assert git_blob_id(path) == sibling.blob_id, sibling.rfilename
    return len(info.siblings)
