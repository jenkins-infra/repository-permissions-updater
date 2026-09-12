# Rollout plan: GitHub permissions management via RPU

This document describes how GitHub team/repository permissions management (see the "Managing GitHub
Permissions" section of [README.md](README.md)) will actually be rolled out across the `jenkinsci` org
(2000+ repositories, ~2600 teams), and how the design keeps GitHub API usage bounded as adoption grows.

Everything below builds on what's already merged: an opt-in schema (`manageGitHubPermissions` /
`manageGitHubTeam`), static (network-free) YAML validation on every PR, and a **report-only** diff generator
(`GitHubPermissionsSyncer`) that runs once every ~2 hours on trusted.ci and publishes
`json/github-permissions-diff.json`. No GitHub team membership is mutated today.

## Why not Terraform (or another general-purpose IaC tool)?

Terraform (with the `integrations/github` provider) is the obvious "codify GitHub as infrastructure"
answer, and jenkins-infra already uses Terraform elsewhere, so it's worth explaining explicitly why this
project doesn't adopt it for GitHub team/repo permissions. This isn't about credentials/trust boundaries —
Terraform can follow the same `plan` on PR / `apply` on trusted post-merge split RPU already uses. The
deciding factor is **performance at this specific scale (2000+ repos, ~2600 teams)**, where Terraform's
execution model is a poor match:

- **Terraform refreshes its entire managed state before every plan, not just what changed.** By default,
  `terraform plan`/`apply` re-reads the current state of *every* resource under management to detect drift,
  regardless of how small the underlying config change is. A single-line YAML edit to one plugin's
  `developers` list would still trigger a full read of every other opted-in repo/team's current GitHub state
  in the same run. `-target` can narrow this, but Hashicorp documents it as an exceptional/debugging flag,
  not something to build a routine CI pipeline on — and computing a safe target list per PR is itself extra
  engineering with no upstream support. RPU's diff generator, by contrast, only ever reads the specific teams
  whose YAML is present in `permissions/`/`teams/` — cost scales with the opted-in set, not with "everything
  Terraform happens to have in state."
- **The GitHub Terraform provider is REST-based (built on `go-github`), not GraphQL, and doesn't batch.**
  Resources like team membership are generally one API call per resource (or per team, for set-based
  membership resources) during refresh — there's no built-in equivalent to the alias-batched GraphQL query
  this project's `GitHubTeamsAPIImpl` uses to fetch up to 50 teams' full membership in a **single** HTTP
  request. At full adoption (~2600 teams), that's the difference between roughly 52 requests per run (RPU,
  see [Scaling](#scaling-to-2000-repos-minimizing-api-calls)) and on the order of thousands of individual
  REST calls per full-state refresh with the stock provider — a ~50x+ difference in API cost for the same
  read, before even counting writes.
- **That refresh cost is paid on every run, including the trusted cron that runs every ~2 hours regardless of
  whether anything changed.** A few thousand REST calls per refresh, times 12 runs/day, would consume a
  meaningful fraction of GitHub's primary rate limit purely on drift-detection overhead — compared to RPU's
  bounded ~52 requests/run (~624/day at full adoption), which leaves over an order of magnitude of headroom
  with no further optimization needed.
- **State size and plan/apply wall-clock time grow linearly with total managed resources, not with what
  changed.** Modelling per-team membership as individual Terraform resources across thousands of repos means
  the state file, and the time to refresh/diff/serialize it, keeps growing as adoption approaches 100% —
  even for a run triggered by one small PR. RPU has no persisted state to grow: each run computes a diff
  transiently from a live GraphQL read and a git-diff of the changed YAML, so its cost is a function of the
  opted-in set size, not accumulated historical state.
- **Getting Terraform to batch the way this project needs would mean writing a custom provider/data source
  anyway** — at which point the actual GraphQL-batching engineering work (the part that makes this scale to
  2000+ repos) is identical to what's already built in `GitHubTeamsAPIImpl`; Terraform would only be adding a
  state-management and HCL layer on top of that same code, without a performance benefit to justify it.

This isn't a rejection of Terraform in general — it remains a good fit where jenkins-infra already uses it
(e.g. provisioning cloud infrastructure, where resource counts don't scale with "one file per external
contributor" and full-state refreshes are cheap because there simply isn't that much to refresh). It's
specifically the wrong tool for reconciling thousands of small, high-churn GitHub team-membership resources
on a tight API budget.

## Goals for this phase

1. Prove the diff report is accurate against real data with zero risk (nothing is written to GitHub).
2. Get a handful of real plugins opted in and get hosting-team sign-off on the diffs before writing any code
   that mutates memberships.
3. Design the eventual write path and the org-wide adoption path so that neither one requires an unbounded
   number of GitHub API calls as the opted-in set grows toward "all 2000+ repos".

## Stage 0 — Pilot (in progress)

- `permissions/plugin-slack.yml` is opted in (`manageGitHubPermissions: true`) as the first pilot.
- Every trusted run computes and publishes a diff for this one repo/team. No mutations.
- Hosting team reviews `json/github-permissions-diff.json` manually for a couple of weeks to confirm:
  - the desired vs. actual computation matches reality (spot-check against the GitHub UI),
  - the report correctly reflects additions/removals when `plugin-slack.yml` is edited,
  - GraphQL calls stay cheap and reliable (see [Scaling](#scaling-to-2000-repos-minimizing-api-calls) below).
- Exit criteria: hosting team explicitly approves moving to Stage 1.

## Stage 1 — Expand opt-in to a small, representative batch (~20-50 repos)

- Ask a handful of plugin maintainers (mix of high-traffic and low-traffic plugins, at least one with a
  cross-repo `teams/*.yml` team) to opt in via PR.
- No tooling change needed for this — it's the same schema, just more files setting the flag.
- Track diff-report stability across a few runs per repo (a "flapping" diff, e.g. members appearing/
  disappearing between runs, would indicate a bug and must be fixed before Stage 2).
- Exit criteria: diffs are stable and correct across this batch for at least a week of runs.

## Stage 2 — Bulk opt-in tooling

Manually editing 2000+ YAML files is not viable. Before broad adoption we need a one-off migration helper
(a script, not part of the production sync path) that:

1. Reads `https://reports.jenkins.io/github-jenkinsci-permissions-report.json` (existing GitHub↔LDAP mapping
   report) plus the current `permissions/*.yml`/`teams/*.yml` developer lists.
2. For each file, proposes:
   - `manageGitHubPermissions: true` (or `manageGitHubTeam: true` for `teams/*.yml`),
   - upgrading plain-string `developers` entries to `{ldap, github}` mappings only where the report shows a
     GitHub login that **differs** from the LDAP id (leaving matching entries as plain strings, since the
     sync already assumes `ldap == github` by default — see README).
3. Opens the changes as a small number of batched PRs (e.g. per-letter or per-N-files, not one giant PR),
   so each is independently reviewable and revertable, and so any one bad diff doesn't block the rest.
4. Runs **entirely off the existing static report JSON** — it does not call the GitHub API itself, so this
   step adds no additional GitHub load regardless of how many repos it touches.

This is a scripted, supervised, one-time batch job — not something that runs unattended in CI.

## Stage 3 — Enable mutations (writes), still gated and staged

Once the diff report has been trustworthy across Stage 1/2 for a sustained period:

1. **Done.** The write path is implemented: `GitHubTeamsAPIImpl#addTeamMember`/`#removeTeamMember` call the
   GitHub REST API (`PUT`/`DELETE /orgs/{org}/teams/{team}/memberships/{login}`, always role `member`, never
   `maintainer`), reusing the existing bot token already used for repo creation/hosting — no new credential.
2. Rather than a **second** opt-in YAML flag, `manageGitHubPermissions`/`manageGitHubTeam` directly mean
   "actively manage (i.e. reconcile) this component's/team's GitHub membership" — there is only one YAML
   opt-in flag. Whether a given trusted run actually *applies* the computed diff, or only computes and
   reports/logs it, is controlled independently by the `githubPermissionsDryRun` system property passed to
   the trusted run (`-DgithubPermissionsDryRun=true|false`), which **defaults to `true`** (safe/report-only).
   This means every currently-opted-in repo can be re-verified end-to-end (real reads, real diff, real
   report) with zero write risk, right up until the org deliberately flips `githubPermissionsDryRun=false`
   for the trusted run — at which point *all* currently opted-in repos start being reconciled for real, not
   just newly-added ones. This is simpler than a second flag and avoids repos silently drifting between
   "reported on" and "actually managed" states independent of their own YAML.
3. Safety guards before any write is issued:
   - removing the **last** member of a team is allowed and expected — an adopted/orphaned plugin can
     legitimately end up with zero maintainers (e.g. the previous maintainer stepped away and `developers`
     was correctly emptied), and org owners always retain the ability to restore access to any repo/team
     regardless of its member count. This is not treated as an error case.
   - no cap on the number/percentage of removals performed in a single run per team — the diff is always
     derived directly from the YAML in the merged commit, so a large removal simply reflects a large,
     reviewed change to `developers`; there's no separate "unexpected" state to guard against beyond normal
     PR review.
   - a mutation failure for one login/team (e.g. a login that no longer exists, or a team that was deleted)
     is caught per-mutation, logged, and recorded in that team's `errors` in the JSON report — it does not
     abort reconciliation of any other opted-in team/login in the same run.
   - every mutation is logged; the diff report (which also records `dryRun` and any `errors`) remains the
     audit trail.
4. Before flipping `githubPermissionsDryRun=false` for the first time, re-review the current diff report for
   every already-opted-in repo (starting with the Stage 0 pilot, `plugin-slack`) to confirm it still looks
   as expected, then flip the flag and watch closely for a few cycles.
5. Broaden opt-in over time as plugin maintainers/hosting team choose to adopt it — this remains **opt-in per
   repo indefinitely** via `manageGitHubPermissions`/`manageGitHubTeam`; there is no planned "flip everyone
   over" cutover. `githubPermissionsDryRun`, once disabled, applies to all opted-in repos uniformly — it is a
   single global switch for the trusted run, not a per-repo setting.

## Stage 4 — Steady state

- Adoption grows organically as maintainers add `manageGitHubPermissions`/`manageGitHubTeam` to their
  `permissions/*.yml`/`teams/*.yml` (new plugins can opt in from day one via the hosting request template).
- Hosting team spot-checks the diff report periodically; alerts (see below) catch anomalies between checks.
- The manual GitHub-permissions request issue template
  (`.github/ISSUE_TEMPLATE/5-github-permissions.yml`) is updated to point maintainers at self-service YAML
  instead of a manual hosting-team action, once confidence is high.

## Scaling to 2000+ repos: minimizing API calls

The design keeps GitHub API usage roughly proportional to the **opted-in set**, not the org size, and keeps
each run's call count low even as that set grows:

- **Opt-in scope, not org-wide scans.** `GitHubPermissionsSyncer` only queries teams/repos where
  `manageGitHubPermissions`/`manageGitHubTeam` is `true`. A repo that hasn't opted in costs zero GitHub API
  calls. This is the single biggest lever — the org has ~2600 teams total, but only opted-in teams are ever
  queried.
- **GraphQL batching via aliased sub-queries.** `GitHubTeamsAPIImpl` doesn't do one REST call per team.
  It builds one GraphQL query containing up to `BATCH_SIZE = 50` aliased `team(slug: ...) { members { ... } }`
  sub-selections, so 50 teams cost **1 HTTP request**, not 50. At full adoption (2600 teams), that's ~52
  requests total per run, comfortably inside GitHub's primary rate limit (5000 pts/hr for a GitHub App /
  authenticated request) and the GraphQL secondary/node-count limits (each request currently asks for at
  most 50 teams × 100 members = 5000 nodes, well under the 500,000-node ceiling).
- **Members fetched in the same request as the team lookup.** There's no separate "list teams" call followed
  by N "list members" calls — membership comes back in the same aliased sub-query, so cost doesn't multiply
  with team size.
- **Per-team member cap is a non-issue in practice, not a scaling concern.** `MAX_MEMBERS_PER_TEAM = 100`
  members are fetched per team per request (no pagination implemented). No team in the org is expected to
  ever approach 100 members — per-repo "Developers" teams are almost always single digits, and even the
  largest cross-repo teams (e.g. core-maintainer teams) are nowhere near that size — so this cap is simply
  headroom, not a real limitation that needs revisiting as part of this rollout.
- **Bounded batch count regardless of adoption growth.** Request count per run is `ceil(opted_in_teams / 50)`.
  Even if every one of the ~2600 teams in the org opted in simultaneously, that's ~52 GraphQL requests per
  run — not 2000+ (one per repo) and not 2600+ (one per team). This is the property that makes "scale to
  2000+ repos" tractable: the call count scales with `teams / 50`, not with `repos` or `members` directly.
- **Runs are infrequent and off the hot path.** The sync only runs on the trusted, post-merge cron (every 2
  hours, see `Jenkinsfile`), not per-PR and not per-webhook. PR builds do zero GitHub-permissions API calls
  (only static YAML validation), so the 2000+ contributor-facing builds per day never touch this API at all.
  Only the trusted run touches GitHub, and it does so at most every 2 hours regardless of how many repos are
  opted in.
- **Retry/backoff, not busy-polling.** `GitHubTeamsAPIImpl.postGraphQl` retries transient failures (up to 3
  attempts with a short fixed delay) instead of re-querying eagerly or falling back to slower REST loops, so
  transient GitHub issues don't multiply call volume.
- **Failure isolation.** The diff generation is wrapped in try/catch in `ArtifactoryPermissionsUpdater`, so a
  GitHub API problem (rate limit, outage) fails the diff step only — it doesn't block or retry the
  Artifactory sync, and doesn't retry-storm GitHub on the next run beyond the normal 2-hour cadence.
- **When writes are added (Stage 3),** the same batching principle applies: additions/removals should be
  issued as the minimal set of mutating calls derived from the diff (one `add`/`remove` REST call is
  unavoidable per membership change — GraphQL has no bulk-mutation for team membership — but the number of
  mutations per run is bounded by how much actually changed since the last run, which in steady state is
  small). No polling loops, no re-deriving full state more than once per run.

### Rough capacity math (worst case: 100% adoption)

- Teams to query: ~2600 (all teams, if every repo + cross-repo team opts in).
- GraphQL requests per run: `ceil(2600 / 50)` = 52.
- Runs per day: 24h / 2h cadence = 12.
- GraphQL requests per day at full adoption: 52 × 12 = **624/day**, vs. a 5000-point/hour primary limit
  (60,000+/day) — over an order of magnitude of headroom even at 100% adoption, without any further
  optimization.

This means no additional scaling work is required to reach full org adoption; the existing batching design
already accounts for the 2000+ repo scale.

## Monitoring / guardrails carried into rollout

- `json/github-permissions-diff.json` is published every trusted run (already implemented) — this is the
  primary visibility mechanism during Stage 0-2.
- Before Stage 3 (writes), add: a run-over-run diff-size alert (e.g. if the number of planned changes in a
  single run is anomalously large compared to the previous run, hold and notify rather than auto-apply) —
  this is the main defense against a schema bug or bad PR silently mass-editing GitHub team membership.
- Keep the "opt-in per repo" model permanently, even post-Stage 3 — this bounds the blast radius of any bug
  to the repos that have explicitly adopted the feature, no matter how mature it becomes.

## Explicitly out of scope for this rollout

- CLI commands for developer search/removal (jenkins-infra/repository-permissions-updater#4755/#4759) — separate effort.
- Repo-level (non-team) permission diffing for `additionalGitHubTeams` role grants — diff report currently
  only reconciles team *membership*, not repo-to-team permission-level grants; follow-up once membership
  sync is proven out.
- Any org-wide, non-opt-in scan or migration — adoption is and remains per-repo opt-in.
