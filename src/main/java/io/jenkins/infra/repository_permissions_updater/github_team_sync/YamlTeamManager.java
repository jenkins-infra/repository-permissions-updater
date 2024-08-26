package io.jenkins.infra.repository_permissions_updater.github_team_sync;

import org.kohsuke.github.GHTeam;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Loads and parses YAML configurations into RepoTeamDefinition objects with security validations.
 */

public class YamlTeamManager {
    private static final Logger logger = LoggerFactory.getLogger(YamlTeamManager.class);

    // Defines paths for permission and team YAML files
    private static final Path PERMISSIONS_PATH = Paths.get("permissions").toAbsolutePath().normalize();
    private static final Path TEAMS_PATH = Paths.get("teams").toAbsolutePath().normalize();

    private static Path resolvedPath;
    private static Map<String, Object> teamConfig;

    public YamlTeamManager(GitHubService gitHubService, String filePath) throws IOException {
        YamlTeamManager.resolvedPath = resolveFilePath(filePath);
        YamlTeamManager.teamConfig = loadYamlConfiguration(YamlTeamManager.resolvedPath);
    }

    public static Object loadTeam(String filePath) throws IOException {
        Path resolvedPath = resolveFilePath(filePath);
        Map<String, Object> teamConfig = loadYamlConfiguration(resolvedPath);

        if (filePath.startsWith("permissions/")) {
            return parsePermissionsTeamDefinition(teamConfig);
        } else if (filePath.startsWith("teams/")) {
            return parseTeamsTeamDefinition(teamConfig);
        } else {
            throw new IllegalArgumentException("Unsupported file path: " + filePath);
        }
    }

    // Resolves and secures a YAML file path, protecting against path traversal attacks.
    private static Path resolveFilePath(String filePath) {
        Path basePath = filePath.startsWith("permissions/") ? PERMISSIONS_PATH : TEAMS_PATH;
        Path resolvedPath = basePath.resolve(filePath.replaceFirst("^(permissions/|teams/)", "")).normalize();

        if (!resolvedPath.startsWith(basePath)) {
            throw new SecurityException("Attempted path traversal out of allowed directory");
        }
        if (!resolvedPath.toString().endsWith(".yml")) {
            throw new SecurityException("Invalid file type");
        }
        if (!Files.exists(resolvedPath)) {
            throw new RuntimeException("File does not exist: " + resolvedPath);
        }
        return resolvedPath;
    }


    private static Map<String, Object> loadYamlConfiguration(Path path) {
        try (FileInputStream inputStream = new FileInputStream(path.toFile())) {
            Yaml yaml = new Yaml();
            return yaml.load(inputStream);
        } catch (Exception e) {
            logger.error("Failed to load YAML configuration: {}", path, e);
            throw new RuntimeException("Failed to load YAML configuration: " + path, e);
        }
    }

    private static RepoTeamDefinition parsePermissionsTeamDefinition(
            Map<String, Object> teamConfig) throws IOException {

        // Extract the repo name and org name from the GitHub key
        // e.g. github: &GH "jenkinsci/commons-lang3-api-plugin"
        // orgName = jenkinsci, repoName = commons-lang3-api-plugin
        String repoPath = (String) teamConfig.getOrDefault("github", "");
        String[] parts = repoPath.split("/");
        String orgName = parts[0];
        String repoName = parts[1];

        // Extract the team name and role
        // Note: repository_team should always be admin
        // e.g. repository_team: design-library-plugin-developers
        // teamName = design-library-plugin-developers, teamRole = admin
        String repoTeamName = (String) teamConfig.get("repository_team");

        // Extract the developers, example:
        // developers:
        //  - "user1"
        //  - "user2"
        Set<String> developers = extractDevelopers(teamConfig);

        // If developers is not empty, then team name is required
        if ((repoTeamName == null || repoTeamName.trim().isEmpty()) && !developers.isEmpty()) {
            throw new IllegalArgumentException("No valid team name found.");
        }


        // Extract the additional GitHub teams, which can range from zero to any number, example:
        // additional_github_teams:
        //  - name: sig-ux
        //    role: admin
        Set<AdditionalTeamDefinition> additionalTeams = new HashSet<>();
        Map<String, Object> additionalTeamsInfo = (Map<String, Object>) teamConfig.get("additional_github_teams");
        if (additionalTeamsInfo != null) {
            List<Map<String, Object>> groups = (List<Map<String, Object>>) additionalTeamsInfo;
            for (Map<String, Object> group : groups) {
                String name = (String) group.get("name");
                String role = (String) group.get("role");
                try {
                    additionalTeams.add(new AdditionalTeamDefinition(name, role));
                } catch (IllegalArgumentException e) {
                    // if the role is invalid, log an error and skip adding this team
                    logger.error("Invalid role {} for team {}: {}", role, name, e.getMessage());
                }
            }
        }
        return new RepoTeamDefinition(repoName, orgName, repoTeamName, developers, additionalTeams);
    }

    private static SpecialTeamDefinition parseTeamsTeamDefinition(Map<String, Object> teamConfig) throws IOException {
        String teamName = (String) teamConfig.getOrDefault("github_team", "");
        Set<String> developers = extractDevelopers(teamConfig);

        if (teamName == null || teamName.trim().isEmpty()) {
            // If developers is not empty, then team name is required
            if (!developers.isEmpty()) {
                throw new IllegalArgumentException("No valid team name found.");
            }
        }

        return new SpecialTeamDefinition(null, teamName, developers);
    }

    private static Set<String> extractDevelopers(Map<String, Object> teamConfig) {
        List<String> devsList = (List<String>) teamConfig.getOrDefault(
                "developers", new HashSet<>());
        return new HashSet<>(devsList);
    }


}
