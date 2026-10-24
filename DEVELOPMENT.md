# Development notes

How the plugin works, how it is built and released, and how it has been tested. For installing
and using it, see the [README](README.md).

The plugin is a clean-room implementation, written from the nexus-public sources and the
observable behaviour of huggingface.co and the public `huggingface_hub` client. It is not derived
from the Hugging Face support in Sonatype's Community or Pro editions.

## Building and testing

```sh
mvn package        # compiles, runs the unit tests, builds the jar
```

The unit tests cover what can be checked without a Hub: path parsing, error encoding, the
streaming download (verification, resuming, concurrent readers) and content type validation
against Nexus' real MIME detection. Everything else is covered by the [end-to-end
tests](#end-to-end-tests).

* The parent POM is Nexus' `nexus-common-components-formats-parent`, resolved from Maven Central
  (`<relativePath/>`); no nexus-public checkout is needed. It enforces JDK 25 for the build and
  compiles for Java 21.
* The parent's `githook-checks` profile would point `core.hooksPath` of the enclosing git
  repository at Nexus' own hooks; its execution is bound to phase `none` in `pom.xml`.

## Targeting another Nexus version

The plugin uses Nexus internals (proxy facet support, content store, view handlers), which change
between releases. To build for another version, change the `<parent>` version in `pom.xml`, fix
whatever no longer compiles, test against that Nexus version, and add a row to the compatibility
table in the README.

## How Nexus loads the plugin

Nexus Repository Core is a Spring Boot fat jar started by `PropertiesLauncher`, which adds the
jars in `loader.path` to the classpath. Nexus then component-scans the `org.sonatype.nexus` and
`com.sonatype.nexus` packages, so **the Java packages must stay under `org.sonatype.nexus`** for
the format to be found. Database tables (`huggingface_*`) are created at startup by the
`FormatStoreSupport` DAOs.

The UI needs no changes: nexus-public already contains the repository forms for
`huggingface-proxy`. The REST endpoint `/v1/repositories/huggingface/proxy` is provided by this
plugin.

## Request handling

`huggingface_hub` (checked against 2.3.0) talks to two kinds of endpoint:

| Client request | Purpose |
|---|---|
| `HEAD /{repo}/resolve/{rev}/{file}` | file metadata: `X-Repo-Commit`, `X-Linked-Etag`/`ETag`, `X-Linked-Size`/`Content-Length` |
| `GET  /{repo}/resolve/{rev}/{file}` | file bytes |
| `GET  /api/{type}s/{repo}[/revision/{rev}]` | repo info (commit `sha`, file list) |
| `GET  /api/{type}s/{repo}/tree/{rev}[/{path}]` | file listing, paginated with `Link: <...>; rel="next"` |
| `GET  /api/{type}s?search=...` | listings and searches |
| `GET  /api/{organizations,users}/{name}/overview` | publishers (checked by the `kernels` package) |

`{repo}` is `name` or `owner/name`; `{type}` is `model`, `dataset`, `space` or `kernel`. File URLs of
datasets, spaces and kernels are prefixed with `datasets/`, `spaces/` and `kernels/`.

Nothing else under `/api/` is proxied (404): in particular nothing about the identity behind the
repository's token (`whoami`, settings, billing, jobs, notifications, members, ...), which would
otherwise be readable by anyone who can read the repository. See `HuggingFacePaths.API_REGEX`.

### Files

Upstream answers `resolve` with a 302 to a CDN (LFS/XET files) or a 307 to a relative
`/api/resolve-cache/...` URL (small files). Nexus' HTTP client follows redirects, which loses
the Hub headers, so a file request is served in two steps:

1. **File metadata** — an upstream `HEAD` with redirects disabled. Relative, same-host
   redirects are followed by hand and the CDN redirect is not, exactly as `huggingface_hub`
   does. The result (commit, etag, size) is cached as a small JSON metadata asset under
   `/.meta/<file path>.json`. It is subject to *metadata max age* for branch/tag revisions and to *content
   max age* for commit-hash revisions (immutable).
2. **File content** — fetched from `/{repo}/resolve/{commit}/{file}` (redirects followed) and
   stored at that commit-pinned path. All branch names that point at the same commit share
   one stored copy.

Revisions `refs/pr/N` and `refs/convert/X` contain slashes; clients send them as `%2F`, the
repository sees them decoded, and the proxy re-encodes them upstream.

### Streaming uncached files

A Nexus proxy normally downloads a file completely before sending the first byte. For multi-GB
model files that is minutes of silence, and `huggingface_hub` gives up after about a minute
(10 s read timeout, 5 retries). Uncached files are therefore downloaded in the background into
a temporary file that every requesting client reads while it grows (`StreamingDownload`):

* bytes are released only once verified — the last 64 kB chunk is held back until size and hash
  (LFS SHA-256 or git blob id) match the file metadata, so no client ever receives a complete
  but corrupt file;
* a transport failure (connection dropped or timed out, body ending early) is resumed with a
  Range request, up to 3 times, without the clients noticing; corrupt content is not retried;
* concurrent requests for the same file share one upstream download;
* the download completes and is cached even if all clients disconnect;
* range requests are served as soon as the requested bytes have arrived.

A client `HEAD` needs only step 1, so it never triggers a download of a multi-GB file (the
client's metadata timeout is 10 s). Responses carry `X-Repo-Commit`, `ETag`, `X-Linked-Etag`
and `X-Linked-Size` built from the metadata.

Files are stored under the repository's canonical name: the Hub redirects other spellings
(`OpenAI-Community/GPT2`) and renamed repositories to it, and the proxy records where it was sent.

Etags are content hashes, so content is stored once per Nexus repository: an index
(`/.meta/content/<etag>.json`) maps etags to stored files, and a file that is identical in another
commit (only the README changed) or another repository is served from the stored copy, without
downloading or storing it again.

When a branch or tag resolves to a commit, the file metadata is also stored for that commit:
`snapshot_download` asks for every file by commit hash, which is then answered from the cache
(also while the Hub is unreachable).

### API

`/api/...` GETs are proxied and cached as metadata (query string included in the cache key).
Two rewrites keep clients on the proxy:

* `xetHash` fields are removed from JSON. Otherwise `snapshot_download` skips the `HEAD` and
  fetches straight from XET storage.
* Paginated listings (`Link: <...>; rel="next"`) are proxied page by page: each page is cached
  with its next-page link, which is served pointing at this repository (`HuggingFaceHeadersHandler`).

`xet-read-token` endpoints are not proxied (404). API responses are served as `application/json`.

### Errors

The Hub describes errors with `X-Error-Code` (`RepoNotFound`, `RevisionNotFound`,
`EntryNotFound`, `GatedRepo`) and `X-Error-Message`, which `huggingface_hub` turns into specific
exceptions. Upstream 401/403/404 responses are passed through with these headers, also when served
from the negative cache (which only keeps the status, so they travel in the status message; see
`HuggingFaceErrors`). A bare 401 is what the Hub answers for missing, private and gated repos; it
is passed on as `RepoNotFound`, which the client would otherwise only infer for `https` endpoints.
429 is passed on with its `Retry-After`, so clients back off.

An upstream that fails, or does not behave like a Hub (no `X-Repo-Commit`, redirect loops), is
answered with 502, which unlike a 404 is not negatively cached.

Because of those 401s, the format has its own auto-block policy: only 407 and 5xx block the
remote. With the default policy a single request for a non-existent repo would block the whole
proxy.

## Content type validation

Strict content type validation compares the detected type of content with the type its name
suggests, to keep e.g. an upstream error page from being cached. For repository files that check
is both too weak and too strict: they are verified against the Hub's content hashes before being
stored, and they are whatever their authors uploaded under whatever name (a PNG called `.jpg`, model
weights of no detectable type), which strict validation would refuse to cache.
`HuggingFaceContentValidator` therefore validates strictly only what the proxy produces itself: API
responses and file metadata, typed as JSON.

## Components

One component per repo revision: namespace = owner (empty for legacy un-namespaced repos),
name = repo name, version = commit hash, kind = `model` / `dataset` / `space`. Each file is an
asset of that component. Metadata and API responses are component-less assets.

## Not supported (yet)

* XET transfer protocol.
* Gated/private models with a token: bearer-token auth is wired up but untested. Without a
  token they fail with the proper `GatedRepo` error.
* Hosted and group repositories; uploads.
* Search customisation (assets are indexed by the default SQL search).

## End-to-end tests

`e2e/` holds a pytest suite that runs against a Nexus with the plugin installed and the real
huggingface.co. It creates its own repositories (named `e2e-*`) and deletes them afterwards.

```sh
cd e2e
pip install -r requirements.txt
NEXUS_URL=http://localhost:8081 NEXUS_PASSWORD=... pytest
```

`NEXUS_USER` (default `admin`) must be allowed to create repositories and to change anonymous
access and SSRF protection, which the suite changes for the run and restores afterwards.
`transformers` and `datasets` are optional; their tests are skipped without them.

Nexus does not talk to huggingface.co directly but through a relay started by the suite
(`relay.py`), which forwards everything unchanged. Being the only way to the Hub, it keeps the
suite polite and gives the tests something to assert on:

* requests are at least 0.25 s apart, identified by a User-Agent pointing at this project, and
  limited to 500 requests and 150 MB per run; a 429 from the Hub stops the run;
* only tiny repositories are used; a full run makes about 300 requests and transfers about
  30 MB;
* every request is recorded, so tests check e.g. that a cached snapshot needs no request at
  all, or that three concurrent clients caused one CDN download;
* faults can be injected into chosen responses — corrupt, truncated or stalled bodies, throttling,
  errors, dropped connections, missing Hub headers — which is how the failure handling is tested
  without a fake Hub. Faults with a status do not reach the Hub.

The relay must be reachable from Nexus: by default it listens on 127.0.0.1 (Nexus on the same
machine) and stands in for the CDN under the name `localhost`, since the CDN has to be a
different host than the Hub. Set `E2E_RELAY_HOST` and `E2E_RELAY_CDN_HOST` otherwise.

`test_hub.py` checks behaviour against the real Hub: snapshots of models, datasets (empty files,
nested directories) and spaces verified file by file against the Hub's hashes; awkward file names;
`refs/pr/N`, short commit hashes, legacy and renamed repository ids; file metadata identical to
the Hub's without downloading; the Hub's own error types for missing repos, files and revisions
and gated repos, also from the negative cache and without auto-blocking; conditional and range
requests; paginated listings; storage shared between branch and commit; components; `HfFileSystem`;
clients authenticating with credentials in the endpoint URL; and that nothing ever uses XET; files
unchanged between commits not downloaded again; mislabelled files cached; other spellings stored
under the canonical name; kernel repositories, listings and publisher overviews; and identity
endpoints not proxied.

`test_faults.py` checks failure handling: dropped and stalled transfers resumed transparently;
corrupt content never served complete nor cached; slow downloads streamed to concurrent clients
from one upstream download; client disconnects not aborting caching; ranges into a file still
downloading; 429, 5xx, dropped connections and non-Hub responses; cached files served while
the Hub is down; a repository deleted during a download.

`test_libraries.py` loads a model config and tokenizer with `transformers` and a dataset with
`datasets`.

## Tested manually

Large files, which the suite avoids: an uncached 1.5 GB file downloaded by 3 concurrent clients
with default client settings — one upstream download, all clients at full upstream speed (first
byte ~1 s, no gap over 1.5 s), ~100 s; cached ~7 s. Before streaming, the same download failed
after 66 s.

## Releasing

CI (`.github/workflows/build.yml`) builds and tests every push and pull request. To release,
set `<version>` in `pom.xml`, update the compatibility table in the README, commit, and push a
tag `v<version>`; the workflow checks that the tag matches the version and publishes a GitHub
release with the jar.
