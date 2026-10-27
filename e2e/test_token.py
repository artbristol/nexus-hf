"""
The repository's Hub token: sent to the Hub, never to the CDN or XET storage, and good for gated
repositories.

Where the token goes is checked with a marker token that the relay removes before forwarding (the
Hub would reject it). Access to a gated repository needs a real token that can read
meta-llama/Llama-3.2-1B, in E2E_HF_TOKEN; without one, that test is skipped.
"""

import json
import os
import urllib.error
import urllib.request

import pytest
from huggingface_hub import get_hf_file_metadata, hf_hub_download, hf_hub_url, snapshot_download

from relay import MARKER_TOKEN

TINY = "hf-internal-testing/tiny-random-gpt2"
TINY_COMMIT = "71034c5d8bde858ff824298bdedc65515b97d2b9"
GATED = "meta-llama/Llama-3.2-1B"
GATED_FILES = ["config.json", "tokenizer.json"]  # the small ones; the weights are 2.4 GB
REAL_TOKEN = os.environ.get("E2E_HF_TOKEN")


def hub_requests(requests):
    return [r for r in requests if not r.cdn]


@pytest.mark.repo(bearer_token=MARKER_TOKEN)
def test_hub_token_goes_to_the_hub_only(repository, relay, tmp_path):
    mark = relay.mark()
    # model.safetensors is redirected to the CDN, config.json within the Hub; an API listing too
    snapshot_download(TINY, revision=TINY_COMMIT, allow_patterns=["config.json", "model.safetensors"],
                      endpoint=repository.url, cache_dir=tmp_path, token=False)
    requests = relay.since(mark)
    assert [r for r in requests if r.cdn], "something was downloaded from the CDN"
    assert all(r.auth for r in hub_requests(requests)), [r.path for r in hub_requests(requests) if not r.auth]
    assert not [r for r in requests if r.cdn and r.auth], "the Hub token must not leave the Hub"


@pytest.mark.skipif(not REAL_TOKEN, reason="needs E2E_HF_TOKEN with access to " + GATED)
@pytest.mark.repo(bearer_token=REAL_TOKEN or "", xet=True)
def test_gated_repository_with_a_token(repository, relay, reader, tmp_path):
    for name in GATED_FILES:
        path = hf_hub_download(GATED, name, endpoint=repository.url, cache_dir=tmp_path / "anonymous", token=False)
        assert os.path.getsize(path) == get_hf_file_metadata(
            hf_hub_url(GATED, name, endpoint=repository.url), token=False).size

    # XET: the proxy gets a Hub XET token and a reconstruction for the gated weights (without downloading them)
    metadata = get_hf_file_metadata(hf_hub_url(GATED, "model.safetensors", endpoint=reader.endpoint), token=False)
    assert metadata.xet_file_data is not None
    status, headers, _ = http(repository.url + "/api/models/%s/xet-read-token/%s" % (GATED, metadata.commit_hash),
                              {"Authorization": reader.basic})
    assert status == 200
    status, _, body = http(headers["X-Xet-Cas-Url"] + "/v2/reconstructions/" + metadata.xet_file_data.file_hash,
                           {"Authorization": "Bearer " + headers["X-Xet-Access-Token"], "Range": "bytes=0-999"})
    assert status == 200 and json.loads(body)["terms"]


def http(url, headers):
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()
