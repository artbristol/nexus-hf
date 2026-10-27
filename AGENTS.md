# AGENTS.md

Notes for coding agents working on this repository.

## What this is

A Hugging Face proxy plugin for the open-source Nexus Repository Core, installed as a jar into an
unmodified Nexus. See README.md (users) and DEVELOPMENT.md (design, tests, releasing).

## Ground rules

- **Clean room.** Work only from the nexus-public sources, public documentation, and the
  observable behaviour of huggingface.co and `huggingface_hub`. Never decompile, copy or consult
  Sonatype's Community or Pro edition code. Bug reports about those editions are fine as
  inspiration.
- **The repository is public.** Commit nothing about the maintainer's machine or setup: no local
  paths, hostnames, test instance passwords, tokens, or details of the environment.
- **Be very polite to huggingface.co.** Use tiny repositories, make requests sequentially, and
  identify the project in the User-Agent. Stop on a 429. In the e2e suite, everything goes through
  the relay, which enforces this.
- **Don't fake the Hub.** Test against the real Hub, and inject faults with the relay. Don't
  reimplement the Hub's protocol in a fake server.

## Code and tests

- Match the surrounding Nexus code style. Java packages must stay under `org.sonatype.nexus`,
  because Nexus only scans there for components.
- Unit tests must check behaviour against something independent: real MIME detection, real path
  shapes, failure timing. Don't write tests that just mirror the implementation.
- When you find a bug, add a test for it, in e2e if it needs Nexus or the Hub.
- `mvn package` runs the unit tests; `mvn verify` also runs the e2e suite, against a Nexus it
  assembles and starts itself (needs Python 3.13 and huggingface.co). CI runs `mvn verify`.

## Docs

- README.md holds end-user instructions only: building, installing, using. Everything for
  developers goes in DEVELOPMENT.md.
- Keep both accurate when behaviour changes, and update the README's compatibility table when
  targeting another Nexus version.
