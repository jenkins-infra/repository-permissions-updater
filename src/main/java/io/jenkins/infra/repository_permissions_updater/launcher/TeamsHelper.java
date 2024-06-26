package io.jenkins.infra.repository_permissions_updater.launcher;

import io.jenkins.infra.repository_permissions_updater.TeamDefinition;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class TeamsHelper {

    private static final Yaml YAML_TEAM_DEFINITION = new Yaml(new Constructor(TeamDefinition.class, new LoaderOptions()));
    private static final String YAML_FILE_EXTENSION = "%s.yml";

    /**
     * Loads a team file from yaml
     * @param teamFile to load
     * @return a team definition
     */
    static TeamDefinition loadTeam(Path teamFile) {
        try {
            var td = YAML_TEAM_DEFINITION.loadAs(Files.newBufferedReader(teamFile, StandardCharsets.UTF_8), TeamDefinition.class);
            td.setFilePath(teamFile);
            return td;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + teamFile.getFileName().toString(), e);
        }
    }

    /**
     * Checks if the file name the same as the expected team name
     * @param teamDefinition to check
     * @return true if the file name expected and false if not
     */
    static Boolean expectedTeamName(TeamDefinition teamDefinition) {
        var result = YAML_FILE_EXTENSION.formatted(teamDefinition.getName()).equals(teamDefinition.getFilePath().getFileName().toString());
        if (!result) {
            throw new RuntimeException("Team file should be named " + YAML_FILE_EXTENSION.formatted(teamDefinition.getName()) + " instead of the current " + teamDefinition.getFilePath().getFileName().toString());
        }
        return result;
    }
}
