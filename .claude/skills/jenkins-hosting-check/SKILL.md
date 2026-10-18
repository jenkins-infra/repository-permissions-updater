---
name: jenkins-hosting-check
description: Runs the Jenkins hosting checks against a new plugin before a hosting request is opened in jenkins-infra/repository-permissions-updater, and fixes what they report. Use when preparing a Jenkins plugin for hosting in the jenkinsci GitHub organization, when asked to check a plugin is ready to be hosted, to run the hosting checks or hosting checker locally, or when a hosting request got a "needs-fix" comment from the Jenkins Hosting Checker.
---

# Jenkins hosting check

The Jenkins hosting team only reviews a hosting request after the automated Hosting Checker passes.
Running the same checks before opening the request avoids rounds of fix-and-re-check on the issue.

The checks read the plugin from GitHub, not from the local working tree, so **local changes must be committed and pushed** to the default branch of the repository being hosted before each run.

## 1. Gather the inputs

Work from the plugin's repository root.

| Option | Where to get it |
|---|---|
| `--repository-url` | `https://github.com/<owner>/<repo>` of the repository to host. Derive it from `git remote get-url origin`; it must be `https://`, with no trailing `.git`. |
| `--new-repository-name` | `<artifactId>-plugin`, using `<artifactId>` from `pom.xml`. All lowercase, hyphenated, must not contain `jenkins`. Defaults to the current repository name if omitted. |
| `--github-user` | GitHub users to get commit access, comma separated. `gh api user --jq .login` gives the current user. |
| `--jenkins-user` | Jenkins community account names (accounts.jenkins.io) to get release permission, comma separated. These are **not** GitHub handles and cannot be derived — ask the user if they have not said. |
| `--no-cd` | Only if the user does not want automated releases via GitHub Actions (JEP-229 CD). CD is the default and recommended. |

## 2. Get the checker

The checker lives in `jenkins-infra/repository-permissions-updater`. If this skill was installed as a symlink, the checkout it points into is the one to use: resolve it with `realpath ~/.agents/skills/jenkins-hosting-check` or `realpath ~/.claude/skills/jenkins-hosting-check` (the checkout is three directories up). Otherwise keep one outside the plugin repository:

```shell
RPU="${RPU_HOME:-$HOME/.cache/jenkins-repository-permissions-updater}"
if [ -d "$RPU/.git" ]; then
  git -C "$RPU" pull --ff-only --quiet && export REBUILD=1
else
  git clone --depth 1 https://github.com/jenkins-infra/repository-permissions-updater.git "$RPU"
fi
```

`REBUILD=1` after a pull makes sure the latest checks are used. Requirements: Java 25+ and Maven. The script finds a Java 25 JDK through `JAVA_HOME`, the `PATH`, or `/usr/libexec/java_home` on macOS; if none is found, tell the user to install one rather than editing the checker.

A GitHub token is required (anonymous rate limits run out mid-check). The script uses `GITHUB_OAUTH`, then `GITHUB_TOKEN`, then `gh auth token`. No scopes are needed.

## 3. Run it

```shell
"$RPU/bin/check-hosting.sh" \
  --repository-url https://github.com/<owner>/<repo> \
  --new-repository-name <artifactId>-plugin \
  --github-user <github-user> \
  --jenkins-user <jenkins-account>
```

The first run builds the checker, which takes a minute or two. Each check then takes about half a minute. The report is printed at the end, after the log lines.

Exit codes:

- `0`: no Required issues. Warning and Info items are recommendations.
- `1`: Required issues found. They must be fixed before opening the hosting request.
- `2`: no Java 25+ or no GitHub token. Fix the environment.
- `3`: no Required issues, but some checks threw errors (named in the report, details in the log). Usually a network or token problem; re-run.

## 4. Fix and re-run

Each item in the report names what to change, often with a link to a template in `jenkinsci/archetypes`. Fetch those templates rather than writing the files from memory.

Fix Warning and Info items too where they apply: they are what hosting reviewers most often ask for (unneeded dependencies, missing proxy support, hard coded colors, outdated README sections). Some are heuristics, so when one does not apply, tell the user why instead of changing code just to silence it.

Issues that the plugin code cannot fix, so report them to the user instead:

- **Jenkins account has not logged into Jira or Artifactory**: the user must log in once at https://issues.jenkins.io and https://repo.jenkins-ci.org with that account. The reports re-sync hourly, so the check keeps failing for up to an hour afterwards.
- **Invalid GitHub user**: a typo, or an organization rather than a user.
- **Forked from, or already forked into, jenkinsci**: the user must detach the fork (GitHub support or "Leave fork network") or explain the existing repository in the hosting request.
- **`target/` or `work/` in git history**: fixing this rewrites history (e.g. `git filter-repo --path target --path work --invert-paths`) and needs a force push. Get the user's agreement before doing it.

After fixing, commit, push, and run the check again. Repeat until it exits `0`.

## 5. Open the hosting request

When the check passes, the user opens a hosting request at https://github.com/jenkins-infra/repository-permissions-updater/issues/new?template=1-hosting-request.yml with the same values used above, and ticks the box confirming the hosting checks were run locally. The hosting team still reviews things the checker cannot, such as the code and README content.
