"""
The proxy against the real Hub, without faults: whatever a client gets through Nexus must match
what the Hub says, and what Nexus has cached must not need the Hub again.

All repositories used are tiny; the whole module downloads a few MB.
"""

import os
import urllib.error
import urllib.request

import pytest
from huggingface_hub import HfApi, HfFileSystem, get_hf_file_metadata, hf_hub_download, hf_hub_url, snapshot_download
from huggingface_hub.errors import (GatedRepoError, HfHubHTTPError, RemoteEntryNotFoundError, RepositoryNotFoundError,
                                    RevisionNotFoundError)

from conftest import git_blob_id, sha256, verify_against_hub

TINY = "hf-internal-testing/tiny-random-gpt2"  # 10 files, 13 MB
TINY_COMMIT = "71034c5d8bde858ff824298bdedc65515b97d2b9"


def hub(relay):
    """The Hub itself (through the relay), as the reference."""
    return HfApi(endpoint=relay.base_url.rstrip("/"), token=False)


def proxied(repository):
    return HfApi(endpoint=repository.url, token=False)


def download(repository, tmp_path, repo_id, filename, **kwargs):
    return hf_hub_download(repo_id, filename, endpoint=repository.url, cache_dir=tmp_path / "cache", token=False,
                           **kwargs)


def http(url, method="GET", headers=None):
    request = urllib.request.Request(url, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


def test_snapshot_matches_the_hub_and_is_then_served_from_cache(repository, relay, tmp_path):
    folder = snapshot_download(TINY, endpoint=repository.url, cache_dir=tmp_path / "first", token=False)
    assert os.path.basename(folder) == TINY_COMMIT
    files = verify_against_hub(hub(relay), TINY, "model", folder, TINY_COMMIT)
    assert len(repository.file_assets()) == files

    mark = relay.mark()
    again = snapshot_download(TINY, endpoint=repository.url, cache_dir=tmp_path / "second", token=False)
    assert relay.since(mark) == [], "a cached snapshot must not need the Hub"
    for name in os.listdir(folder):
        if os.path.isfile(os.path.join(folder, name)):
            assert sha256(os.path.join(again, name)) == sha256(os.path.join(folder, name))


def test_no_request_ever_uses_xet(repository, relay, tmp_path):
    """The client has hf_xet installed; it must still download everything through the proxy."""
    mark = relay.mark()
    snapshot_download(TINY, endpoint=repository.url, cache_dir=tmp_path / "cache", token=False)
    listing = http(repository.url + "/api/models/%s/tree/main?recursive=true" % TINY)[2]
    assert b"xetHash" not in listing
    assert not [r for r in relay.since(mark) if "xet-read-token" in r.path or "cas-server" in r.path], \
        "no XET protocol requests"


@pytest.mark.parametrize("repo_id, repo_type, description", [
    ("hf-internal-testing/test-dataset", "dataset", "contains an empty file"),
    ("hf-internal-testing/multi_dir_dataset", "dataset", "files in nested directories"),
    ("julien-c/titanic-survival", "dataset", "LFS and non-LFS files"),
])
def test_dataset_snapshots_match_the_hub(repository, relay, tmp_path, repo_id, repo_type, description):
    folder = snapshot_download(repo_id, repo_type=repo_type, endpoint=repository.url, cache_dir=tmp_path,
                               token=False)
    verify_against_hub(hub(relay), repo_id, repo_type, folder, os.path.basename(folder))


def test_space_file(repository, relay, tmp_path):
    path = download(repository, tmp_path, "gradio/hello_world", "app.py", repo_type="space")
    reference = hub(relay).get_paths_info("gradio/hello_world", ["app.py"], repo_type="space")[0]
    assert os.path.getsize(path) == reference.size


def test_file_name_with_unusual_characters(repository, relay, tmp_path):
    name = "examples_Rebecca_(1939_poster)_Small.jpeg"
    path = download(repository, tmp_path, "hf-internal-testing/fixtures-captioning", name, repo_type="dataset")
    reference = hub(relay).get_paths_info("hf-internal-testing/fixtures-captioning", [name], repo_type="dataset")[0]
    assert sha256(path) == reference.lfs.sha256 if reference.lfs else os.path.getsize(path) == reference.size


@pytest.mark.parametrize("repo_id, revision", [
    ("openai-community/gpt2", "refs/pr/1"),  # a revision containing slashes
    ("gpt2", "main"),  # legacy repository id without an owner
    ("bert-base-uncased", "main"),  # renamed (now google-bert/bert-base-uncased)
    (TINY, TINY_COMMIT[:7]),  # short commit hash
])
def test_revisions_and_repository_ids_resolve_like_on_the_hub(repository, relay, tmp_path, repo_id, revision):
    expected = get_hf_file_metadata(hf_hub_url(repo_id, "config.json", revision=revision,
                                               endpoint=relay.base_url.rstrip("/")), token=False)
    actual = get_hf_file_metadata(hf_hub_url(repo_id, "config.json", revision=revision, endpoint=repository.url),
                                  token=False)
    assert (actual.commit_hash, actual.etag, actual.size) == (expected.commit_hash, expected.etag, expected.size)
    path = download(repository, tmp_path, repo_id, "config.json", revision=revision)
    assert os.path.getsize(path) == expected.size


@pytest.mark.parametrize("filename", ["config.json", "model.safetensors"])
def test_file_metadata_matches_the_hub_without_downloading(repository, relay, filename):
    expected = get_hf_file_metadata(hf_hub_url(TINY, filename, endpoint=relay.base_url.rstrip("/")), token=False)
    mark = relay.mark()
    actual = get_hf_file_metadata(hf_hub_url(TINY, filename, endpoint=repository.url), token=False)
    assert (actual.commit_hash, actual.etag, actual.size) == (expected.commit_hash, expected.etag, expected.size)
    assert actual.xet_file_data is None, "XET must not be offered"
    assert relay.since(mark, cdn=True) == [], "a HEAD must not download the file"


# What the client raises against huggingface.co. (Against a plain-http endpoint such as the relay it cannot tell a
# missing repository from other 401s; the proxy tells it.)
ERROR_CASES = {
    "missing repository": ("hf-internal-testing/this-repo-does-not-exist-e2e", "config.json", None,
                           RepositoryNotFoundError),
    "missing file": (TINY, "no-such-file.json", None, RemoteEntryNotFoundError),
    "missing revision": (TINY, "config.json", "no-such-branch-e2e", RevisionNotFoundError),
    "gated repository": ("meta-llama/Llama-3.2-1B", "config.json", None, GatedRepoError),
}


@pytest.mark.repo(auto_block=True)
@pytest.mark.parametrize("case", ERROR_CASES)
def test_errors_are_the_hubs_errors(repository, tmp_path, case):
    repo_id, filename, revision, expected = ERROR_CASES[case]

    def error(cache):
        with pytest.raises(HfHubHTTPError) as e:
            hf_hub_download(repo_id, filename, revision=revision, endpoint=repository.url,
                            cache_dir=tmp_path / cache, token=False)
        return type(e.value)

    assert error("first") is expected
    assert error("again") is expected, "also when answered from the negative cache"
    # the error must not have made Nexus block the remote
    assert os.path.getsize(download(repository, tmp_path, TINY, "config.json")) > 0


def test_conditional_and_range_requests_on_cached_files(repository, tmp_path):
    path = download(repository, tmp_path, TINY, "model.safetensors")
    url = "%s/%s/resolve/%s/model.safetensors" % (repository.url, TINY, TINY_COMMIT)
    with open(path, "rb") as f:
        content = f.read()

    status, headers, _ = http(url, "HEAD")
    assert status == 200 and int(headers["Content-Length"]) == len(content)
    etag = headers["ETag"]
    assert http(url, headers={"If-None-Match": etag})[0] == 304
    status, headers, body = http(url, headers={"Range": "bytes=1000-1999"})
    assert status == 206 and body == content[1000:2000]
    status, _, body = http(url, headers={"Range": "bytes=-10"})
    assert status == 206 and body == content[-10:]


def test_paginated_listing_is_complete_and_pages_point_at_the_proxy(repository, relay):
    # allenai/c4 en/ has just over 1000 entries: two pages
    expected = sorted(e.path for e in hub(relay).list_repo_tree("allenai/c4", path_in_repo="en", repo_type="dataset"))
    actual = sorted(e.path for e in proxied(repository).list_repo_tree("allenai/c4", path_in_repo="en",
                                                                      repo_type="dataset"))
    assert len(expected) > 1000 and actual == expected
    _, headers, _ = http(repository.url + "/api/datasets/allenai/c4/tree/main/en?recursive=false&expand=false")
    assert headers["Link"].startswith("<" + repository.url + "/api/datasets/allenai/c4/tree/main/en?")

    mark = relay.mark()
    again = sorted(e.path for e in proxied(repository).list_repo_tree("allenai/c4", path_in_repo="en",
                                                                     repo_type="dataset"))
    assert again == expected and relay.since(mark) == [], "all pages are cached"


def test_branch_and_commit_share_one_stored_copy(repository, tmp_path):
    download(repository, tmp_path, TINY, "config.json", revision="main")
    download(repository, tmp_path, TINY, "config.json", revision=TINY_COMMIT, force_download=True)
    stored = [a for a in repository.file_assets() if a["path"].endswith("/config.json")]
    assert [a["path"] for a in stored] == ["/%s/resolve/%s/config.json" % (TINY, TINY_COMMIT)]


def test_components_are_repository_revisions(repository, tmp_path):
    download(repository, tmp_path, TINY, "config.json")
    download(repository, tmp_path, "julien-c/titanic-survival", "titanic.csv", repo_type="dataset")
    components = {(c["group"], c["name"], c["version"]) for c in repository.nexus.components(repository.name)}
    assert components == {
        ("hf-internal-testing", "tiny-random-gpt2", TINY_COMMIT),
        ("julien-c", "titanic-survival", hub_commit(repository, "julien-c/titanic-survival", "dataset")),
    }


def hub_commit(repository, repo_id, repo_type):
    return proxied(repository).repo_info(repo_id, repo_type=repo_type).sha


def test_file_system_interface(repository):
    fs = HfFileSystem(endpoint=repository.url, token=False)
    assert "datasets/julien-c/titanic-survival/titanic.csv" in fs.ls("datasets/julien-c/titanic-survival",
                                                                    detail=False)
    with fs.open("datasets/julien-c/titanic-survival/titanic.csv", "rb") as f:
        assert f.read(8) == b"Survived"
    assert fs.glob("datasets/julien-c/titanic-survival/*.csv") == ["datasets/julien-c/titanic-survival/titanic.csv"]


def test_clients_can_authenticate_to_nexus_in_the_endpoint_url(repository, nexus, tmp_path):
    nexus.anonymous_access(False)
    try:
        with pytest.raises(HfHubHTTPError) as e:
            download(repository, tmp_path / "anonymous", TINY, "config.json")
        assert e.value.response.status_code == 401
        scheme, rest = repository.url.split("://", 1)
        authenticated = "%s://%s:%s@%s" % (scheme, nexus.user, nexus.password, rest)
        path = hf_hub_download(TINY, "config.json", endpoint=authenticated, cache_dir=tmp_path / "user", token=False)
        assert os.path.getsize(path) > 0
    finally:
        nexus.anonymous_access(True)


def test_unchanged_file_in_another_commit_is_not_downloaded_again(repository, relay, tmp_path):
    # commits that only changed generation_config.json: pytorch_model.bin is the same in both
    first, second = "846cc056cfd12b673fba447d93b06e9a60653eda", "a7121eefebf95db88463ff247b50b76f8cf109ec"
    a = download(repository, tmp_path / "a", TINY, "pytorch_model.bin", revision=first)
    mark = relay.mark()
    b = download(repository, tmp_path / "b", TINY, "pytorch_model.bin", revision=second)
    assert sha256(a) == sha256(b)
    assert relay.since(mark, cdn=True) == [], "served from the copy stored for the first commit"
    assert len([x for x in repository.file_assets() if x["path"].endswith("/pytorch_model.bin")]) == 1


def test_mislabelled_file_is_served_and_cached(repository, relay, tmp_path):
    # a PNG called .jpg: Hub files are verified by hash, not by type
    name = "ai2d-demo.jpg"
    path = download(repository, tmp_path, "hf-internal-testing/fixtures_image_utils", name, repo_type="dataset")
    with open(path, "rb") as f:
        assert f.read(4) == b"\x89PNG"
    reference = hub(relay).get_paths_info("hf-internal-testing/fixtures_image_utils", [name], repo_type="dataset")[0]
    assert sha256(path) == reference.lfs.sha256
    assert [a for a in repository.file_assets() if a["path"].endswith("/" + name)], "cached despite its name"


def test_other_spellings_are_stored_under_the_canonical_name(repository, tmp_path):
    download(repository, tmp_path / "a", "OpenAI-Community/GPT2", "config.json")
    download(repository, tmp_path / "b", "openai-community/gpt2", "config.json")
    stored = [a["path"] for a in repository.file_assets() if a["path"].endswith("/config.json")]
    assert len(stored) == 1 and stored[0].startswith("/openai-community/gpt2/resolve/")


def test_kernel_repositories(repository, relay, tmp_path):
    path = download(repository, tmp_path, "kernels-community/megablocks", "README.md", repo_type="kernel")
    reference = hub(relay).get_paths_info("kernels-community/megablocks", ["README.md"], repo_type="kernel")[0]
    assert git_blob_id(path) == reference.blob_id
    assert proxied(repository).repo_info("kernels-community/megablocks", repo_type="kernel").sha


def test_listings_and_publisher_overviews(repository, relay):
    api = proxied(repository)
    expected = [d.id for d in hub(relay).list_datasets(author="hf-internal-testing", limit=3)]
    assert [d.id for d in api.list_datasets(author="hf-internal-testing", limit=3)] == expected
    assert api.get_organization_overview("openai").name == "openai"
    assert api.get_user_overview("julien-c").username == "julien-c"


@pytest.mark.parametrize("path", ["api/whoami-v2", "api/settings/repositories", "api/notifications",
                                  "api/organizations/openai/members", "api/jobs/openai"])
def test_identity_endpoints_are_not_proxied(repository, relay, path):
    """The repository's own Hub token must not be usable, or revealed, through the proxy."""
    mark = relay.mark()
    assert http(repository.url + "/" + path)[0] == 404
    assert relay.since(mark) == []
