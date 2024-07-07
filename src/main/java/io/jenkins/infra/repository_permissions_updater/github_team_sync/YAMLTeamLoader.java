package io.jenkins.infra.repository_permissions_updater.github_team_sync;
import org.yaml.snakeyaml.Yaml;
import java.io.FileInputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class YAMLTeamLoader {

    public static GithubTeamDefinition loadTeam(String filePath) {

        Path resolvedPath = Paths.get(filePath).normalize();

        if (!resolvedPath.startsWith(Paths.get("permissions"))) {
            throw new SecurityException("Attempted path traversal out of allowed directory");
        }

        if (!resolvedPath.toString().endsWith(".YAML")) {
            throw new SecurityException("Invalid file type");
        }

        if (!Files.exists(resolvedPath)) {
            throw new RuntimeException("File does not exist: " + resolvedPath);
        }

        // Load the file
        try (FileInputStream inputStream = new FileInputStream(resolvedPath.toFile())) {

            Yaml yaml = new Yaml();

            Map<String, Object> data = yaml.load(inputStream);
            String repoPath = "";
            String teamName = "";
            Set<String> developers = new HashSet<>();
            
            if (data.containsKey("github")) {
                repoPath = (String) data.get("github");
            }
            
            if (data.containsKey("github_team")) {
                teamName = (String) data.get("github_team");
            }

            
            if (data.containsKey("developers") && data.get("developers") != null) {
                List<String> devsList = (List<String>) data.get("developers");
                developers = new HashSet<>(devsList);
            }

            return new GithubTeamDefinition(repoPath,teamName,developers);
            

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to load: " + resolvedPath, e);
        }

        
    }
}
