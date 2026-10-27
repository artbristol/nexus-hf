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

The unit tests cover what can be checked without a Hub, against something independent where
possible: Nexus' real MIME detection, real XET storage data from the Hub (`src/test/resources`), the
RFC for `multipart/byteranges`. Everything else is covered by the [end-to-end
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
| `GET  /api/{type}s/{repo}/xet-read-token/{rev}` | XET token and storage URL, see [XET](#xet) |

`{repo}` is `name` or `owner/name`; `{type}` is `model`, `dataset`, `space` or `kernel`. File URLs of
datasets, spaces and kernels are prefixed with `datasets/`, `spaces/` and `kernels/`.

Nothing else under `/api/` is proxied (404): in particular nothing about the identity behind the
repository's token (`whoami`, settings, billing, jobs, notifications, members, ...), which would
otherwise be readable by anyone who can read the repository. See `HuggingFacePaths.API_REGEX`.

### Files

Upstream answers `resolve` with a 302 to a CDN (large files) or a 307 within the Hub (small
files). Nexus' HTTP client follows redirects, which loses the Hub headers, so a file request is
served in two steps: the file metadata (commit, etag, size), from a `HEAD` that does not follow the
CDN redirect, as `huggingface_hub` does; then the content, stored at the commit-pinned path, so that
all branches pointing at a commit share one copy. Metadata of branches and tags is subject to
*metadata max age*, everything addressed by commit hash to *content max age*.

Revisions `refs/pr/N` and `refs/convert/X` contain slashes; clients send them as `%2F`, the
repository sees them decoded, and the proxy re-encodes them upstream.

### Streaming uncached files

A Nexus proxy normally downloads a file completely before sending the first byte. For multi-GB
model files that is minutes of silence, and `huggingface_hub` gives up after about a minute
(10 s read timeout, 5 retries). Uncached files are therefore downloaded in the background into
a temporary file that every requesting client reads while it grows (`StreamingDownload`). It
guarantees that no client ever receives a complete but corrupt file (the end is held back until the
hash matches), that dropped connections are resumed without clients noticing, and that concurrent
clients share one upstream download, which is cached even if they all go away.

A client `HEAD` needs only the metadata, so it never triggers a download of a multi-GB file (the
client's metadata timeout is 10 s).

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

`/api/...` GETs are proxied and cached (query string included in the cache key). Paginated listings
are proxied page by page, with their next-page links pointing at this repository. `xetHash` fields
are removed unless XET is offered to the client: given one, `snapshot_download` skips the `HEAD` and
goes for XET.

### XET

XET (checked against `hf_xet` 1.7.0 and the [protocol spec](https://huggingface.co/docs/xet/index))
stores files as chunks, packed into xorbs of up to 64 MB. A client with `hf_xet`:

1. learns a file's XET hash from `X-Xet-Hash` (on the `HEAD`, or `xetHash` in a listing), and gets
   a token and the storage (CAS) URL from `xet-read-token`, in `X-Xet-*` headers;
2. asks the storage for the file's *reconstruction* (`GET {cas}/v2/reconstructions/{hash}`, with
   `Authorization: Bearer <token>` and a `Range` for a window of the file): terms (chunk ranges of
   xorbs, in file order), and presigned URLs for the byte ranges of the xorbs they need;
3. fetches those byte ranges, with no credentials, and decompresses the chunks.

The repository stands in for the storage, under `/.xet/` (`HuggingFaceXetFacet`; the protocol
details are in its Javadoc). The decisions that are not obvious from the code:

* **Whole-file reconstructions, sliced.** The client asks for windows of a file whose sizes depend
  on its download speed, and the storage answers each with xorb byte ranges cut to that window. So
  the proxy fetches the reconstruction of the whole file once, stores it, and answers every window
  from it: the xorb ranges, and so the cache, are the same whatever the windows.
* **What is cached is xorb ranges**, checked for their structure only: they carry no hashes, and
  a file's XET hash is a Merkle hash over all its chunks, decompressed.
* **The client's own token never appears in URLs.** Each xorb URL carries a token for that range
  only, as these URLs end up in request logs.
* **Expired presigned URLs** are answered 403, which makes the client fetch the reconstruction
  again: then the proxy fetches new ones.
* **Blocking the remote blocks XET storage too.** Nexus' HTTP client does not: it only blocks the
  first host contacted after a configuration change.
* **The Hub's `xet-auth` link is passed on as is**: `huggingface_hub` rewrites
  `https://huggingface.co` to its endpoint, credentials included; a link to the repository would
  lose them.

**Authentication.** `hf_xet` sends `Authorization: Bearer <XET token>` to the storage (replacing
any Basic credentials in the URL) and nothing at all to the xorb URLs, so Basic authentication
cannot reach it. A realm (`HuggingFaceXetTokenRealm`) therefore accepts the tokens this Nexus issued,
as the user they were issued to, with that user's permissions; but only in the requests each token
is for. So XET is offered only where it can work: repository enabled, realm active, user not
anonymous. Tokens are signed with a key that lives as long as the Nexus process; a download running
across a restart fails and has to be retried.

The repository forms of nexus-public already have the *Enable XET* setting, hidden unless the UI
state has `nexus.format.huggingface.xet.enabled`, which the plugin sets.

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

### The repository's Hub token

Nexus does not send a repository's bearer token by itself, so the plugin does, to the Hub only:
redirects to the CDN are followed without it (presigned URLs refuse a second credential, and the
CDN has no business seeing it), and XET storage never gets it either.

### Auto-blocking

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
asset of that component. Metadata, API responses and XET storage data (`/.xet/`) are component-less
assets.

## Not supported (yet)

* XET for anonymous clients: tokens are issued to users only. XET uploads.
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
`requirements.txt` is a lockfile (generated from `requirements.in`, with hashes): the client versions
the suite was last run with, which are the ones these notes say the plugin was checked against. To
move to newer clients, regenerate it as its header says, run the suite, and update those notes.
`transformers` and `datasets` are optional; their tests are skipped without them. `E2E_HF_TOKEN`, a
Hub token that can read `meta-llama/Llama-3.2-1B`, enables the test of a gated repository; it is
skipped without one.

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

The test modules: `test_hub.py` (behaviour against the real Hub), `test_faults.py` (failure
handling, with faults injected at the relay), `test_xet.py` (XET, as a user who may only read the
test repository), `test_token.py` (where the repository's Hub token goes, and gated repositories),
`test_libraries.py` (`transformers` and `datasets`). The relay also rewrites the XET storage URLs in
token responses and reconstructions to go through it.

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
