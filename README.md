# Hugging Face proxy for Nexus Repository Core

A plugin that adds a **Hugging Face proxy** repository type (`huggingface-proxy`) to the
open-source [Sonatype Nexus Repository Core](https://github.com/sonatype/nexus-public), so that
`huggingface_hub`, `transformers` and anything else that honours `HF_ENDPOINT` can download
models, datasets and spaces through Nexus, with caching.

It is an independent, clean-room implementation, written from the nexus-public sources and the
observable behaviour of huggingface.co and the public `huggingface_hub` client. It is not
affiliated with or endorsed by Sonatype, and is not derived from the Hugging Face support in
Sonatype's Community or Pro editions. (Those editions already include Hugging Face proxying; this
plugin is for the open-source Core build.)

## Compatibility

The plugin uses Nexus internals, which change between releases, so each plugin build targets one
Nexus version.

| Plugin | Nexus Repository Core |
|---|---|
| 0.1.0 | 3.97.2-02 |

To target another version, change the `<parent>` version in `pom.xml` and rebuild; Sonatype
publishes the Nexus artifacts to Maven Central.

## Installation

1. Download `nexus-repository-huggingface-<version>.jar` from the
   [releases](https://github.com/artbristol/nexus-hf/releases), or [build it](#building).
2. Copy it into the `deploy/` directory of your Nexus installation.
3. Start Nexus with the JVM option `-Dloader.path=deploy`, e.g.

   ```sh
   INSTALL4J_ADD_VM_PARAMS="-Dloader.path=deploy" bin/nexus run
   ```

   Nexus is launched by Spring Boot's `PropertiesLauncher`, which adds the jars in `loader.path`
   (relative to the installation directory) to the classpath; Nexus then picks the plugin up like
   a built-in format.
4. Create a repository: *Settings → Repositories → Create repository → huggingface (proxy)*, with
   remote storage `https://huggingface.co`. Or use the REST API:

   ```sh
   curl -u admin -X POST -H 'Content-Type: application/json' \
     http://localhost:8081/service/rest/v1/repositories/huggingface/proxy -d '{
       "name": "hf-proxy", "online": true,
       "storage": {"blobStoreName": "default", "strictContentTypeValidation": true},
       "proxy": {"remoteUrl": "https://huggingface.co", "contentMaxAge": 1440, "metadataMaxAge": 1440},
       "negativeCache": {"enabled": true, "timeToLive": 1440},
       "httpClient": {"blocked": false, "autoBlock": true}}'
   ```

   For gated or private models, configure a Hugging Face access token as the repository's
   HTTP authentication (bearer token).

## Usage

```sh
export HF_ENDPOINT=http://localhost:8081/repository/hf-proxy
hf download openai-community/gpt2
```

Clients authenticate to Nexus, not to Hugging Face: either allow anonymous read access to the
repository, or give the client Nexus credentials. A Hugging Face token set on the client is not
needed (Nexus uses the token configured on the repository).

Upstream XET is never exposed, so clients always use plain HTTP downloads through the proxy.

## Building

```sh
mvn package
```

Requires JDK 25 or later (enforced by the Nexus parent POM; the jar itself targets Java 21, like
Nexus). All dependencies come from Maven Central; the jar is
`target/nexus-repository-huggingface-<version>.jar`.

## Request handling

`huggingface_hub` (checked against 2.3.0) talks to two kinds of endpoint:

| Client request | Purpose |
|---|---|
| `HEAD /{repo}/resolve/{rev}/{file}` | file metadata: `X-Repo-Commit`, `X-Linked-Etag`/`ETag`, `X-Linked-Size`/`Content-Length` |
| `GET  /{repo}/resolve/{rev}/{file}` | file bytes |
| `GET  /api/{type}s/{repo}[/revision/{rev}]` | repo info (commit `sha`, file list) |
| `GET  /api/{type}s/{repo}/tree/{rev}[/{path}]` | file listing, paginated with `Link: <...>; rel="next"` |

`{repo}` is `name` or `owner/name`. Datasets and spaces are prefixed with `datasets/` and `spaces/`.

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

* bytes are released only once verified — the last chunk is held back until size and hash
  (LFS SHA-256 or git blob id) match the file metadata, so no client ever receives a complete
  but corrupt file;
* concurrent requests for the same file share one upstream download;
* the download completes and is cached even if all clients disconnect;
* range requests are served as soon as the requested bytes have arrived.

A client `HEAD` needs only step 1, so it never triggers a download of a multi-GB file (the
client's metadata timeout is 10 s). Responses carry `X-Repo-Commit`, `ETag`, `X-Linked-Etag`
and `X-Linked-Size` built from the metadata.

### API

`/api/...` GETs are proxied and cached as metadata (query string included in the cache key).
Two rewrites keep clients on the proxy:

* `xetHash` fields are removed from JSON. Otherwise `snapshot_download` skips the `HEAD` and
  fetches straight from XET storage.
* Paginated listings (`Link: <...>; rel="next"`) are fetched completely and returned as one
  page, so no upstream URL reaches the client.

`xet-read-token` endpoints are not proxied (404). API responses are served as `application/json`.

### Errors

The Hub describes errors with `X-Error-Code` (`RepoNotFound`, `RevisionNotFound`,
`EntryNotFound`, `GatedRepo`) and `X-Error-Message`, which `huggingface_hub` turns into specific
exceptions. Upstream 401/403/404 responses are passed through with these headers, also when served
from the negative cache (which only keeps the status, so they travel in the status message; see
`HuggingFaceErrors`). A bare 401 is what the Hub answers for missing, private and gated repos; it
is passed on as `RepoNotFound`, which the client would otherwise only infer for `https` endpoints.

Because of those 401s, the format has its own auto-block policy: only 407 and 5xx block the
remote. With the default policy a single request for a non-existent repo would block the whole
proxy.

## Content type validation

Model weights (`.safetensors`, `.bin`, `.gguf`, ...) have no detectable content type, which strict
content type validation rejects. `HuggingFaceContentValidator` accepts content whose type cannot be
detected and still validates everything else against the file name.

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

## Tested

Nexus Repository Core 3.97.2-02, both with the module built into the distribution and as this
plugin jar loaded into an unmodified build; against huggingface.co with `huggingface_hub` 2.3.0
and `hf_xet` installed:

* `hf download` of single files and whole snapshots (models and datasets), by branch and by
  commit; files byte-identical to upstream (LFS SHA-256 / git blob hash).
* No XET requests reach the proxy, and no client request bypasses it.
* `HEAD` of a 548 MB file is answered from metadata in ~30 ms without downloading it.
* With the upstream blocked, previously fetched snapshots download from cache into an empty client cache.
* Repository creation via the REST API and via the UI form.
* Error cases: missing repo, file and revision; gated repo — same client errors as huggingface.co,
  also when repeated (negative cache), and the proxy keeps working afterwards.
* Legacy names (`gpt2`), spaces, `refs/pr/1`, a client sending its own `HF_TOKEN`.
* Uncached 1.5 GB file, 3 concurrent clients with default client settings: one upstream
  download, all clients at full upstream speed (first byte ~1.3 s, no gap over 1.4 s), ~100 s;
  cached: ~7 s. Before streaming, the same download failed after 66 s.
* Client disconnecting mid-download (download still completes and is cached); range request
  into the part of a file that is still downloading.
* Paginated listing of `allenai/c4` `en/` (1032 entries): identical to upstream.
* Restart, then upstream blocked: all previously fetched repos install from cache and verify.

## License

[Eclipse Public License 1.0](LICENSE.txt), the license of Nexus Repository Core.

"Sonatype" and "Sonatype Nexus" are trademarks of Sonatype, Inc. "Hugging Face" is a trademark of
Hugging Face, Inc. This project is not affiliated with either.
