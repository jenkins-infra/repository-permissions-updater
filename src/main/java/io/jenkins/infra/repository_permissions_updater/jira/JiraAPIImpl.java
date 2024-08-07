package io.jenkins.infra.repository_permissions_updater.jira;

import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.UserRestClient;
import com.atlassian.jira.rest.client.api.domain.Project;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

class JiraAPIImpl implements JiraAPI {

    private static final Logger LOGGER = Logger.getLogger(JiraAPIImpl.class.getName());
    private static final String JIRA_URL = System.getProperty("jiraUrl", "https://issues.jenkins.io");
    private static final String JIRA_PROJECT = System.getProperty("JIRA_PROJECT", "JENKINS");
    private static JiraAPI INSTANCE;

    private final Map<String, Boolean> userMapping = new HashMap<>();
    private final Map<String, Long> componentNamesToIds = new HashMap<>();

    private JiraAPIImpl() {}


    private void ensureDataLoaded() {
        if (componentNamesToIds.isEmpty()) {
            LOGGER.log(Level.INFO, "Retrieving components from Jira...");
            final String jiraUsername = System.getProperty("JIRA_USERNAME");
            final String jiraPassword = System.getProperty("JIRA_PASSWORD");
            try (final JiraRestClient jiraRestClient = new AsynchronousJiraRestClientFactory()
                    .createWithBasicHttpAuthentication(URI.create(JIRA_URL), jiraUsername, jiraPassword)) {
                final Project project = jiraRestClient.getProjectClient().getProject(JIRA_PROJECT).claim();
                project.getComponents().forEach(basicComponent -> {
                    final Long id = basicComponent.getId();
                    final String name = basicComponent.getName();
                    LOGGER.log(Level.FINE, "Identified Jira component with ID '%d' and name '%s'", new Object[]{id, name});
                    componentNamesToIds.put(name, id);
                });

            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Something went wrong at retrieving the components from jira", e);
            }
        }
    }

    @Override
    public Long getComponentId(final String componentName) {
        ensureDataLoaded();
        return componentNamesToIds.getOrDefault(componentName, -1L);
    }

    @Override
    public boolean isUserPresent(final String username) {
        return userMapping.computeIfAbsent(username, this::isUserPresentInternal);
    }

    private boolean isUserPresentInternal(final String username) {
        final String jiraUsername = System.getProperty("JIRA_USERNAME");
        final String jiraPassword = System.getProperty("JIRA_PASSWORD");
        boolean found;
        try (final JiraRestClient jiraRestClient = new AsynchronousJiraRestClientFactory()
                .createWithBasicHttpAuthentication(URI.create(JIRA_URL), jiraUsername, jiraPassword)) {
            final UserRestClient userClient = jiraRestClient.getUserClient();
            userClient.getUser(jiraUsername).claim();
            found = true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Something went wrong at retrieving the internal user from jira", e);
            found = false;
        }
        return found;
    }

    static synchronized JiraAPI getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new JiraAPIImpl();
        }
        return INSTANCE;
    }
}
