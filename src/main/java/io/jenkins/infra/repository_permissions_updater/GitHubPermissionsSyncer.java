package io.jenkins.infra.repository_permissions_updater;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

/**
 * Computes the difference between the desired GitHub team membership (declared in YAML, for components that
 * have opted in via {@code manageGitHubPermissions}/{@code manageGitHubTeam}) and the actual membership
 * currently on GitHub, writes it to a JSON report, and -- unless this instance is running in dry-run mode --
 * applies it by adding/removing the affected members.
 * <p>
 * Dry-run is controlled independently of the opt-in YAML flag, via the {@code dryRunMode} constructor
 * parameter (wired to the {@code githubPermissionsDryRun} system property by callers): {@code
 * manageGitHubPermissions: true} / {@code manageGitHubTeam: true} means "this component/team's GitHub
 * membership should be managed by RPU", while the dry-run flag independently controls whether that
 * management actually mutates GitHub or only computes and logs/reports what it would do. This lets the
 * trusted run exercise the real read+diff+apply code path safely (with real credentials, against real
 * teams) before switching dry-run off.
 * <p>
 * Only ever executes in the trusted, post-merge run -- see the project README for why PR/dry-run builds
 * cannot safely make any live GitHub API calls for this feature at all (a distinct, coarser gate than the
 * dry-run flag described above, which only matters once already inside the trusted run).
 */
public final class GitHubPermissionsSyncer {

    private static final Logger LOGGER = Logger.getLogger(GitHubPermissionsSyncer.class.getName());

    /**
     * Default GitHub organization assumed for cross-repository teams (teams/*.yml), which aren't themselves
     * tied to a single component's {@code github:} repository.
     */
    private static final String DEFAULT_ORGANIZATION = "jenkinsci";

    /**
     * Whether this instance only computes and logs/reports the diff ({@code true}), or also applies it by
     * adding/removing the affected members on GitHub ({@code false}).
     */
    private final boolean dryRunMode;

    public GitHubPermissionsSyncer(boolean dryRunMode) {
        this.dryRunMode = dryRunMode;
    }

    /**
     * One managed team's desired state: which GitHub organization it lives in, and which logins should be
     * members.
     */
    private static final class DesiredTeam {
        final String organization;
        final Set<String> logins = new TreeSet<>();

        DesiredTeam(String organization) {
            this.organization = organization;
        }
    }

    /**
     * Computes the desired vs. actual GitHub team membership diff for all opted-in components and teams,
     * writes it as JSON to {@code reportFile}, and, unless this instance is in dry-run mode, applies it by
     * adding/removing the affected members on GitHub. Equivalent to {@code sync(definitionsDir, teamsDir,
     * reportFile, true)}.
     *
     * @param definitionsDir directory containing component YAML definitions (see {@link Definition})
     * @param teamsDir directory containing cross-repository team YAML definitions (see {@link TeamDefinition})
     * @param reportFile file to write the JSON diff report to
     */
    public void sync(File definitionsDir, File teamsDir, File reportFile) throws IOException {
        sync(definitionsDir, teamsDir, reportFile, true);
    }

    /**
     * Computes the desired vs. actual GitHub team membership diff for all opted-in components and teams
     * and, unless this instance is in dry-run mode, applies it by adding/removing the affected members on
     * GitHub. {@code dryRunMode} (fixed for the lifetime of this instance, set via the constructor) and
     * {@code writeReport} are independent: {@code dryRunMode} controls whether GitHub is actually mutated,
     * while {@code writeReport} controls only whether the JSON diff report is written to {@code reportFile}
     * -- the diff is always computed (and, in dry-run, logged) regardless of {@code writeReport}.
     *
     * @param definitionsDir directory containing component YAML definitions (see {@link Definition})
     * @param teamsDir directory containing cross-repository team YAML definitions (see {@link TeamDefinition})
     * @param reportFile file to write the JSON diff report to; may be {@code null} if {@code writeReport} is
     *     {@code false}
     * @param writeReport if {@code true}, write the JSON diff report to {@code reportFile}; if {@code false},
     *     skip writing it (e.g. for callers that only care about the applied/logged result)
     */
    public void sync(File definitionsDir, File teamsDir, File reportFile, boolean writeReport) throws IOException {
        Map<String, Set<TeamDefinition>> teamsByName = ArtifactoryPermissionsUpdater.loadTeams(teamsDir);
        Map<String, DesiredTeam> desiredByTeamSlug = computeDesiredState(definitionsDir, teamsByName);

        if (desiredByTeamSlug.isEmpty()) {
            LOGGER.log(
                    Level.INFO,
                    "No components have opted into GitHub permissions management "
                            + "(manageGitHubPermissions/manageGitHubTeam); nothing to do");
            if (writeReport) {
                writeReport(reportFile, Map.of());
            }
            return;
        }

        Map<String, Set<String>> slugsByOrganization = new TreeMap<>();
        for (Map.Entry<String, DesiredTeam> entry : desiredByTeamSlug.entrySet()) {
            slugsByOrganization
                    .computeIfAbsent(entry.getValue().organization, unused -> new TreeSet<>())
                    .add(entry.getKey());
        }

        Map<String, Set<String>> actualBySlug = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : slugsByOrganization.entrySet()) {
            actualBySlug.putAll(GitHubTeamsAPI.getInstance().fetchTeamMembers(entry.getKey(), entry.getValue()));
        }

        Map<String, TeamDiff> diffsBySlug = new TreeMap<>();
        for (Map.Entry<String, DesiredTeam> entry : desiredByTeamSlug.entrySet()) {
            String slug = entry.getKey();
            DesiredTeam desired = entry.getValue();
            Set<String> actual = actualBySlug.getOrDefault(slug, Set.of());

            Set<String> toAdd = new TreeSet<>(desired.logins);
            toAdd.removeAll(actual);
            Set<String> toRemove = new TreeSet<>(actual);
            toRemove.removeAll(desired.logins);

            diffsBySlug.put(slug, new TeamDiff(desired.organization, toAdd, toRemove));
        }

        if (dryRunMode) {
            logDryRun(diffsBySlug);
        } else {
            applyDiffs(diffsBySlug);
        }

        if (writeReport) {
            writeReport(reportFile, diffsBySlug);
        }
    }

    /**
     * Logs a summary of what would be changed, without touching GitHub. Used when {@link #dryRunMode} is
     * {@code true}, so the trusted run's read+diff logic can be exercised safely before enabling apply.
     */
    private void logDryRun(Map<String, TeamDiff> diffsBySlug) {
        int toAdd = diffsBySlug.values().stream()
                .mapToInt(diff -> diff.toAdd().size())
                .sum();
        int toRemove = diffsBySlug.values().stream()
                .mapToInt(diff -> diff.toRemove().size())
                .sum();
        if (toAdd == 0 && toRemove == 0) {
            return;
        }
        LOGGER.log(
                Level.INFO,
                "GitHub permissions sync dry-run: would add {0} and remove {1} membership(s) across {2}"
                        + " team(s); no changes applied (set githubPermissionsDryRun=false to apply)",
                new Object[] {toAdd, toRemove, diffsBySlug.size()});
    }

    /**
     * Applies the computed diff: adds/removes the affected members via {@link GitHubTeamsAPI}. Failures for
     * an individual membership change are logged and recorded on that team's {@link TeamDiff#errors()} (so
     * they show up in the JSON report), but do not stop the run -- one bad membership change (e.g. a login
     * that no longer exists) shouldn't block reconciling every other opted-in team.
     */
    private void applyDiffs(Map<String, TeamDiff> diffsBySlug) {
        GitHubTeamsAPI api = GitHubTeamsAPI.getInstance();
        int added = 0;
        int removed = 0;
        int failures = 0;
        for (Map.Entry<String, TeamDiff> entry : diffsBySlug.entrySet()) {
            String slug = entry.getKey();
            TeamDiff diff = entry.getValue();
            List<String> addedLogins = new ArrayList<>();
            List<String> removedLogins = new ArrayList<>();
            for (String login : diff.toAdd()) {
                try {
                    api.addTeamMember(diff.organization(), slug, login);
                    added++;
                    addedLogins.add(login);
                } catch (IOException e) {
                    failures++;
                    String message = "Failed to add " + login + " to " + diff.organization() + "/" + slug;
                    diff.errors().add(message + ": " + e.getMessage());
                    LOGGER.log(Level.WARNING, message, e);
                }
            }
            for (String login : diff.toRemove()) {
                try {
                    api.removeTeamMember(diff.organization(), slug, login);
                    removed++;
                    removedLogins.add(login);
                } catch (IOException e) {
                    failures++;
                    String message = "Failed to remove " + login + " from " + diff.organization() + "/" + slug;
                    diff.errors().add(message + ": " + e.getMessage());
                    LOGGER.log(Level.WARNING, message, e);
                }
            }
            if (!addedLogins.isEmpty() || !removedLogins.isEmpty()) {
                LOGGER.log(Level.INFO, "GitHub permissions sync: {0}/{1}: added {2}, removed {3}", new Object[] {
                    diff.organization(), slug, addedLogins, removedLogins
                });
            }
        }
        LOGGER.log(
                Level.INFO,
                "GitHub permissions sync: added {0}, removed {1} membership(s) across {2} team(s), {3}" + " failure(s)",
                new Object[] {added, removed, diffsBySlug.size(), failures});
    }

    private static Map<String, DesiredTeam> computeDesiredState(
            File definitionsDir, Map<String, Set<TeamDefinition>> teamsByName) throws IOException {
        Map<String, DesiredTeam> desiredByTeamSlug = new TreeMap<>();

        // Cross-repository teams (teams/*.yml) that have opted in.
        for (Set<TeamDefinition> definitions : teamsByName.values()) {
            for (TeamDefinition team : definitions) {
                if (!team.isManageGitHubTeam()) {
                    continue;
                }
                Set<String> logins = resolveGitHubLogins(team.getDeveloperIds(), team.getGitHubUsernames());
                logins.addAll(team.getGitHubOnlyUsernames());
                if (logins.isEmpty()) {
                    continue;
                }
                mergeDesired(desiredByTeamSlug, slugify(team.getName()), DEFAULT_ORGANIZATION, logins);
            }
        }

        // Per-component repository teams that have opted in.
        Yaml yaml = new Yaml(new Constructor(Definition.class, new LoaderOptions()));
        for (File file : Objects.requireNonNull(definitionsDir.listFiles())) {
            if (!file.getName().endsWith(".yml")) {
                continue;
            }
            Definition definition;
            try (InputStream is = Files.newInputStream(file.toPath())) {
                definition = yaml.loadAs(is, Definition.class);
            } catch (Exception e) {
                throw new IOException("Failed to read " + file.getName(), e);
            }
            if (definition == null) {
                continue;
            }

            if (!definition.isManageGitHubPermissions()) {
                continue;
            }
            String repo = definition.getGithub();
            if (repo == null) {
                // Already rejected by ArtifactoryPermissionsUpdater's static validation; be defensive here too.
                LOGGER.log(
                        Level.WARNING,
                        "Skipping {0}: manageGitHubPermissions requires a GitHub repository",
                        file.getName());
                continue;
            }
            String organization = repo.substring(0, repo.indexOf('/'));
            String repoName = repo.substring(repo.indexOf('/') + 1);
            String repositoryTeam = definition.getRepositoryTeam();
            String teamName = repositoryTeam != null ? repositoryTeam : repoName + " Developers";

            Set<String> logins = resolveGitHubLogins(definition.getDeveloperIds(), definition.getGitHubUsernames());
            logins.addAll(definition.getGitHubOnlyUsernames());
            mergeDesired(desiredByTeamSlug, slugify(teamName), organization, logins);
        }

        return desiredByTeamSlug;
    }

    private static void mergeDesired(
            Map<String, DesiredTeam> desiredByTeamSlug, String slug, String organization, Set<String> logins) {
        DesiredTeam team = desiredByTeamSlug.computeIfAbsent(slug, unused -> new DesiredTeam(organization));
        team.logins.addAll(logins);
    }

    /**
     * Resolves the desired GitHub login for each entry in {@code developers}: by default, a developer's
     * Jenkins community (LDAP) id is assumed to also be their GitHub login; {@code githubUsernameOverrides}
     * (an LDAP id -&gt; GitHub login map, see {@link Definition#getGitHubUsernames()}/
     * {@link TeamDefinition#getGitHubUsernames()}) can override this for the rare case where they differ.
     */
    static Set<String> resolveGitHubLogins(String[] developers, Map<String, String> githubUsernameOverrides) {
        Set<String> logins = new TreeSet<>();
        for (String developer : developers) {
            if (developer == null || developer.isBlank()) {
                continue;
            }
            String login = githubUsernameOverrides.getOrDefault(developer, developer);
            if (login != null && !login.isBlank()) {
                logins.add(login);
            }
        }
        return logins;
    }

    /**
     * Converts a GitHub team display name (e.g. {@code "example-plugin Developers"}) into the slug GitHub
     * derives for it (e.g. {@code "example-plugin-developers"}): lower-cased, with runs of whitespace and
     * other non-alphanumeric characters collapsed to single hyphens.
     */
    static String slugify(String teamName) {
        String slug = teamName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return slug.replaceAll("^-+|-+$", "");
    }

    /**
     * One team's diff for this run: which logins should be added/removed to reconcile desired vs. actual
     * state, and (populated only when applying, i.e. not dry-run) any per-membership-change errors
     * encountered -- kept mutable (a plain {@link ArrayList}) so {@link #applyDiffs(Map)} can record
     * failures against the same {@code TeamDiff} instance that gets serialized into the report.
     */
    private record TeamDiff(String organization, Set<String> toAdd, Set<String> toRemove, List<String> errors) {
        TeamDiff(String organization, Set<String> toAdd, Set<String> toRemove) {
            this(organization, toAdd, toRemove, new ArrayList<>());
        }
    }

    private void writeReport(File reportFile, Map<String, TeamDiff> diffsBySlug) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        JsonObject root = new JsonObject();
        for (Map.Entry<String, TeamDiff> entry : diffsBySlug.entrySet()) {
            TeamDiff diff = entry.getValue();
            JsonObject teamJson = new JsonObject();
            teamJson.addProperty("organization", diff.organization());
            teamJson.addProperty("dryRun", dryRunMode);
            teamJson.add("toAdd", toJsonArray(diff.toAdd()));
            teamJson.add("toRemove", toJsonArray(diff.toRemove()));
            if (!diff.errors().isEmpty()) {
                teamJson.add("errors", toJsonArray(diff.errors()));
            }
            root.add(entry.getKey(), teamJson);
        }

        if (reportFile.getParentFile() != null) {
            Files.createDirectories(reportFile.getParentFile().toPath());
        }
        try (Writer writer = Files.newBufferedWriter(reportFile.toPath(), StandardCharsets.UTF_8)) {
            gson.toJson(root, writer);
        }
        LOGGER.log(Level.INFO, "Wrote GitHub permissions diff report for {0} team(s) to {1}", new Object[] {
            diffsBySlug.size(), reportFile
        });
    }

    private static JsonArray toJsonArray(Collection<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
