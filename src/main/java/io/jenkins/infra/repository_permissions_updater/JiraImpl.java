package io.jenkins.infra.repository_permissions_updater;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class JiraImpl extends JiraAPI {
    private static final Logger LOGGER = LoggerFactory.getLogger(JiraImpl.class);
    private static final Gson gson = new Gson();
    private static final Pattern USERNAME_REGEX = Pattern.compile("[a-zA-Z0-9_]+");
    private static final String JIRA_USERNAME = System.getenv("JIRA_USERNAME");
    private static final String JIRA_PASSWORD = System.getenv("JIRA_PASSWORD");
    private static final String JIRA_BASIC_AUTH_VALUE = Base64.getEncoder().encodeToString((JIRA_USERNAME + ":" + JIRA_PASSWORD).getBytes(StandardCharsets.UTF_8));
    private static final String JIRA_BASIC_AUTH_HEADER = "Basic %s";

    /**
     * URL to Jira components API
     */
    private final String JIRA_COMPONENTS_URL;
    private final String JIRA_USE_URL;
    /**
     * URL to Jira user API
     */
    private final String JIRA_USER_QUERY;
    private Map<String, String> componentNamesToIds;
    private final Map<String, Boolean> userMapping = new HashMap<>();


    JiraImpl(String jiraUrl) {
        JIRA_COMPONENTS_URL = jiraUrl + "/rest/api/2/project/JENKINS/components";
        JIRA_USE_URL = jiraUrl + "/rest/api/2/user";
        JIRA_USER_QUERY = JIRA_USE_URL + "?username=%s";
    }

    @SuppressFBWarnings({"SE_NO_SERIALVERSIONID", "URLCONNECTION_SSRF_FD"})
    private void ensureDataLoaded() throws IOException {
        if (componentNamesToIds == null) {
            componentNamesToIds = new HashMap<>();

            LOGGER.info("Retrieving components from Jira...");
            final URL url;
            try {
                url = URI.create(JIRA_COMPONENTS_URL).toURL();
            } catch (final MalformedURLException e) {
                throw new IOException("Failed to construct Jira URL", e);
            }
            final HttpURLConnection conn;
            try {
                conn = (HttpURLConnection) url.openConnection();
            } catch (final IOException e) {
                throw new IOException("Failed to open connection for Jira URL", e);
            }

            try {
                conn.setRequestMethod("GET");
            } catch (final ProtocolException e) {
                throw new IOException("Failed to set request method", e);
            }
            try {
                conn.connect();
            } catch (IOException e) {
                throw new IOException("Failed to connect to Jira URL", e);
            }
            try (final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                final String text = bufferedReader.lines().collect(Collectors.joining());
                final JsonArray jsonElements = gson.fromJson(text, JsonArray.class);
                jsonElements.forEach(jsonElement -> {
                    final var object = jsonElement.getAsJsonObject();
                    final var id = object.get("id").getAsString();
                    final var name = object.get("name").getAsString();
                    LOGGER.trace("Identified Jira component with ID {} and name {}", id, name);
                    componentNamesToIds.put(name, id);
                });
            } catch (final IOException e) {
                throw new IOException("Failed to parse Jira response", e);
            }
        }
    }
    @Override
    public String getComponentId(final String componentName) throws IOException {
        ensureDataLoaded();
        return componentNamesToIds.get(componentName);
    }

    @Override
    public boolean isUserPresent(final String username) {
        return userMapping.computeIfAbsent(username, this::isUserPresentInternal);
    }
    @SuppressFBWarnings({"URLCONNECTION_SSRF_FD"})
    private boolean isUserPresentInternal(final String username) throws RuntimeException {
        if (!USERNAME_REGEX.matcher(username).matches()) {
            LOGGER.warn(String.format("Rejecting user name for Jira lookup: %s", username));
            return false;
        }

        LOGGER.info("Checking whether user exists in Jira: {}", username);

        final URL url;
        try {
            url = URI.create(String.format(JIRA_USER_QUERY, username)).toURL();
        } catch (final MalformedURLException e) {
            throw new RuntimeException("Failed to construct Jira URL", e);
        }
        final HttpURLConnection conn;
        try {
            conn = (HttpURLConnection) url.openConnection();
        } catch (final IOException e) {
            throw new RuntimeException("Failed to open connection for Jira URL", e);
        }

        try {
            conn.setRequestMethod("GET");
        } catch (final ProtocolException e) {
            throw new RuntimeException("Failed to set request method", e);
        }
        conn.setRequestProperty("Authorization", String.format(JIRA_BASIC_AUTH_HEADER, JIRA_BASIC_AUTH_VALUE));
        try {
            conn.connect();
        } catch (final IOException e) {
            throw new RuntimeException("Failed to connect to Jira URL", e);
        }

        final int code;
        try {
            code = conn.getResponseCode();
        } catch (final IOException e) {
            return false;
        }
        return code == 200;
    }
}
