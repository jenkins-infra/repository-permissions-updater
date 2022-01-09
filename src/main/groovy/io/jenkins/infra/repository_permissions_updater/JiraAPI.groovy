package io.jenkins.infra.repository_permissions_updater

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.util.logging.Level
import java.util.logging.Logger

@SuppressFBWarnings("LI_LAZY_INIT_STATIC") // Something related to Groovy
abstract class JiraAPI implements Definition.IssueTracker.JiraComponentSource {
    public static final Logger LOGGER = Logger.getLogger(JiraAPI.class.getName())
    /**
     * URL to Jira
     */
    private static final String JIRA_URL = System.getProperty('jiraUrl', 'https://issues.jenkins.io')
    /**
     * URL to Jira components API
     */
    private static final String JIRA_COMPONENTS_URL = JIRA_URL + '/rest/api/2/project/JENKINS/components'
    /**
     * URL to Jira user API
     */
    private static final String JIRA_USE_URL = JIRA_URL + '/rest/api/2/user'

    abstract String getComponentId(String componentName);

    abstract boolean isUserPresent(String username);

    /* Singleton support */
    private static JiraAPI INSTANCE = null
    static synchronized JiraAPI getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new JiraImpl()
        }
        return INSTANCE
    }

    private static class JiraImpl extends JiraAPI {

        private Map<String, String> componentNamesToIds

        private Map<String, Boolean> userMapping = new HashMap<>()

        @SuppressFBWarnings("SE_NO_SERIALVERSIONID")
        private void ensureDataLoaded() {
            if (componentNamesToIds == null) {
                componentNamesToIds = new HashMap<>();

                LOGGER.log(Level.INFO, "Retrieving components from Jira...")
                URL url = new URL(JIRA_COMPONENTS_URL)
                HttpURLConnection conn = (HttpURLConnection) url.openConnection()

                conn.setRequestMethod('GET')
                conn.connect()
                String text = conn.getInputStream().getText()

                def json = new JsonSlurper().parseText(text)

                json.each {
                    def id = it.id
                    def name = it.name
                    LOGGER.log(Level.FINE, "Identified Jira component with ID '${id}' and name '${name}'")
                    componentNamesToIds.put(name, id)
                }
            }
        }

        @Override
        String getComponentId(String componentName) {
            ensureDataLoaded()
            return componentNamesToIds.get(componentName)
        }

        @Override
        boolean isUserPresent(String username) {
            return userMapping.computeIfAbsent(username, { u -> isUserPresentInternal(u) })
        }

        private static boolean isUserPresentInternal(String username) {
            if (!username.matches(/[a-zA-Z0-9_]+/)) {
                LOGGER.log(Level.WARNING, "Rejecting user name for Jira lookup: " + username)
                return false // Do not allow unusual user names, protect from any shenanigans
            }

            LOGGER.log(Level.INFO, "Checking whether user exists in Jira: " + username)

            URL url = new URL(JIRA_USE_URL + "?username=${username}")
            HttpURLConnection conn = (HttpURLConnection) url.openConnection()

            conn.setRequestMethod('GET')
            conn.setRequestProperty('Authorization', 'Basic ' + Base64.getEncoder().encodeToString((System.getenv("JIRA_USERNAME") + ':' + System.getenv("JIRA_PASSWORD")).getBytes(StandardCharsets.UTF_8)))
            conn.connect()

            def code = conn.getResponseCode()
            if (code == 200) {
                return true
            }
            return false
        }
    }
}
