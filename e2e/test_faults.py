"""
The proxy when the Hub or its CDN misbehaves. Faults are injected by the relay into real Hub
responses for tiny files; a fault with a status does not reach the Hub at all.
"""

import threading
import time
import urllib.error
import urllib.request

import pytest
from huggingface_hub import hf_hub_download
from huggingface_hub.errors import HfHubHTTPError

from conftest import sha256
from relay import Fault
from test_hub import TINY, TINY_COMMIT, download, http

WEIGHTS = "model.safetensors"  # 454 kB, LFS: served from the CDN
# CDN URLs do not show the file name in their path; each test here fetches only this one file from the CDN
CDN_WEIGHTS = None


@pytest.fixture(scope="module")
def weights_sha256(relay):
    """The real SHA-256 of the test file, from the Hub."""
    from test_hub import hub
    return hub(relay).get_paths_info(TINY, [WEIGHTS], revision=TINY_COMMIT)[0].lfs.sha256


def weights_url(repository):
    return "%s/%s/resolve/%s/%s" % (repository.url, TINY, TINY_COMMIT, WEIGHTS)


def wait_for_stored(repository, path_suffix, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if any(a["path"].endswith(path_suffix) for a in repository.file_assets()):
            return True
        time.sleep(1)
    return False


# --- broken transfers

@pytest.mark.repo(timeout=3)
@pytest.mark.parametrize("fault", [
    Fault(truncate_after=100_000),
    Fault(stall_after=100_000, stall_seconds=8),  # longer than the repository's 3 s timeout
], ids=["connection dropped", "stalled"])
def test_a_failed_transfer_is_resumed_without_the_client_noticing(repository, relay, fault, weights_sha256):
    import hashlib
    relay.inject(fault, cdn=True)
    mark = relay.mark()
    status, _, body = http(weights_url(repository))  # one plain request, no client retries
    assert status == 200 and hashlib.sha256(body).hexdigest() == weights_sha256
    assert len(relay.since(mark, cdn=True)) == 2, "resumed once"
    assert wait_for_stored(repository, "/" + WEIGHTS)
    stored = [a for a in repository.file_assets() if a["path"].endswith("/" + WEIGHTS)]
    assert len(stored) == 1 and stored[0]["checksum"]["sha256"] == weights_sha256


def test_corrupt_content_is_never_served_complete_nor_cached(repository, relay, tmp_path, weights_sha256):
    import hashlib
    from http.client import HTTPException
    relay.inject(Fault(corrupt=True), cdn=True)
    try:
        status, _, body = http(weights_url(repository))
        assert status != 200 or hashlib.sha256(body).hexdigest() == weights_sha256, \
            "a complete but corrupt file was served"
    except (urllib.error.URLError, HTTPException, OSError):
        pass  # the transfer failing is the expected outcome
    time.sleep(2)
    assert not [a for a in repository.file_assets() if a["path"].endswith("/" + WEIGHTS)], "nothing corrupt cached"
    # the next attempt works
    assert sha256(download(repository, tmp_path, TINY, WEIGHTS)) == weights_sha256


# --- streaming

def timed_get(url, results, index, headers=None):
    start = time.monotonic()
    request = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(request, timeout=120) as response:
        first = response.read(1)
        first_byte = time.monotonic() - start
        body = first + response.read()
    results[index] = (first_byte, time.monotonic() - start, body)


def test_slow_upstream_is_streamed_to_concurrent_clients_from_one_download(repository, relay, weights_sha256):
    import hashlib
    relay.inject(Fault(bytes_per_second=100_000), cdn=True, path=CDN_WEIGHTS)  # ~4.5 s for the file
    mark = relay.mark()
    results = [None] * 3
    threads = [threading.Thread(target=timed_get, args=(weights_url(repository), results, i)) for i in range(3)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    for first_byte, total, body in results:
        assert hashlib.sha256(body).hexdigest() == weights_sha256
        assert total > 3, "the throttle applied"
        assert first_byte < 2.5, "bytes flow before the download has finished"
    assert len(relay.since(mark, cdn=True, path=CDN_WEIGHTS)) == 1, "one upstream download for all clients"


def test_client_disconnecting_does_not_abort_caching(repository, relay):
    relay.inject(Fault(bytes_per_second=100_000), cdn=True, path=CDN_WEIGHTS)
    with urllib.request.urlopen(weights_url(repository), timeout=60) as response:
        response.read(20_000)  # and hang up
    assert wait_for_stored(repository, "/" + WEIGHTS), "the download completed and was stored"
    mark = relay.mark()
    assert http(weights_url(repository))[0] == 200
    assert relay.since(mark) == [], "served from the cache"


def test_range_request_into_a_file_still_downloading(repository, relay, weights_sha256):
    import hashlib
    relay.inject(Fault(bytes_per_second=100_000), cdn=True, path=CDN_WEIGHTS)
    results = [None]
    whole = threading.Thread(target=timed_get, args=(weights_url(repository), results, 0))
    whole.start()
    time.sleep(1)
    status, headers, tail = http(weights_url(repository), headers={"Range": "bytes=400000-400099"})
    whole.join()
    body = results[0][2]
    assert hashlib.sha256(body).hexdigest() == weights_sha256
    assert status == 206 and tail == body[400000:400100]


# --- upstream errors

def test_rate_limiting_is_passed_on(repository, relay):
    url = "%s/%s/resolve/main/config.json" % (repository.url, TINY)
    relay.inject(Fault(status=429, headers={"Retry-After": "7"}), method="HEAD", path="/config.json")
    status, headers, _ = http(url, "HEAD")
    assert status == 429 and headers["Retry-After"] == "7"
    assert http(url, "HEAD")[0] == 200, "neither negatively cached nor blocking"


@pytest.mark.parametrize("fault", [Fault(status=503), Fault(drop=True)], ids=["503", "connection dropped"])
def test_upstream_failure_is_a_gateway_error_and_not_remembered(repository, relay, tmp_path, fault):
    url = "%s/%s/resolve/main/config.json" % (repository.url, TINY)
    relay.inject(fault, method="HEAD", path="/config.json", times=10)  # more than Nexus retries
    assert http(url, "HEAD")[0] in (502, 503)
    relay.clear_faults()
    assert http(url, "HEAD")[0] == 200


def test_upstream_that_is_not_a_hub_is_a_gateway_error(repository, relay):
    url = "%s/%s/resolve/main/config.json" % (repository.url, TINY)
    relay.inject(Fault(strip_headers=("X-Repo-Commit",)), method="HEAD", path="/config.json", times=10)
    assert http(url, "HEAD")[0] == 502
    relay.clear_faults()
    assert http(url, "HEAD")[0] == 200, "not negatively cached"


def test_cached_files_are_served_while_the_hub_is_down(repository, relay, tmp_path, weights_sha256):
    path = download(repository, tmp_path / "warm", TINY, WEIGHTS)
    relay.inject(Fault(status=500), times=1000)
    mark = relay.mark()
    by_commit = download(repository, tmp_path / "by-commit", TINY, WEIGHTS, revision=TINY_COMMIT)
    by_branch = download(repository, tmp_path / "by-branch", TINY, WEIGHTS)
    assert sha256(by_commit) == sha256(by_branch) == sha256(path) == weights_sha256
    assert relay.since(mark) == []


def test_deleting_a_repository_during_a_download(repository, relay, nexus, tmp_path):
    relay.inject(Fault(bytes_per_second=50_000), cdn=True, path=CDN_WEIGHTS)
    results = [None]

    def get():
        try:
            timed_get(weights_url(repository), results, 0)
        except (urllib.error.URLError, OSError, ValueError) as e:
            results[0] = e

    downloading = threading.Thread(target=get)
    downloading.start()
    time.sleep(2)
    nexus.delete_repository(repository.name)
    downloading.join(timeout=60)
    assert not downloading.is_alive(), "the client is not left hanging"

    # Nexus is fine: a new repository works
    other = "e2e-after-delete-%d" % int(time.time())
    nexus.create_proxy(other, relay.base_url)
    try:
        status, _, _ = http("%s/repository/%s/%s/resolve/main/config.json" % (nexus.url, other, TINY))
        assert status == 200
    finally:
        nexus.delete_repository(other)
