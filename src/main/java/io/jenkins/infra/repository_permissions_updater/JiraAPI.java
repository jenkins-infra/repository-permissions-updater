package io.jenkins.infra.repository_permissions_updater;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

abstract public class JiraAPI implements Definition.IssueTracker.JiraComponentSource {

    public static final Logger LOGGER = Logger.getLogger(
            JiraAPI.class.getName()
    );

    /**
     * URL to Jira
     */
    private static final String JIRA_URL = System.getProperty("jiraUrl", "https://issues.jenkins.io");
    /**
     * URL to Jira components API
     */
    private static final String JIRA_COMPONENTS_URL = JIRA_URL + "/rest/api/2/project/JENKINS/components";
    /**
     * URL to Jira user API
     */
    private static final String JIRA_USER_URL = JIRA_URL + "/rest/api/2/user";

    public abstract String getComponentId(String componentName);

    abstract boolean isUserPresent(String username);

    /* Singleton support */
    private static JiraAPI INSTANCE = null;

    static synchronized JiraAPI getInstance(){
        if (INSTANCE == null) {
            INSTANCE = new JiraImpl();
        }
        return INSTANCE;
    }
    @SuppressFBWarnings(value = "URLCONNECTION_SSRF_FD", justification = "requests are necessary")
    private static class JiraImpl extends JiraAPI {

        private Map<String, String> componentNamesToIds = new HashMap<>();
        private Map<String, Boolean> userMapping = new HashMap<>();

        private void ensureDataLoaded()
        {
            if (!componentNamesToIds.isEmpty()) {
                return;
            }

            LOGGER.log(Level.INFO, "Retrieving components from Jira...");
            try {
                URL url = new URL(JIRA_COMPONENTS_URL);

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.connect();

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream() , StandardCharsets.UTF_8));
                StringBuilder response = new StringBuilder();

                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                Type ResponseType = new TypeToken
                        <List<Map<String, String>>>() {}.getType();

                Gson gson = new Gson();

                List<Map<String, String>> data = gson.fromJson(response.toString(), ResponseType);

                for (Map<String, String> map : data) {

                    String id = map.get("id");
                    String name = map.get("name");

                    LOGGER.log(Level.FINE,
                            String.format("Identified Jira component with ID '%s' and name '%s",
                                    id, name));

                    componentNamesToIds.put(name, id);

                }

            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        }

        @Override
        public String getComponentId(String componentName) {
            ensureDataLoaded();
            return componentNamesToIds.get(componentName);
        }

        @Override
        boolean isUserPresent(String username) {
            return userMapping.computeIfAbsent(username, u -> isUserPresentInternal(u) );
        }

        private static  boolean isUserPresentInternal(String username) {
            if (!username.matches("/[a-zA-Z0-9_]+/"))
            {
                LOGGER.log(Level.WARNING,
                        String.format("Rejecting user name for Jira lookup: %s", username));
                return false;
            }
            try {
                URL url = new URL(String.format(
                        "%s?username=%s", JIRA_USER_URL, username
                ));

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString((System.getenv("JIRA_USERNAME") + ':' + System.getenv("JIRA_PASSWORD")).getBytes(StandardCharsets.UTF_8)));

                conn.connect();

                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK)
                    return true;


            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            return false;
        }
    }





}
