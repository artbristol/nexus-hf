# Hugging Face proxy for Nexus Repository Core

A plugin that adds a **Hugging Face proxy** repository type to the open-source
[Sonatype Nexus Repository Core](https://github.com/sonatype/nexus-public) (formerly OSS), so that
`huggingface_hub`, `transformers` and anything else that honours `HF_ENDPOINT` can download
models, datasets, spaces and kernels through Nexus, with caching.

It is an independent project, not affiliated with or endorsed by Sonatype. Sonatype's Community
and Pro editions already include Hugging Face proxying; this plugin is for the open-source Core
build.

## Compatibility

Each plugin release works with exactly one Nexus version:

| Plugin | Nexus Repository Core |
|---|---|
| 0.1.0 | 3.97.2-02 |

## Building

You can skip this and download the jar from the
[releases](https://github.com/artbristol/nexus-hf/releases) instead.

Requires JDK 25 or later and Maven.

```sh
mvn package
```

The jar is `target/nexus-repository-huggingface-<version>.jar`.

## Installing

1. Copy `nexus-repository-huggingface-<version>.jar` into the `deploy/` directory of your Nexus
   installation (see [Getting Nexus Repository Core](#getting-nexus-repository-core) if you don't
   have one).
2. Start Nexus with the JVM option `-Dloader.path=deploy`:

   ```sh
   INSTALL4J_ADD_VM_PARAMS="-Dloader.path=deploy" bin/nexus run
   ```

3. Create a repository: *Settings → Repositories → Create repository → huggingface (proxy)*, with
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

4. For gated or private models, add a Hugging Face access token to the repository as HTTP
   authentication of type bearer token. Everything that token can read becomes readable by anyone
   who can read the Nexus repository; restrict access to the repository accordingly.

5. Optionally, enable XET (see below).

### XET

Hugging Face stores large files with its XET protocol. Clients that have `hf_xet` installed (the
default for `huggingface_hub`) can download them with it, through Nexus, which caches the pieces of
XET storage they fetch. Without XET, these files are downloaded over plain HTTP, also through
Nexus.

To enable it:

1. Activate the *Hugging Face XET Token Realm*: *Settings → Security → Realms*. XET clients
   authenticate their storage requests with short-lived tokens that Nexus issues to them, as the
   Nexus user they asked as, not with a password.
2. Enable XET for the repository: *Enable XET* in the repository form, or `"xet": {"enabled": true}`
   in the REST API request above. It is off unless enabled.

XET is offered only to authenticated users (credentials in the endpoint URL, see below); anonymous
clients download over HTTP. The URLs of XET storage requests contain tokens, so these appear in
Nexus' request log; each is valid for an hour, and only for the one piece of a file its URL names.
Blocking the repository's remote blocks XET storage too. Setting `nexus.format.huggingface.xet.enabled=false` in `etc/nexus.properties` turns XET
off for all repositories.

## Using

Point clients at the repository:

```sh
export HF_ENDPOINT=http://localhost:8081/repository/hf-proxy
hf download openai-community/gpt2
```

Clients authenticate to Nexus, not to Hugging Face. Either allow anonymous read access to the
repository, or put Nexus credentials into the endpoint URL:

```sh
export HF_ENDPOINT=http://user:password@localhost:8081/repository/hf-proxy
```

Clients do not need a Hugging Face token; the repository uses its own. A token set on the client
(`HF_TOKEN`) does no harm.

Besides `hf` and `huggingface_hub`, this works for libraries built on them, such as
`transformers` and `datasets`.

Large files start downloading immediately, even when Nexus has not cached them yet, and work
with the default client settings.

## Limitations

* Proxy repositories only: no hosted or group repositories, no uploads.
* XET downloads need an authenticated user and the XET token realm; other clients use plain HTTP.

## Getting Nexus Repository Core

Sonatype's website doesn't offer a download of Nexus Repository Core, so assemble it from Maven
Central:

```sh
V=3.97.2-02
curl -LO https://repo1.maven.org/maven2/org/sonatype/nexus/common/assemblies/nexus-base-overlay/$V/nexus-base-overlay-$V.zip
curl -LO https://repo1.maven.org/maven2/org/sonatype/nexus/selfhosted/assemblies/nexus-repository-core/$V/nexus-repository-core-$V.jar
unzip nexus-base-overlay-$V.zip
mv nexus-repository-core-$V.jar nexus-base-overlay-$V/bin/
chmod +x nexus-base-overlay-$V/bin/nexus
```

The admin password is in `sonatype-work/nexus3/admin.password`.

## License

[Eclipse Public License 1.0](LICENSE.txt), the license of Nexus Repository Core.

"Sonatype" and "Sonatype Nexus" are trademarks of Sonatype, Inc. "Hugging Face" is a trademark of
Hugging Face, Inc. This project is not affiliated with either.
