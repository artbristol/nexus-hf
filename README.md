# Hugging Face proxy for Nexus Repository Core

A plugin that adds a **Hugging Face proxy** repository type to the open-source
[Sonatype Nexus Repository Core](https://github.com/sonatype/nexus-public), so that
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
   installation.
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
* Downloads always use plain HTTP; Hugging Face's XET protocol is not supported.
* Access to gated or private models with a token has not been tested yet.

## License

[Eclipse Public License 1.0](LICENSE.txt), the license of Nexus Repository Core.

"Sonatype" and "Sonatype Nexus" are trademarks of Sonatype, Inc. "Hugging Face" is a trademark of
Hugging Face, Inc. This project is not affiliated with either.
