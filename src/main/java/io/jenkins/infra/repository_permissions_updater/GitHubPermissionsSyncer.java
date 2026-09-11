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
import java.util.HashMap;
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
 * have opted in via {@code manageGithubPermissions}/{@code manageGithubTeam}) and the actual membership
 * currently on GitHub, and writes it to a JSON report.
 * <p>
 * This is deliberately <b>report-only</b>: no GitHub team membership is ever added or removed by this class.
 * The report is meant to be reviewed by the hosting team during a bake-in period before any reconciliation
 * (actual add/remove of members) is implemented as a follow-up.
 * <p>
 * Only runs against the opt-in managed set (never the whole org), and only ever executes in the trusted,
 * post-merge run -- see the project README for why PR/dry-run builds cannot safely make any live GitHub API
 * calls for this feature.
 */
public final class GitHubPermissionsSyncer {

    private static final Logger LOGGER = Logger.getLogger(GitHubPermissionsSyncer.class.getName());

    /**
     * Default GitHub organization assumed for cross-repository teams (teams/*.yml), which aren't themselves
     * tied to a single component's {@code github:} repository.
     */
    private static final String DEFAULT_ORGANIZATION = "jenkinsci";

    private GitHubPermissionsSyncer() {}

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
     * and writes it as JSON to {@code reportFile}.
     *
     * @param definitionsDir directory containing component YAML definitions (see {@link Definition})
     * @param teamsDir directory containing cross-repository team YAML definitions (see {@link TeamDefinition})
     * @param reportFile file to write the JSON diff report to
     */
    public static void generateDiffReport(File definitionsDir, File teamsDir, File reportFile) throws IOException {
        Map<String, Set<TeamDefinition>> teamsByName = ArtifactoryPermissionsUpdater.loadTeams(teamsDir);
        Map<String, DesiredTeam> desiredByTeamSlug = computeDesiredState(definitionsDir, teamsByName);

        if (desiredByTeamSlug.isEmpty()) {
            LOGGER.log(
                    Level.INFO,
                    "No components have opted into GitHub permissions management "
                            + "(manageGithubPermissions/manageGithubTeam); nothing to report");
            writeReport(reportFile, Map.of());
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

        writeReport(reportFile, diffsBySlug);
    }

    private static Map<String, DesiredTeam> computeDesiredState(
            File definitionsDir, Map<String, Set<TeamDefinition>> teamsByName) throws IOException {
        Map<String, DesiredTeam> desiredByTeamSlug = new TreeMap<>();

        // Cross-repository teams (teams/*.yml) that have opted in.
        for (Set<TeamDefinition> definitions : teamsByName.values()) {
            for (TeamDefinition team : definitions) {
                if (!team.isManageGithubTeam()) {
                    continue;
                }
                Set<String> logins = resolveGithubLogins(team.getDeveloperIds(), team.getGithubUsernames());
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

            if (!definition.isManageGithubPermissions()) {
                continue;
            }
            String repo = definition.getGithub();
            if (repo == null) {
                // Already rejected by ArtifactoryPermissionsUpdater's static validation; be defensive here too.
                LOGGER.log(
                        Level.WARNING,
                        "Skipping {0}: manageGithubPermissions requires a GitHub repository",
                        file.getName());
                continue;
            }
            String organization = repo.substring(0, repo.indexOf('/'));
            String repoName = repo.substring(repo.indexOf('/') + 1);
            String repositoryTeam = definition.getRepositoryTeam();
            String teamName = repositoryTeam != null ? repositoryTeam : repoName + " Developers";

            Set<String> logins = resolveGithubLogins(definition.getDeveloperIds(), definition.getGithubUsernames());
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
     * (an LDAP id -&gt; GitHub login map, see {@link Definition#getGithubUsernames()}/
     * {@link TeamDefinition#getGithubUsernames()}) can override this for the rare case where they differ.
     */
    static Set<String> resolveGithubLogins(String[] developers, Map<String, String> githubUsernameOverrides) {
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

    private record TeamDiff(String organization, Set<String> toAdd, Set<String> toRemove) {}

    private static void writeReport(File reportFile, Map<String, TeamDiff> diffsBySlug) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        JsonObject root = new JsonObject();
        for (Map.Entry<String, TeamDiff> entry : diffsBySlug.entrySet()) {
            TeamDiff diff = entry.getValue();
            JsonObject teamJson = new JsonObject();
            teamJson.addProperty("organization", diff.organization());
            teamJson.add("toAdd", toJsonArray(diff.toAdd()));
            teamJson.add("toRemove", toJsonArray(diff.toRemove()));
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

    private static JsonArray toJsonArray(Set<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
