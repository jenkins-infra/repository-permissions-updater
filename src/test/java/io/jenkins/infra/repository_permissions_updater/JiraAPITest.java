package io.jenkins.infra.repository_permissions_updater;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.jenkins.infra.repository_permissions_updater.helper.HttpUrlStreamHandler;
import io.jenkins.infra.repository_permissions_updater.helper.MemoryAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URL;
import java.net.URLStreamHandlerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
class JiraAPITest {

    private static MemoryAppender memoryAppender;
    private static HttpUrlStreamHandler httpUrlStreamHandler;

    private static final Supplier<String> JIRA_URL = () -> System.getProperty("jiraUrl", "https://issues.jenkins.io");
    private static final  Supplier<String> JIRA_USE_URL = () -> JIRA_URL.get() + "/rest/api/2/user";
    private static final  Supplier<String> JIRA_USER_QUERY = () -> JIRA_USE_URL.get() + "?username=%s";
    private static final Supplier<String> JIRA_COMPONENTS_URL = () -> JIRA_URL.get() + "/rest/api/2/project/JENKINS/components";
    private static URLStreamHandlerFactory urlStreamHandlerFactory;
    private Properties backup;


    @BeforeAll
    public static void setup() {
        Logger logger = (Logger) LoggerFactory.getLogger(JiraImpl.class);
        memoryAppender = new MemoryAppender();
        memoryAppender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        logger.setLevel(Level.DEBUG);
        logger.addAppender(memoryAppender);
        memoryAppender.start();

        urlStreamHandlerFactory = mock(URLStreamHandlerFactory.class);
        httpUrlStreamHandler = new HttpUrlStreamHandler();
        when(urlStreamHandlerFactory.createURLStreamHandler("http")).thenReturn(httpUrlStreamHandler);
        when(urlStreamHandlerFactory.createURLStreamHandler("https")).thenReturn(httpUrlStreamHandler);
        URL.setURLStreamHandlerFactory(urlStreamHandlerFactory);
    }

    @BeforeEach
    public void reset() {
        backup = new Properties();
        backup.putAll(System.getProperties());

        JiraAPI.INSTANCE = null;

        httpUrlStreamHandler.resetConnections();
    }



    @AfterEach
    void restore() {
        System.setProperties(backup);
    }

    @Test
    void testEnsureLoadedWrongBaseUrl() {
        System.setProperty("jiraUrl", "xx://issues.jenkins.io");
        JiraAPI.getInstance().getComponentId("FakeData");
        assertThat(memoryAppender.contains("Retrieving components from Jira...", Level.INFO)).isTrue();
        assertThat(memoryAppender.contains("Failed to construct Jira URL", Level.ERROR)).isTrue();
    }

    @Test
    void testEnsureLoadedFailedToOpenConnection() throws IOException {
        URL fakeUrl = spy(URI.create(JIRA_COMPONENTS_URL.get()).toURL());
        httpUrlStreamHandler.addConnection(fakeUrl, new IOException());
        JiraAPI.getInstance().getComponentId("FakeData");
        assertThat(memoryAppender.contains("Retrieving components from Jira...", Level.INFO)).isTrue();
        assertThat(memoryAppender.contains("Failed to open connection for Jira URL", Level.ERROR)).isTrue();
    }

    @Test
    void testEnsureLoadedFailedToGetConnection() throws IOException {
        URL fakeUrl = spy(URI.create(JIRA_COMPONENTS_URL.get()).toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        doThrow(ProtocolException.class).when(fakeHttpConnection).setRequestMethod(anyString());
        httpUrlStreamHandler.addConnection(fakeUrl, fakeHttpConnection);
        JiraAPI.getInstance().getComponentId("FakeData");
        assertThat(memoryAppender.contains("Retrieving components from Jira...", Level.INFO)).isTrue();
        assertThat(memoryAppender.contains("Failed to set request method", Level.ERROR)).isTrue();
    }

    @Test
    void testEnsureLoadedFailedToConnect() throws IOException {
        URL fakeUrl = spy(URI.create(JIRA_COMPONENTS_URL.get()).toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        doThrow(IOException.class).when(fakeHttpConnection).connect();
        httpUrlStreamHandler.addConnection(fakeUrl, fakeHttpConnection);
        JiraAPI.getInstance().getComponentId("FakeData");
        assertThat(memoryAppender.contains("Retrieving components from Jira...", Level.INFO)).isTrue();
        assertThat(memoryAppender.contains("Failed to connect to Jira URL", Level.ERROR)).isTrue();
    }

    @Test
    void testEnsureLoadedFailedToParse() throws IOException {
        URL fakeUrl = spy(URI.create(JIRA_COMPONENTS_URL.get()).toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        when(fakeHttpConnection.getInputStream()).thenThrow(IOException.class);
        httpUrlStreamHandler.addConnection(fakeUrl, fakeHttpConnection);
        JiraAPI.getInstance().getComponentId("FakeData");
        assertThat(memoryAppender.contains("Retrieving components from Jira...", Level.INFO)).isTrue();
        assertThat(memoryAppender.contains("Failed to parse Jira response", Level.ERROR)).isTrue();
    }

    @Test
    void testEnsureLoadedSuccessToParse() throws IOException {
        URL fakeUrl = spy(URI.create(JIRA_COMPONENTS_URL.get()).toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        when(fakeHttpConnection.getInputStream()).thenReturn(Files.newInputStream(Path.of("src","test","resources", "components_12_07_2024.json")));
        httpUrlStreamHandler.addConnection(fakeUrl, fakeHttpConnection);
        var id = JiraAPI.getInstance().getComponentId("42crunch-security-audit-plugin");
        assertThat(memoryAppender.contains("Retrieving components from Jira...", Level.INFO)).isTrue();
        assertEquals("27235", id);
    }

    @Test
    void testIsUserPresentInternalRegexDontMatch() {
        var result = JiraAPI.getInstance().isUserPresent("FakeUser**");
        assertThat(memoryAppender.contains("Rejecting user name for Jira lookup", Level.WARN)).isTrue();
        Assertions.assertFalse(result);
    }

    @Test
    void testIsUserPresentInternalMalformedUrlException() {
        System.setProperty("jiraUrl", "xx://issues.jenkins.io");
        var result = JiraAPI.getInstance().isUserPresent("FakeUser");
        assertThat(memoryAppender.contains("Checking whether user exists in Jira", Level.INFO)).isTrue();
        assertThat(memoryAppender.contains("Failed to construct Jira URL", Level.ERROR)).isTrue();
        Assertions.assertFalse(result);
    }


    @Test
    void testIsUserPresentInternalFailedToOpenConnection() throws IOException {
        URL fakeUrl = spy(URI.create(String.format(JIRA_USER_QUERY.get(), "FakeUser")).toURL());
        httpUrlStreamHandler.addConnection(fakeUrl, new IOException());
        var result = JiraAPI.getInstance().isUserPresent("FakeUser");
        assertThat(memoryAppender.contains("Checking whether user exists in Jira", Level.INFO)).isTrue();
        assertThat(memoryAppender.contains("Failed to open connection for Jira URL", Level.ERROR)).isTrue();
        Assertions.assertFalse(result);
    }

    @Test
    void testIsUserPresentInternalFailedToGetConnection() throws IOException {
        URL fakeUrl = spy(URI.create(String.format(JIRA_USER_QUERY.get(), "FakeUser")).toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        doThrow(ProtocolException.class).when(fakeHttpConnection).setRequestMethod(anyString());
        httpUrlStreamHandler.addConnection(fakeUrl, fakeHttpConnection);
        var result = JiraAPI.getInstance().isUserPresent("FakeUser");
        assertThat(memoryAppender.contains("Checking whether user exists in Jira", Level.INFO)).isTrue();
        assertThat(memoryAppender.contains("Failed to set request method", Level.ERROR)).isTrue();
        Assertions.assertFalse(result);
    }

    @Test
    void testIsUserPresentInternalFailedToConnect() throws IOException {
        URL fakeUrl = spy(URI.create(String.format(JIRA_USER_QUERY.get(), "FakeUser")).toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        doThrow(IOException.class).when(fakeHttpConnection).connect();
        httpUrlStreamHandler.addConnection(fakeUrl, fakeHttpConnection);
        var result = JiraAPI.getInstance().isUserPresent("FakeUser");
        assertThat(memoryAppender.contains("Checking whether user exists in Jira", Level.INFO)).isTrue();
        assertThat(memoryAppender.contains("Failed to connect to Jira URL", Level.ERROR)).isTrue();
        Assertions.assertFalse(result);
    }

    @Test
    void testIsUserPresentInternalNotExistsException() throws IOException {
        URL fakeUrl = spy(URI.create(String.format(JIRA_USER_QUERY.get(), "FakeUser")).toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        when(fakeHttpConnection.getResponseCode()).thenThrow(IOException.class);
        httpUrlStreamHandler.addConnection(fakeUrl, fakeHttpConnection);
        var result = JiraAPI.getInstance().isUserPresent("FakeUser");
        assertThat(memoryAppender.contains("Checking whether user exists in Jira", Level.INFO)).isTrue();
        Assertions.assertFalse(result);
    }

    @Test
    void testIsUserPresentInternalNotExists() throws IOException {
        URL fakeUrl = spy(URI.create(String.format(JIRA_USER_QUERY.get(), "FakeUser")).toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        when(fakeHttpConnection.getResponseCode()).thenReturn(404);
        httpUrlStreamHandler.addConnection(fakeUrl, fakeHttpConnection);
        var result = JiraAPI.getInstance().isUserPresent("FakeUser");
        assertThat(memoryAppender.contains("Checking whether user exists in Jira", Level.INFO)).isTrue();
        Assertions.assertFalse(result);
    }

    @Test
    void testIsUserPresentInternalExists() throws IOException {
        URL fakeUrl = spy(URI.create(String.format(JIRA_USER_QUERY.get(), "FakeUser")).toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        when(fakeHttpConnection.getResponseCode()).thenReturn(200);
        httpUrlStreamHandler.addConnection(fakeUrl, fakeHttpConnection);
        var result = JiraAPI.getInstance().isUserPresent("FakeUser");
        assertThat(memoryAppender.contains("Checking whether user exists in Jira", Level.INFO)).isTrue();
        Assertions.assertTrue(result);
    }
}
