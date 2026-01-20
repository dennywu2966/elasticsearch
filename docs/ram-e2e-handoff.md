# RAM IAM E2E Handoff Context

## Goal
Run a full end-to-end validation of the Aliyun RAM IAM realm using Elasticsearch 8.17 + Kibana, including STS verification, role mapping, replay/cache behavior, and Kibana access through a signing proxy.

## What’s Implemented (Repo: es-ram-codex)
- Cloud IAM realm plugin and tests under `plugins/security-realm-cloud-iam/`.
- Automated E2E script: `docs/ram-e2e-validate.sh`.
- Validation guide: `docs/ram-e2e-validation.md`.
- Design doc: `docs/support-ram-design.v0.md`.
- Benchmarks: `benchmarks/src/main/java/org/elasticsearch/benchmark/security/cloudiam/CloudIamTokenBenchmark.java` and `benchmarks/build.gradle` updated.

## Java Client Support (Repo: ../elasticsearch-java)
- Helper for signing the `X-ES-IAM-Signed` header:
  - `java-client/src/main/java/co/elastic/clients/transport/CloudIamSigner.java`
  - `java-client/src/test/java/co/elastic/clients/transport/CloudIamSignerTests.java`

## How to Run E2E on ECS
1) Ensure ECS can reach `https://sts.aliyuncs.com` (or set `IAM_ENDPOINT`).
2) Export RAM credentials in the shell:
```
export RAM_AK=...
export RAM_SK=...
# optional
export RAM_STS_TOKEN=...
export RAM_ARN=acs:ram::123456789:user/iam-test
```
3) Run the script (auto-downloads ES/Kibana 8.17 by default):
```
ALLOW_PLUGIN_INSTALL=1 KEEP_RUNNING=1 \
ES_VERSION=8.17.0 KIBANA_VERSION=8.17.0 \
AUTO_DOWNLOAD=1 \
./docs/ram-e2e-validate.sh
```
4) Validate results:
- `curl -H "X-ES-IAM-Signed: $SIGNED" http://127.0.0.1:9200/_security/_authenticate`
- Kibana at `http://127.0.0.1:5601` (proxy on 9201).

## Notes
- Kibana uses a signing proxy (single IAM identity); Kibana cannot generate per-request signatures itself.
- For 8.17, use an ES 8.17 source checkout to build a compatible plugin ZIP if needed.
- The script keeps ES/Kibana running if `KEEP_RUNNING=1`.
- Logs are written to `.ram-e2e/`.

## Required Context for Codex on ECS
- This repo branch/commit with the plugin and docs changes.
- RAM credentials in environment.
- Permission to install plugins on the ES node.
- OS: standard Linux (x86_64 or aarch64).
