package io.jenkins.infra.repository_permissions_updater.github_team_sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;


import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;

import java.io.IOException;

import java.util.Set;


@ExtendWith(MockitoExtension.class)
public class TeamUpdaterTest {
    @Mock
    private GitHubService gitHubService;

    @Mock
    private GHOrganization org;

    @Mock
    private GHRepository repo;

    @Mock
    private GHTeam ghTeam;

    private TeamUpdater teamUpdater;

    @BeforeEach
    public void setUp() {
        teamUpdater = new TeamUpdater(gitHubService);
    }

    @Test
    public void testUpdateTeamCreatesNewTeam() throws IOException {
        Set<String> developers = Set.of("dev1", "dev2"); // Example set of developers
        Set<AdditionalTeamDefinition> additionalTeams = Set.of(
                new AdditionalTeamDefinition("teamA", "maintain"),
                new AdditionalTeamDefinition("teamB", "read")
        );
        RepoTeamDefinition team = new RepoTeamDefinition("example-repo", "jenkins", "example-repo-devs", developers, additionalTeams);
        when(gitHubService.getOrganization(anyString())).thenReturn(org);
        when(org.getRepository(anyString())).thenReturn(repo);
        when(org.getTeamByName(anyString())).thenReturn(null); // Team does not exist
        when(gitHubService.createTeam(anyString(), anyString(), any())).thenReturn(ghTeam);

        teamUpdater.updateTeam(team);

        verify(gitHubService).createTeam(eq("jenkins"), eq("example-repo-devs"), eq(GHTeam.Privacy.CLOSED));
        //verify(ghTeam).add(eq(repo), any(GHOrganization.RepositoryRole.class));
    }
}
