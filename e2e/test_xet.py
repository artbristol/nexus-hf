"""
The XET protocol through the proxy: clients with hf_xet fetch XET files from the repository's own
XET storage endpoints, which fetch from the Hub's XET storage and cache what they fetched.

XET needs the "Hugging Face XET Token" realm, a repository with XET enabled, and an authenticated
user; the tests use a user who may only read the test repository, with credentials in the endpoint
URL. All files are from TINY: one xorb range of under 1 MB per file.
"""

import base64
import json
import os
import urllib.error
import urllib.parse
import urllib.request

import pytest
from huggingface_hub import HfApi, get_hf_file_metadata, hf_hub_download, hf_hub_url, snapshot_download

from conftest import XET_REALM, sha256
from relay import Fault, Rule

TINY = "hf-internal-testing/tiny-random-gpt2"
TINY_COMMIT = "71034c5d8bde858ff824298bdedc65515b97d2b9"
XET_FILES = ["model.safetensors", "pytorch_model.bin"]  # both XET files, in one xorb
def hub_sha256(relay, filename):
    info = HfApi(endpoint=relay.base_url.rstrip("/"), token=False).get_paths_info(
        TINY, [filename], revision=TINY_COMMIT)[0]
    return info.lfs.sha256


def xet_offered(endpoint, filename="model.safetensors"):
    return get_hf_file_metadata(hf_hub_url(TINY, filename, endpoint=endpoint), token=False).xet_file_data is not None


def http(url, headers=None):
    request = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


def forged(token):
    """The token with another signature."""
    return token.rsplit(".", 1)[0] + "." + base64.urlsafe_b64encode(b"x" * 32).decode().rstrip("=")


def xet_storage_requests(requests):
    return [r for r in requests if r.cdn and (r.path.startswith("/v1/") or r.path.startswith("/v2/")
                                               or r.path.startswith("/xorbs/"))]


@pytest.mark.repo(xet=True)
def test_xet_download_matches_the_hub_and_is_then_served_from_cache(repository, relay, reader, tmp_path):
    mark = relay.mark()
    folder = snapshot_download(TINY, revision=TINY_COMMIT, allow_patterns=XET_FILES, endpoint=reader.endpoint,
                               cache_dir=tmp_path / "first", token=False)
    for name in XET_FILES:
        assert sha256(os.path.join(folder, name)) == hub_sha256(relay, name), name

    requests = relay.since(mark)
    assert [r for r in requests if "/xet-read-token/" in r.path], "the proxy needs a Hub token for XET storage"
    assert [r for r in xet_storage_requests(requests) if "/reconstructions/" in r.path]
    assert [r for r in xet_storage_requests(requests) if r.path.startswith("/xorbs/")]
    cdn = [r for r in requests if r.cdn]
    assert cdn == xet_storage_requests(cdn), "XET files are not downloaded over plain HTTP"
    reconstructions = [r for r in xet_storage_requests(requests) if "/reconstructions/" in r.path]
    assert len(reconstructions) == len(XET_FILES), "one reconstruction per file from XET storage"
    paths = [a["path"] for a in repository.assets()]
    assert [p for p in paths if p.startswith("/.xet/xorbs/")]
    assert [p for p in paths if p.startswith("/.xet/reconstructions/")]

    mark = relay.mark()
    again = snapshot_download(TINY, revision=TINY_COMMIT, allow_patterns=XET_FILES, endpoint=reader.endpoint,
                              cache_dir=tmp_path / "second", token=False)
    assert relay.since(mark) == [], "a cached XET download must not need the Hub"
    for name in XET_FILES:
        assert sha256(os.path.join(again, name)) == sha256(os.path.join(folder, name))


@pytest.mark.repo(xet=True)
def test_xet_works_without_anonymous_access(nexus, repository, relay, reader, tmp_path):
    """Clients authenticate their XET storage requests with the repository's tokens, not with Basic auth."""
    nexus.anonymous_access(False)
    try:
        mark = relay.mark()
        path = hf_hub_download(TINY, "model.safetensors", revision=TINY_COMMIT, endpoint=reader.endpoint,
                               cache_dir=tmp_path, token=False)
        assert sha256(path) == hub_sha256(relay, "model.safetensors")
        assert [r for r in relay.since(mark) if r.cdn and r.path.startswith("/xorbs/")]
    finally:
        nexus.anonymous_access(True)


@pytest.mark.repo(xet=True)
def test_xet_is_offered_to_users_only(repository, reader):
    assert xet_offered(reader.endpoint)
    assert not xet_offered(repository.url), "anonymous clients cannot authenticate XET requests"
    listing = "/api/models/%s/tree/%s" % (TINY, TINY_COMMIT)
    assert b"xetHash" in http(repository.url + listing, {"Authorization": reader.basic})[2]
    assert b"xetHash" not in http(repository.url + listing)[2]


@pytest.mark.repo(xet=True)
def test_xet_needs_the_realm(nexus, repository, reader):
    realms = nexus.active_realms()
    nexus.set_active_realms([r for r in realms if r != XET_REALM])
    try:
        assert not xet_offered(reader.endpoint)
    finally:
        nexus.set_active_realms(realms)
    assert xet_offered(reader.endpoint)


def test_xet_is_off_unless_enabled(nexus, repository, reader):
    assert nexus.proxy(repository.name)["xet"] == {"enabled": False}
    assert not xet_offered(reader.endpoint)


@pytest.mark.repo(xet=True)
def test_xet_storage_needs_the_repositorys_tokens(nexus, repository, reader):
    assert nexus.proxy(repository.name)["xet"] == {"enabled": True}
    status, headers, _ = http(repository.url + "/api/models/%s/xet-read-token/%s" % (TINY, TINY_COMMIT),
                              {"Authorization": reader.basic})
    assert status == 200
    cas, token = headers["X-Xet-Cas-Url"], headers["X-Xet-Access-Token"]
    assert cas == repository.url + "/.xet"
    file_hash = get_hf_file_metadata(hf_hub_url(TINY, "model.safetensors", endpoint=reader.endpoint),
                                     token=False).xet_file_data.file_hash
    reconstruction = cas + "/v2/reconstructions/" + file_hash

    assert http(reconstruction)[0] == 401
    assert http(reconstruction, {"Authorization": "Bearer " + forged(token)})[0] == 401
    status, _, body = http(reconstruction, {"Authorization": "Bearer " + token})
    assert status == 200
    plan = json.loads(body)
    url = next(iter(plan["xorbs"].values()))[0]["url"]
    assert url.startswith(repository.url + "/.xet/xorbs/")

    unsigned, query = url.split("?", 1)
    url_token = urllib.parse.parse_qs(query)["token"][0]
    assert http(url)[0] == 200
    assert http(unsigned)[0] == 403
    assert http(unsigned + "?token=" + forged(url_token))[0] == 403

    # beyond the end of the file: how the client learns it has it all
    size = sum(t["unpacked_length"] for t in plan["terms"])
    assert http(reconstruction, {"Authorization": "Bearer " + token, "Range": "bytes=%d-%d" % (size, size + 10)})[0] \
        == 416


@pytest.mark.repo(xet=True)
def test_dropped_xorb_transfer_is_retried(repository, relay, reader, tmp_path):
    rule = relay.inject(Fault(truncate_after=1000), cdn=True, path=r"^/xorbs/")
    path = hf_hub_download(TINY, "model.safetensors", revision=TINY_COMMIT, endpoint=reader.endpoint,
                           cache_dir=tmp_path, token=False)
    assert sha256(path) == hub_sha256(relay, "model.safetensors")
    assert rule.times == 0, "the fault was injected"


@pytest.mark.repo(xet=True)
def test_refused_presigned_url_is_replaced(repository, relay, reader, tmp_path):
    """An expired presigned URL keeps being refused; the client must get a new one, from a new reconstruction."""
    refused_at = []

    def until_reconstructed_again(request):
        if not (request.cdn and request.path.startswith("/xorbs/")):
            return False
        if not refused_at:
            refused_at.append(len(relay.requests))
            return True
        return not [r for r in relay.requests[refused_at[0]:] if r.cdn and "/reconstructions/" in r.path]

    rule = relay.add_rule(Rule(until_reconstructed_again, Fault(status=403), times=20))
    path = hf_hub_download(TINY, "model.safetensors", revision=TINY_COMMIT, endpoint=reader.endpoint,
                           cache_dir=tmp_path, token=False)
    assert sha256(path) == hub_sha256(relay, "model.safetensors")
    assert refused_at, "the fault was injected"
    assert rule.times > 0, "the refused URL was replaced, not retried until the fault ran out"


@pytest.mark.repo(xet=True)
def test_xorb_url_tokens_are_valid_for_their_range_only(repository, reader):
    """The tokens in xorb URLs end up in request logs; they must not open anything but that range."""
    status, headers, _ = http(repository.url + "/api/models/%s/xet-read-token/%s" % (TINY, TINY_COMMIT),
                              {"Authorization": reader.basic})
    reconstruction = headers["X-Xet-Cas-Url"] + "/v2/reconstructions/" + get_hf_file_metadata(
        hf_hub_url(TINY, "model.safetensors", endpoint=reader.endpoint), token=False).xet_file_data.file_hash
    plan = json.loads(http(reconstruction, {"Authorization": "Bearer " + headers["X-Xet-Access-Token"]})[2])
    url = next(iter(plan["xorbs"].values()))[0]["url"]
    path, query = url.split("?", 1)
    url_token = urllib.parse.parse_qs(query)["token"][0]

    assert http(url)[0] == 200
    assert http(reconstruction, {"Authorization": "Bearer " + url_token})[0] == 401
    xorb, _ = path.rsplit("/", 1)
    assert http(xorb + "/0-99?token=" + url_token)[0] == 403


@pytest.mark.repo(xet=True)
def test_blocked_remote_fetches_nothing_from_xet_storage(nexus, repository, relay, reader, tmp_path):
    cached = hf_hub_download(TINY, "model.safetensors", revision=TINY_COMMIT, endpoint=reader.endpoint,
                             cache_dir=tmp_path / "before", token=False)
    # the metadata of another XET file is cached too, so that only its XET data is missing
    get_hf_file_metadata(hf_hub_url(TINY, "pytorch_model.bin", revision=TINY_COMMIT, endpoint=reader.endpoint),
                         token=False)
    nexus.update_proxy(repository.name, relay.base_url, blocked=True, xet=True)
    mark = relay.mark()
    # Nexus blocks the first host it contacts after the change, normally the Hub (so here too)
    with pytest.raises(Exception):
        get_hf_file_metadata(hf_hub_url(TINY, "vocab.json", revision=TINY_COMMIT, endpoint=reader.endpoint),
                             token=False)
    again = hf_hub_download(TINY, "model.safetensors", revision=TINY_COMMIT, endpoint=reader.endpoint,
                            cache_dir=tmp_path / "after", token=False)
    assert sha256(again) == sha256(cached), "cached XET data is still served"
    # asked for directly: the client would retry for minutes
    _, headers, _ = http(repository.url + "/api/models/%s/xet-read-token/%s" % (TINY, TINY_COMMIT),
                         {"Authorization": reader.basic})
    file_hash = get_hf_file_metadata(hf_hub_url(TINY, "pytorch_model.bin", revision=TINY_COMMIT,
                                                endpoint=reader.endpoint), token=False).xet_file_data.file_hash
    assert http(headers["X-Xet-Cas-Url"] + "/v2/reconstructions/" + file_hash,
                {"Authorization": "Bearer " + headers["X-Xet-Access-Token"]})[0] == 503
    assert relay.since(mark) == [], "a blocked remote is not contacted, XET storage included"
