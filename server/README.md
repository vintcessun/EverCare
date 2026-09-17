# EverCare server

An OpenAI-compatible gateway. It holds the model provider's key, injects an
elder-friendly system prompt in the caller's language, and gates access on a
subscription bound to a device id.

Because it speaks the same protocol it proxies, the app's "use your own
provider" mode needs no separate code path — only a different base URL, token
and model.

## Running

```bash
cp .env.example .env         # then set EVERCARE_UPSTREAM_API_KEY
cargo run
```

Configuration is read from the environment:

| Variable | Default | Meaning |
| --- | --- | --- |
| `EVERCARE_BIND` | `0.0.0.0:8080` | Listen address |
| `EVERCARE_UPSTREAM_BASE_URL` | `https://api.deepseek.com` | Any OpenAI-compatible provider |
| `EVERCARE_UPSTREAM_API_KEY` | *(empty)* | Provider key. Without it the service starts but chat fails |
| `EVERCARE_UPSTREAM_MODEL` | `deepseek-chat` | The model subscribers get |
| `EVERCARE_DATA_FILE` | `./evercare-data.json` | Where device records are persisted |
| `EVERCARE_TRIAL_DAYS` | `7` | Free days granted at registration |

`RUST_LOG` controls logging (`RUST_LOG=debug` for forwarded-request detail).

## API

### OpenAI-compatible

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| `POST` | `/v1/chat/completions` | device token | Streams when `"stream": true`. Accepts an extra `"language"` field (BCP-47) that picks the system prompt. A client-supplied `model` is accepted and ignored. |
| `GET` | `/v1/models` | none | Reports the single managed model |

### EverCare-specific

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| `GET` | `/healthz` | none | Liveness |
| `POST` | `/v1/evercare/devices` | none | Registers a handset, returns `token` **once** |
| `DELETE` | `/v1/evercare/devices/me` | device token | Forgets the device and its plan |
| `POST` | `/v1/evercare/explain` | device token | Wraps captured screen text in a localized "explain this" instruction |
| `GET` | `/v1/evercare/subscription` | device token | Plan, expiry, days remaining |
| `POST` | `/v1/evercare/subscription/redeem` | device token | Adds months — **receipt verification is a TODO** |
| `POST` | `/v1/evercare/subscription/auto-renew` | device token | Sets the auto-renew flag |
| `POST` | `/v1/evercare/subscription/transfer` | device token | Moves a plan from an old handset, which is left with nothing |

Errors use the OpenAI envelope, so a client written against a third-party
provider parses them with the same code:

```json
{ "error": { "type": "subscription_required", "message": "no active subscription" } }
```

`402 subscription_required` is the one the app turns into a gentle "your plan has
ended" rather than an error, and `401` tells it to register again.

## Design notes

**No accounts.** The subscription belongs to the device id. An elderly user
should never have to create an account before the app will help them, so there
are no passwords or email addresses anywhere in this service. The cost is that a
plan does not follow the user to a new phone by itself, which is what
`/subscription/transfer` is for: the new handset must hold its own token *and*
present the old one, so neither alone can steal a plan.

**Tokens** are `dev_<uuid>.<secret>`. Only a SHA-256 hash is stored, so the data
file cannot leak live credentials, and the prefix makes lookup a single map hit.

**Storage** is a JSON file written with write-then-rename, so a crash mid-write
cannot truncate it. It is not a database because the record count is small and
this keeps deployment to one binary plus one file; the `Store` API is async and
keyed so moving to Postgres later touches only `store.rs`.

## Before this ships

`redeem` accepts **any non-empty code** and grants months. It must verify the
receipt against App Store / Play / WeChat Pay first. It is wired up so the app's
payment flow can be built end to end, and is marked `TODO(payments)` in the
source.

## Tests

```bash
cargo test
```

12 unit tests plus 11 integration tests that run the real router over HTTP
against a stub upstream, covering auth, the subscription gate, prompt injection
per language, redemption and transfer.
