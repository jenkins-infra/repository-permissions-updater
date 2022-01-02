package io.jenkins.infra.repository_permissions_updater.hosting;

import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.domain.BasicComponent;
import com.atlassian.jira.rest.client.api.domain.Project;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;
import io.atlassian.util.concurrent.Promise;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.jenkins.infra.repository_permissions_updater.hosting.HostingConfig.JIRA_PASSWORD;
import static io.jenkins.infra.repository_permissions_updater.hosting.HostingConfig.JIRA_URL;
import static io.jenkins.infra.repository_permissions_updater.hosting.HostingConfig.JIRA_USERNAME;

public class JiraHelper {

    static JiraRestClient createJiraClient() {
        return new AsynchronousJiraRestClientFactory().createWithBasicHttpAuthentication(
                URI.create(JIRA_URL), JIRA_USERNAME, JIRA_PASSWORD);
    }

    static boolean close(JiraRestClient client) {
        try {
            if (client != null) {
                client.close();
            }
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    /**
     * Waits till the completion of the synchronized command.
     * @param <T> Type of the promise
     * @param promise Ongoing operation
     * @return Operation result
     * @throws InterruptedException Operation interrupted externally
     * @throws ExecutionException Execution failure
     * @throws TimeoutException Timeout
     */
    static <T> T wait(Promise<T> promise)
            throws InterruptedException, ExecutionException, TimeoutException {
        return promise.get(30, TimeUnit.SECONDS);
    }

    static BasicComponent getBasicComponent(JiraRestClient client, String projectId, String componentName)
            throws ExecutionException, TimeoutException, InterruptedException, IOException {
        Project project = wait(client.getProjectClient().getProject(projectId));
        for (BasicComponent component : project.getComponents()) {
            if (component.getName().equals(componentName)) {
                return component;
            }
        }
        throw new IOException("Unable to find component " + componentName + " in the " + projectId + " issue tracker");
    }

}
