package io.jenkins.infra.repository_permissions_updater;

import io.jenkins.infra.repository_permissions_updater.helper.HttpUrlStreamHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URL;
import java.net.URLStreamHandlerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Supplier;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class GithubAPITest extends TestBase {

    private Properties backup;

    @BeforeEach
    public void reset() {
        backup = new Properties();
        backup.putAll(System.getProperties());
        GitHubAPI.INSTANCE = null;
        httpUrlStreamHandler.resetConnections();
    }

    @AfterEach
    void restore() {
        System.setProperties(backup);
    }

    @Test
    void testGetRepositoryPublicKeyWrongBaseUrl() {
        GitHubAPI.INSTANCE = new GitHubImpl("XX://api.github.com/repos/%s/actions/secrets/public-key", "https://api.github.com/repos/%s/actions/secrets/%s");
        Exception exception = assertThrows(IOException.class, () -> {
            GitHubAPI.getInstance().getRepositoryPublicKey("FakeRepo");
        });
        String expectedMessage = "Github Malformed URL";
        String actualMessage = exception.getMessage();
        Assertions.assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    void testGetRepositoryPublicKeyFailedToOpenConnection() throws IOException {
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/public-key").toURL());
        httpUrlStreamHandler.addConnection(fakeUrl, new IOException());
        Exception exception = assertThrows(IOException.class, () -> {
            GitHubAPI.getInstance().getRepositoryPublicKey("FakeRepo");
        });
        String expectedMessage = "Failed to open connection to github";
        String actualMessage = exception.getMessage();
        Assertions.assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    void testGetRepositoryPublicKeyFailedToGetConnection() throws IOException {
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/public-key").toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        doThrow(ProtocolException.class).when(fakeHttpConnection).setRequestMethod(anyString());
        httpUrlStreamHandler.addConnection(fakeUrl, fakeHttpConnection);
        Exception exception = assertThrows(IOException.class, () -> {
            GitHubAPI.getInstance().getRepositoryPublicKey("FakeRepo");
        });
        String expectedMessage = "Protocol error for github";
        String actualMessage = exception.getMessage();
        Assertions.assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    void testGetRepositoryPublicKeyFailedToConnect() throws IOException {
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/public-key").toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        doThrow(IOException.class).when(fakeHttpConnection).connect();
        httpUrlStreamHandler.addConnection(fakeUrl, fakeHttpConnection);
        Exception exception = assertThrows(IOException.class, () -> {
            GitHubAPI.getInstance().getRepositoryPublicKey("FakeRepo");
        });
        String expectedMessage = "Connection error to github";
        String actualMessage = exception.getMessage();
        Assertions.assertTrue(actualMessage.contains(expectedMessage));
    }


    @Test
    void testGetRepositoryPublicKeyResponseCodeException() throws IOException {
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/public-key").toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        when(fakeHttpConnection.getResponseCode()).thenThrow(IOException.class);
        httpUrlStreamHandler.addConnection(fakeUrl, fakeHttpConnection);
        Exception exception = assertThrows(IOException.class, () -> {
            GitHubAPI.getInstance().getRepositoryPublicKey("FakeRepo");
        });
        String expectedMessage = "ResponseCode error from github";
        String actualMessage = exception.getMessage();
        Assertions.assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    void testGetRepositoryPublicKeyResponseCodeNot200() throws IOException {
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/public-key").toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        when(fakeHttpConnection.getResponseCode()).thenReturn(500);
        httpUrlStreamHandler.addConnection(fakeUrl, fakeHttpConnection);
        Assertions.assertNull(GitHubAPI.getInstance().getRepositoryPublicKey("FakeRepo"));
    }

    @Test
    void testGetRepositoryPublicKeyFailedResponse() throws IOException {
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/public-key").toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        when(fakeHttpConnection.getInputStream()).thenThrow(IOException.class);
        when(fakeHttpConnection.getResponseCode()).thenReturn(200);
        httpUrlStreamHandler.addConnection(fakeUrl, fakeHttpConnection);
        Exception exception = assertThrows(IOException.class, () -> {
            GitHubAPI.getInstance().getRepositoryPublicKey("FakeRepo");
        });
        String expectedMessage = "Failed to parse github response";
        String actualMessage = exception.getMessage();
        Assertions.assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    void testGetRepositoryPublicKeySuccessResponse() throws IOException {
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/public-key").toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        when(fakeHttpConnection.getInputStream()).thenReturn(Files.newInputStream(Path.of("src","test","resources", "public_key_fake_response_25_06_2024.json")));
        when(fakeHttpConnection.getResponseCode()).thenReturn(200);
        httpUrlStreamHandler.addConnection(fakeUrl, fakeHttpConnection);
        var result = GitHubAPI.getInstance().getRepositoryPublicKey("FakeRepo");
        Assertions.assertEquals("fakeKeyId", result.getKeyId());
        Assertions.assertEquals("fakeKey", result.getKey());
    }

    @Test
    void testCreateOrUpdateRepositorySecretWrongBaseUrl() {
        GitHubAPI.INSTANCE = new GitHubImpl("XX://api.github.com/repos/%s/actions/secrets/public-key", "XX://api.github.com/repos/%s/actions/secrets/%s");
        Exception exception = assertThrows(IOException.class, () -> {
            GitHubAPI.getInstance().createOrUpdateRepositorySecret("FakeKey", "fakeSecret", "FakeRepo", "fakeKeyId");
        });
        String expectedMessage = "Github Malformed URL";
        String actualMessage = exception.getMessage();
        Assertions.assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    void testCreateOrUpdateRepositorySecretFailedToOpenConnection() throws IOException {
        GitHubAPI.INSTANCE = new GitHubImpl("XX://api.github.com/repos/%s/actions/secrets/public-key",
                "https://api.github.com/repos/%s/actions/secrets/%s");
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/FakeKey").toURL());
        httpUrlStreamHandler.addConnection(fakeUrl, new IOException());
        Exception exception = assertThrows(IOException.class, () -> {
            GitHubAPI.getInstance().createOrUpdateRepositorySecret("FakeKey", "fakeSecret", "FakeRepo", "fakeKeyId");
        });
        String expectedMessage = "Failed to open connection to github";
        String actualMessage = exception.getMessage();
        Assertions.assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    void testCreateOrUpdateRepositorySecretFailedToSetAuthHeader() throws IOException {
        GitHubAPI.INSTANCE = new GitHubImpl("XX://api.github.com/repos/%s/actions/secrets/public-key", "https://api.github.com/repos/%s/actions/secrets/%s");
        Exception exception = assertThrows(NullPointerException.class, () -> {
            GitHubAPI.getInstance().createOrUpdateRepositorySecret("FakeKey", "fakeSecret", "FakeRepo", "fakeKeyId");
        });
        String expectedMessage = "Cannot invoke \"java.net.HttpURLConnection.setRequestProperty(String, String)\" because \"conn\" is null";
        String actualMessage = exception.getMessage();
        Assertions.assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    void testCreateOrUpdateRepositorySecretFailedToSetRequestMethod() throws IOException {
        GitHubAPI.INSTANCE = new GitHubImpl("XX://api.github.com/repos/%s/actions/secrets/public-key",
                "https://api.github.com/repos/%s/actions/secrets/%s");
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/FakeKey").toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        doThrow(ProtocolException.class).when(fakeHttpConnection).setRequestMethod(anyString());
        httpUrlStreamHandler.addConnection(fakeUrl, fakeHttpConnection);
        Exception exception = assertThrows(IOException.class, () -> {
            GitHubAPI.getInstance().createOrUpdateRepositorySecret("FakeKey", "fakeSecret", "FakeRepo", "fakeKeyId");
        });
        String expectedMessage = "Protocol error for github";
        String actualMessage = exception.getMessage();
        Assertions.assertTrue(actualMessage.contains(expectedMessage));
    }


    @Test
    void testCreateOrUpdateRepositorySecretFailedToCallGetOutputStreamMethod() throws IOException {
        GitHubAPI.INSTANCE = new GitHubImpl("XX://api.github.com/repos/%s/actions/secrets/public-key",
                "https://api.github.com/repos/%s/actions/secrets/%s");
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/FakeKey").toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        when(fakeHttpConnection.getOutputStream()).thenThrow(IOException.class);
        httpUrlStreamHandler.addConnection(fakeUrl, fakeHttpConnection);
        Exception exception = assertThrows(IOException.class, () -> {
            GitHubAPI.getInstance().createOrUpdateRepositorySecret("FakeKey", "fakeSecret", "FakeRepo", "fakeKeyId");
        });
        String expectedMessage = "IO error to send data to github";
        String actualMessage = exception.getMessage();
        Assertions.assertTrue(actualMessage.contains(expectedMessage));
    }

}
