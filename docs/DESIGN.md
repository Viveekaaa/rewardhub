# RewardHub — Design & Roadmap

> Multi-tenant **Rewards-as-a-Service** platform. Any business can integrate it and configure
> its own loyalty program — currency, earning rules, tiers, redemption catalog, badges —
> through an admin dashboard. The engine just executes whatever each business configured.
>
> **The configuration is the product.** Rules are *data*, not code.

---

## 1. Decisions locked in

| Topic | Decision |
|---|---|
| Packaging | **Monorepo, single deployable, multi-module Gradle.** Web-free `rewardhub-core` library + `rewardhub-api` Spring Boot app (which also serves the bundled dashboard) + `rewardhub-admin-ui` frontend module. One boot jar to deploy/run. Modules can be split into separate deployables later for free. |
| Tenancy | **Shared schema**, `org_id` discriminator on every tenant-scoped table |
| Mechanics | Points/loyalty currency · Tiers/levels · Redemption catalog · Badges/achievements |
| Config surface | **Admin dashboard UI** (businesses self-serve) + admin REST API |
| Scale target | Many businesses (multi-tenant SaaS), not a single-business app |
| Stack | Java 17 · Spring Boot 4.x · Spring Data JPA · MySQL · Spring Security · Flyway · springdoc-openapi |

---

## 2. Architecture

### 2.1 Modules (monorepo, multi-module Gradle — restructure from current single module)

```
rewardhub/                   # root Gradle project — version/plugin/BOM management only
├── settings.gradle          # includes the modules below
├── build.gradle             # shared config: Java 17, Spring dependency mgmt, common deps
├── rewardhub-core/          # pure Java: entities, rules engine, ledger logic. NO spring-web.
│   └── build.gradle
├── rewardhub-api/           # Spring Boot app: /v1 (API-key) + /admin (JWT) endpoints, security.
│   └── build.gradle         # depends on core; bundles the built admin-ui as static resources.
│                            # → THIS is the single deployable (boot jar).
└── rewardhub-admin-ui/      # React + TS SPA. A Gradle module that wraps the npm/vite build
    └── build.gradle         # (node-gradle plugin) and emits its output into rewardhub-api's
                             # static resources so `./gradlew build` builds everything at once.

# (later) rewardhub-worker/  — background jobs (expiry, tier recalc, webhook delivery).
#         Starts as a Spring profile/scheduler inside rewardhub-api; split into its own
#         deployable module when volume warrants it.
```

**Mono-service now:** `./gradlew build` → one boot jar serving both the API and the dashboard; one process to deploy and to run locally.

**Why still split the modules:**
- `rewardhub-core` is web-free → genuinely reusable as a library (another JVM app can depend on it), and it forces clean domain boundaries.
- `rewardhub-admin-ui` is isolated → frontend work happens in its own folder with a normal npm workflow; to deploy the UI separately later (CDN/static host), you just stop bundling it — no code change.
- The seams are free to create now (empty project) and expensive to retrofit later.

### 2.2 Runtime components

- **rewardhub-api** (the single deployable, one boot jar) hosts:
  - **REST API**, two auth flavours:
    - **API keys** (machine-to-machine): the integrating business's backend calls `/v1/...`
    - **Admin JWT/session**: dashboard users call `/admin/...`
  - **Admin dashboard** — the built React SPA, served as static resources from the same jar (talks only to `/admin`).
  - **Background jobs** — initially as scheduled tasks inside this process; later extracted to `rewardhub-worker`.
- **MySQL** — single database, shared schema, `org_id` everywhere.
- **Redis** (Phase 9) — balance cache, rate limiting, idempotency-key fast path.
- **Message broker / outbox** (Phase 7+) — reliable webhook delivery, async event processing.

### 2.3 Multi-tenancy

- Every tenant-scoped table has `org_id` (FK to `organization`).
- **Tenant context** resolved once per request from the credential (API key → org; JWT → org claim) and held in a request-scoped holder.
- Isolation enforced **structurally**, not by discipline:
  - Tenant-aware JPA repository base, and/or a Hibernate `@Filter` auto-applied with `org_id`.
  - Any query without an `org_id` predicate on a tenant table is a bug.
- **Environments within an org**: `TEST` vs `LIVE`. Separate API keys; an `environment` flag on `Member` / `LedgerEntry` so test data never pollutes live balances.

### 2.4 Security

- **API keys**: stored hashed; show prefix (e.g. `rh_live_abc…`) for identification, full secret shown once at creation. Scopes (`members:read`, `events:write`, `redemptions:write`, …). Revocable. `last_used_at` tracked.
- **Admin auth**: email + password (hashed), JWT, roles: `OWNER`, `ADMIN`, `ANALYST`.
- **Idempotency**: `Idempotency-Key` header required on all write/event endpoints; per-org store of `(key → response)` so retries are safe replays.
- **Rate limiting** per API key, tied to the org's plan.
- **Audit log** of all config changes (who/what/before/after).
- Secrets in env vars / secret manager — never committed. (`application-local.properties` currently has a plaintext DB password — fix this.)

### 2.5 The ledger (core invariant)

- `LedgerEntry` is **append-only**. Never `UPDATE`, never `DELETE`.
- Corrections = new `REVERSAL` / `ADJUST` entries referencing the original.
- Amounts are **signed integers in minor units** (or fixed-scale `BigDecimal`) — never `float`/`double`.
- `MemberBalance` is a **projection** of the ledger — cached for speed, fully rebuildable. Ship a "rebuild balance from ledger" admin tool from day one.
- **Points lots for expiry (FIFO)**: each `EARN` creates a `PointsLot { remaining, expires_at }`. Redemptions and the expiry sweep consume the oldest non-expired lots first. This keeps "how many points expire on date X" answerable and correct.

### 2.6 Rules engine (the customizable core)

A rule = **trigger event** + **conditions** + **outcome**.

- **Trigger**: an event name (`order.completed`, `user.signup`, `review.submitted`, or a business-defined custom event).
- **Conditions**: structured JSON, e.g.
  ```json
  { "all": [
      { "field": "order.total",    "op": ">=", "value": 50 },
      { "field": "order.currency", "op": "==", "value": "USD" }
  ]}
  ```
  Start with a small, safe evaluator over structured JSON (`all`/`any`/`not`, comparison ops). **Later**: optional expression language (SpEL/MVEL, sandboxed) for power users.
- **Outcome**: award N units of a currency · `RATE_PER_UNIT` (e.g. 1 pt per $1 of `order.total`) · `PERCENTAGE_OF_FIELD` · unlock a badge · contribute to a tier metric.
- **Layering**: base earning rules → campaign multipliers → tier multipliers, with defined priority/stacking rules and per-member caps.

### 2.7 Data integrity & money rules

- `Idempotency-Key` on every mutation; same key ⇒ same response.
- Ledger append-only; balances derived; reconciliation job detects drift.
- All amounts integer minor units or fixed-scale `BigDecimal`.
- Inventory/code-pool consumption under row locks or optimistic locking — no double-issue.
- Redemption fulfilment is its own state machine: `PENDING → FULFILLED | CANCELLED | REFUNDED`; cancel/refund emits reversal ledger entries.

---

## 3. Domain model (entities)

### Tenant & access
- **Organization** — `id, name, slug, status, plan, created_at`.
- **OrgUser** — dashboard user: `id, org_id, email, password_hash, role, status`.
- **ApiKey** — `id, org_id, name, key_hash, key_prefix, environment(TEST|LIVE), scopes, last_used_at, revoked_at`.
- **AuditLog** — `id, org_id, actor, action, entity_type, entity_id, before(json), after(json), at`.

### Program & currency
- **RewardProgram** — `id, org_id, name, status`. (A business may run more than one program; start with one if simpler.)
- **PointsCurrency** — `id, program_id, name(e.g. "Stars"), code, decimal_places, rounding_mode`.
- **PointsExpiryPolicy** — `id, program_id, type(NEVER|FIXED_PERIOD_FROM_EARN|FIXED_CALENDAR_DATE|ROLLING), period_days, ...`.

### Members (the business's end users)
- **Member** — `id, org_id, external_id(the business's user id), email?, attributes(json), status, environment, enrolled_at`.
- **MemberBalance** — `member_id, currency_id, available, pending, lifetime_earned, lifetime_redeemed` (projection).
- **MemberTier** — `member_id, tier_id, achieved_at, expires_at`.

### Ledger
- **LedgerEntry** — `id, org_id, member_id, currency_id, type(EARN|REDEEM|ADJUST|EXPIRE|REVERSAL|TRANSFER), amount(signed), balance_after, source_type, source_id, reason, idempotency_key, status(PENDING|POSTED|VOID), created_at, expires_at?`.
- **PointsLot** — `id, member_id, currency_id, original_amount, remaining_amount, earned_at, expires_at?, source_ledger_entry_id`.

### Earning rules
- **EventType** — `id, org_id, name, payload_schema(json)` (system catalog + org-defined).
- **EarningRule** — `id, program_id, name, trigger_event, conditions(json), outcome_type(FIXED|RATE_PER_UNIT|PERCENTAGE_OF_FIELD|BADGE|TIER_METRIC), outcome_value, currency_id, per_member_limit, valid_from, valid_to, priority, active`.
- **Campaign** — `id, program_id, name, multiplier?, bonus_amount?, applies_to_rule_ids, starts_at, ends_at, active`.

### Tiers
- **Tier** — `id, program_id, name, level(order), qualification_metric(LIFETIME_POINTS|POINTS_IN_PERIOD|SPEND|CUSTOM), threshold, period(CALENDAR_YEAR|ROLLING_12M|LIFETIME)`.
- **TierBenefit** — `id, tier_id, type(EARN_MULTIPLIER|FREE_SHIPPING|DISCOUNT_PERCENT|EXCLUSIVE_CATALOG|CUSTOM), value, metadata(json)`.

### Redemption catalog
- **Reward** — `id, program_id, name, description, type(COUPON_CODE|GIFT_CARD|PRODUCT|DISCOUNT|DONATION|CUSTOM), points_cost, monetary_value?, image_url?, per_member_limit?, status, valid_from?, valid_to?, eligible_tier_ids?`.
- **RewardInventory** — `reward_id, total, remaining` (for limited stock).
- **RedemptionCode** — `id, reward_id, code, status(AVAILABLE|ASSIGNED|USED), assigned_redemption_id?` (pre-loaded code pools).
- **Redemption** — `id, org_id, member_id, reward_id, points_spent, status(PENDING|FULFILLED|CANCELLED|REFUNDED), fulfillment_payload(json), idempotency_key, requested_at, fulfilled_at?`.

### Badges
- **Badge** — `id, program_id, name, description, icon_url, criteria(json), repeatable(bool), points_reward?`.
- **MemberBadge** — `member_id, badge_id, earned_at, count`.

### Notifications / webhooks
- **Webhook** — `id, org_id, url, secret, subscribed_events[], active`.
- **WebhookDelivery** — `id, webhook_id, event_id, status, attempts, last_attempt_at, response_code`.
- **OutboxEvent** — `id, org_id, type, payload(json), created_at, published_at?` (write with the business txn, worker publishes).
- **NotificationTemplate** — (later) email/SMS templates per event.

### Common
- `BaseEntity { id, created_at, updated_at }`; `TenantEntity extends BaseEntity { org_id }`.

---

## 4. REST API (sketch)

### Business-facing (`/v1`, API-key auth)
- `POST /v1/members` · `GET /v1/members/{externalId}` — enroll / fetch member
- `GET  /v1/members/{externalId}/balance` — balances per currency
- `GET  /v1/members/{externalId}/transactions` — ledger history (paginated)
- `POST /v1/events` — ingest a business event `{ type, member: {externalId}, payload }` → engine evaluates earning rules + badges
- `POST /v1/members/{externalId}/points:adjust` — manual credit/debit (scoped)
- `GET  /v1/members/{externalId}/tier` — current tier + progress to next
- `GET  /v1/members/{externalId}/badges`
- `GET  /v1/rewards` — catalog visible to a member (tier-filtered)
- `POST /v1/redemptions` — `{ memberExternalId, rewardId }` → redeem
- `GET  /v1/redemptions/{id}` — redemption status / fulfilment payload

All mutations require `Idempotency-Key`. All responses are versioned; errors are `application/problem+json` (RFC 7807).

### Admin-facing (`/admin`, JWT auth)
- Auth: `POST /admin/auth/register-org`, `POST /admin/auth/login`, refresh
- API keys: list / create / revoke
- Program/currency/expiry config CRUD
- Earning rules + campaigns CRUD
- Tiers + benefits CRUD
- Catalog (rewards, inventory, code-pool upload) CRUD
- Badges CRUD
- Members: search, detail, manual adjust, view ledger
- Webhooks CRUD
- Analytics endpoints (issuance, redemption, liability, tier distribution, active members)
- Audit log query
- Team: invite OrgUser, change role

---

## 5. Admin dashboard (what businesses configure)

- **Onboarding**: create org → get TEST + LIVE API keys → quickstart.
- **Program**: currency name/code/decimals, expiry policy.
- **Earning rules builder**: pick event → build conditions visually → choose outcome. Test against a sample payload.
- **Campaigns**: schedule multipliers / bonuses over date ranges.
- **Tiers**: define levels, thresholds, qualification metric & period, benefits.
- **Catalog**: add rewards, set points cost & inventory & per-member limits, upload code pools, restrict by tier.
- **Badges**: define criteria, icon, repeatable, optional points reward.
- **Members**: search, view balance/ledger/tier/badges, manual adjustments.
- **Analytics**: points issued vs redeemed, **outstanding points liability** (monetary value of unredeemed points), active members, tier distribution, redemption rate, top rewards.
- **Webhooks**: register endpoints, pick events, view delivery log, rotate secret.
- **API keys**: manage TEST/LIVE keys & scopes.
- **Audit log** viewer.
- **Team**: invite admins, assign roles.

---

## 6. Background jobs (worker module, Phase 7+)

- **Points expiry** — daily sweep over `PointsLot`; emit `EXPIRE` ledger entries.
- **Tier recalculation** — on relevant events + scheduled full sweep.
- **Webhook delivery** — read outbox, deliver with exponential backoff, dead-letter after N attempts.
- **Balance reconciliation** — recompute from ledger, alert on drift.
- **Campaign activation/deactivation** — flip campaigns on schedule.
- **Analytics rollups** — pre-aggregate daily metrics for the dashboard.

---

## 7. Observability & ops

- Structured logging with `org_id` + `request_id` on every line.
- Metrics: events processed, rules evaluated, redemptions, webhook success rate, points liability, p95 latency on `/v1/events` and `/v1/.../balance`.
- Tracing across API → core → DB.
- Per-org rate limits & quotas tied to plan.
- **Flyway** migrations; `spring.jpa.hibernate.ddl-auto=validate` (never `update`) once real schema exists.
- CI: build + test + migration check on every PR.
- Tests: unit (ledger math, expiry FIFO, rule evaluation), integration with Testcontainers MySQL, API contract tests.

---

## 8. Scale considerations (don't build now, don't design against)

- Indexes from day one: `(org_id, external_id)` on member; `(org_id, member_id, currency_id)` on balance; `(org_id, member_id, created_at)` on ledger; `(org_id, idempotency_key)` unique.
- Hot paths: `POST /v1/events`, `GET /v1/.../balance` → Redis balance cache (invalidate on ledger write).
- Ledger growth → partition by `org_id` or by time later.
- Read replica for analytics queries.
- Outbox pattern for webhooks (already in the model) → reliable, no lost events.
- Data archival/retention policy for old ledger & delivery rows.
- Load-test hot paths before onboarding real volume.

---

## 9. Prioritized roadmap (build order)

### Phase 0 — Foundations
- [x] Restructure to monorepo multi-module Gradle: root project + `rewardhub-core` + `rewardhub-api`; existing app moved into `rewardhub-api` (main class at `com.rewardhub` root package); `settings.gradle` wired; shared config in root `build.gradle` via `subprojects {}`.
- [x] Scaffold `rewardhub-admin-ui` (React 19 + TS + Vite) as a Gradle module (`base` plugin + `npm` Exec tasks `npmInstall`/`npmBuild` — simpler than the node-gradle plugin, swap later if CI needs managed Node). Vite outputs to `build/dist`; `rewardhub-api:processResources` copies it into `static/` so `./gradlew build` bundles the SPA into the boot jar (served at `/`).
- [x] Add **Flyway** to `rewardhub-api` (`spring-boot-starter-flyway` + `flyway-mysql` — note: Boot 4.0 split Flyway auto-config out of `spring-boot-autoconfigure`); `spring.jpa.hibernate.ddl-auto=validate`; migrations live in `rewardhub-api/src/main/resources/db/migration`.
- [x] Stop tracking `application-local.properties` (`git rm --cached`; already gitignored; file stays on disk for local dev). NOTE: the plaintext password is still in older git commits — needs a history rewrite (BFG/`git filter-repo`) to fully purge.
- [x] `BaseEntity` (`Long` id, `Instant` created/updated via Spring Data JPA auditing — `@EnableJpaAuditing` in `JpaConfig`) and `TenantEntity` (+ `org_id`) in `rewardhub-core/common`.
- [x] `TenantContext` thread-local holder in `rewardhub-core/common`; `TenantContextFilter` in `rewardhub-api/web` clears it per request. (Populating it from the authenticated principal lands in Phase 1; full Hibernate `@Filter` / tenant-aware repo base also Phase 1.)
- [x] RFC 7807 error model: `DomainException` / `ResourceNotFoundException` / `ConflictException` in `rewardhub-core/exception`; `GlobalExceptionHandler` (`@RestControllerAdvice extends ResponseEntityExceptionHandler`) in `rewardhub-api/web`; `spring.mvc.problemdetails.enabled=true`.

**Phase 0 complete.** Build green (`./gradlew clean build`). Nothing committed yet.

### Phase 1 — Tenancy & auth
- [ ] `Organization`, `OrgUser` + migrations.
- [ ] Admin auth: register-org, login, JWT, roles.
- [ ] `ApiKey`: create/list/revoke, hashing, TEST/LIVE, scopes.
- [ ] API-key auth filter; cross-tenant access rejected (structurally).
- [ ] `AuditLog` + interceptor on config writes.

### Phase 2 — Points core (MVP)
- [ ] `RewardProgram`, `PointsCurrency`, `PointsExpiryPolicy`.
- [ ] `Member` (org_id + external_id), `MemberBalance`.
- [ ] `LedgerEntry` (append-only) + `PointsLot` (FIFO expiry).
- [ ] Idempotency store + middleware.
- [ ] Member API: enroll, fetch, balance, transactions.
- [ ] Manual `points:adjust` (scoped).
- [ ] Rebuild-balance-from-ledger admin tool.

### Phase 3 — Earning rules engine
- [ ] `EventType` catalog; `EarningRule` with JSON conditions.
- [ ] Rule evaluator (`all`/`any`/`not` + comparison ops; outcomes: fixed / rate-per-unit / percentage-of-field).
- [ ] `POST /v1/events` → evaluate rules → ledger entries.
- [ ] `Campaign` multipliers/bonuses layered on top; priority + per-member caps + validity windows.

### Phase 4 — Tiers
- [ ] `Tier`, `TierBenefit`, `MemberTier`.
- [ ] Qualification metrics + recalculation (on event + scheduled sweep).
- [ ] Tier earn-multiplier feeds back into the rules engine.
- [ ] Tier endpoints (current + progress).

### Phase 5 — Redemption catalog
- [ ] `Reward`, `RewardInventory`, `RedemptionCode` (code pools), `Redemption`.
- [ ] Catalog API (tier-filtered); redeem flow w/ idempotency + inventory locking.
- [ ] Fulfilment hooks (issue pooled code now; external gift-card APIs later).
- [ ] Cancel/refund → reversal ledger entries.

### Phase 6 — Badges / achievements
- [ ] `Badge` (criteria, repeatable, optional points reward), `MemberBadge`.
- [ ] Badge evaluation on events.
- [ ] Badge endpoints.

### Phase 7 — Webhooks & notifications
- [ ] `Webhook`, `WebhookDelivery`, `OutboxEvent`.
- [ ] Event publisher + delivery worker (backoff, dead-letter).
- [ ] Signature scheme + docs.

### Phase 8 — Admin dashboard UI
- [ ] React + TS scaffold; admin auth; org context.
- [ ] Onboarding + API key management.
- [ ] Program/currency/expiry config.
- [ ] Visual earning-rule builder + campaigns.
- [ ] Tier config; catalog management (incl. code-pool upload); badges.
- [ ] Member search/detail + manual adjust.
- [ ] Analytics dashboards (issuance, redemption, liability, tiers, active members).
- [ ] Audit log viewer; team/roles.

### Phase 9 — Scale & ops hardening
- [ ] Redis balance cache; per-key/plan rate limiting.
- [ ] Index & query review; ledger partitioning plan.
- [ ] Read replica for analytics.
- [ ] Metrics / tracing / dashboards; points-liability alerting.
- [ ] Background-job framework (expiry, tier sweep, rollups, outbox).
- [ ] Backfill/replay tooling; data archival.
- [ ] Load testing on hot paths.

### Cross-cutting (continuous)
- [ ] Keep OpenAPI docs current; client SDKs later.
- [ ] Unit + integration (Testcontainers) + API contract tests.
- [ ] CI: build, test, migration check.
- [ ] Versioned API (`/v1`).

---

## 10. Open questions to revisit

- One `RewardProgram` per org to start, or multi-program from day one?
- Rules: stay with structured-JSON conditions, or invest early in an expression language?
- Do businesses need member-facing UI components (widgets), or only the API?
- Gift-card / external fulfilment providers — which ones, and when?
- Pricing/plan model — drives quotas, rate limits, feature gating.
