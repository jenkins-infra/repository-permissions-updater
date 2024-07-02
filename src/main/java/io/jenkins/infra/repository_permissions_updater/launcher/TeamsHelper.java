package io.jenkins.infra.repository_permissions_updater.launcher;

import io.jenkins.infra.repository_permissions_updater.Definition;
import io.jenkins.infra.repository_permissions_updater.TeamDefinition;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class TeamsHelper {

    private static final Yaml YAML_TEAM_DEFINITION = new Yaml(new Constructor(TeamDefinition.class, new LoaderOptions()));
    private static final String YAML_FILE_EXTENSION = "%s.yml";
    private static final Path TEAMS_DIR = Path.of("teams");
    private static final Predicate<String> DEVELOPER_START_WITH = s -> s.startsWith("@");

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
            throw new RuntimeException("Failed to read " + teamFile, e);
        }
    }

    /**
     * Checks if the file name the same as the expected team name
     * @param teamDefinition to check
     * @return true if the file name expected and false if not
     */
    static Boolean expectedTeamName(TeamDefinition teamDefinition) {
        var path = teamDefinition.getFilePath();
        var name = teamDefinition.getName();
        if (path == null || name == null)  return false;
        var fileName = path.getFileName();
        if (fileName == null) return true;
        var result = YAML_FILE_EXTENSION.formatted(name).equalsIgnoreCase(fileName.toString());
        if (!result) {
            throw new RuntimeException("Team file should be named " + YAML_FILE_EXTENSION.formatted(name) + " instead of the current " + path.getFileName());
        }
        return result;
    }

    /**
     * Loads all teams from the teams/ folder.
     * Always returns non null.
     */
    static Map<String, TeamDefinition> loadTeams() throws Exception {
        Set<TeamDefinition> teamsResult;
        try(var teams = Files.list(TEAMS_DIR)) {
            teamsResult = teams.map(TeamsHelper::loadTeam)
                    .filter(TeamsHelper::expectedTeamName).collect(Collectors.toSet());
        }
        return teamsResult.stream().collect(Collectors.toMap(TeamDefinition::getName, Function.identity()));
    }

    /**
     * Checks if any developer has its name starting with `@`.
     * In which case, for `@some-team` it will replace it with the developers
     * listed for the team whose name equals `some-team` under the teams/ directory.
     */
    static void expandTeams(Definition definition, Map<String, TeamDefinition> teamsByName) {
        try (var developers = Arrays.stream(definition.getDevelopers())) {
            var extendDevelopers = developers.filter(DEVELOPER_START_WITH).map(s -> {
                var team = teamsByName.get(s.substring(1));
                ArtifactoryPermissionsUpdater.LOGGER.log(Level.INFO, "[" + definition.getName() + "]: replacing " + s + " with " + String.join(",", Arrays.asList(team.getDevelopers())));
                return (Set<String>) new HashSet<>(Arrays.asList(team.getDevelopers()));
            }).reduce((strings, strings2) -> Stream.concat(strings.stream(), strings2.stream()).collect(Collectors.toSet()));
            var result = new HashSet<>(Arrays.asList(definition.getDevelopers()));
            extendDevelopers.ifPresent(result::addAll);
            definition.setDevelopers(result.toArray(String[]::new));
        }
    }
}
