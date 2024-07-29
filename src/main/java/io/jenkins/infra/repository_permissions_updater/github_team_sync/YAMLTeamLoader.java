package io.jenkins.infra.repository_permissions_updater.github_team_sync;

import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YAMLTeamLoader {
    private static final Logger logger = LoggerFactory.getLogger(YAMLTeamLoader.class);

    public static GithubTeamDefinition loadTeam(String filePath) {
        Path resolvedPath = resolveFilePath(filePath);
        Map<String, Object> teamConfig = loadYamlConfiguration(resolvedPath);
        return parseTeamDefinition(teamConfig);
    }

    private static Path resolveFilePath(String filePath) {
        Path basePath = Paths.get("permissions").toAbsolutePath().normalize();
        // Remove the prefix for GitHub actions
        if (filePath.startsWith("permissions/")) {
            filePath = filePath.substring("permissions/".length());
        }
        Path resolvedPath = basePath.resolve(filePath).normalize();

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

    private static GithubTeamDefinition parseTeamDefinition(Map<String, Object> teamConfig) {
        String repoPath = (String) teamConfig.getOrDefault("github", "");
        String teamName = (String) teamConfig.get("github_team");

        // Check if the team name is not provided or empty, then use the repo name
        if (teamName == null || teamName.trim().isEmpty()) {
            teamName = extractDefaultTeamName(repoPath);
        }

        Set<String> developers = extractDevelopers(teamConfig);
        return new GithubTeamDefinition(repoPath, teamName, developers);
    }

    private static String extractDefaultTeamName(String repoPath) {
        // Repository path format: org/repo
        String[] parts = repoPath.split("/");
        return parts.length > 1 ? parts[parts.length - 1] : "";
    }

    private static Set<String> extractDevelopers(Map<String, Object> teamConfig) {
        List<String> devsList = (List<String>) teamConfig.getOrDefault("developers", new HashSet<>());
        return new HashSet<>(devsList);
    }
}
