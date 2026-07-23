package io.jenkins.infra.repository_permissions_updater;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.jenkins.infra.repository_permissions_updater.helper.URLHelper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GithubAPITest {

    private Properties backup;

    @BeforeEach
    public void reset() {
        backup = new Properties();
        backup.putAll(System.getProperties());
        GitHubAPI.INSTANCE = null;
        URLHelper.instance().getURLStreamHandler().resetConnections();
    }

    @AfterEach
    void restore() {
        System.setProperties(backup);
    }

    @Test
    void testGetRepositoryPublicKeyWrongBaseUrl() {
        GitHubAPI.INSTANCE = new GitHubImpl(
                "XX://api.github.com/repos/%s/actions/secrets/public-key",
                "https://api.github.com/repos/%s/actions/secrets/%s");
        Assertions.assertThrows(
                MalformedURLException.class,
                () -> {
                    GitHubAPI.getInstance().getRepositoryPublicKey("FakeRepo");
                },
                "unknown protocol: xx");
    }

    @Test
    void testGetRepositoryPublicKeyFailedToOpenConnection() throws IOException {
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/public-key")
                .toURL());
        URLHelper.instance().getURLStreamHandler().addConnection(fakeUrl, new IOException());
        Assertions.assertThrows(IOException.class, () -> {
            GitHubAPI.getInstance().getRepositoryPublicKey("FakeRepo");
        });
    }

    @Test
    void testGetRepositoryPublicKeyFailedToGetConnection() throws IOException {
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/public-key")
                .toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        doThrow(ProtocolException.class).when(fakeHttpConnection).setRequestMethod(anyString());
        URLHelper.instance().getURLStreamHandler().addConnection(fakeUrl, fakeHttpConnection);
        assertThrows(ProtocolException.class, () -> {
            GitHubAPI.getInstance().getRepositoryPublicKey("FakeRepo");
        });
    }

    @Test
    void testGetRepositoryPublicKeyFailedToConnect() throws IOException {
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/public-key")
                .toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        doThrow(IOException.class).when(fakeHttpConnection).connect();
        URLHelper.instance().getURLStreamHandler().addConnection(fakeUrl, fakeHttpConnection);
        assertThrows(IOException.class, () -> {
            GitHubAPI.getInstance().getRepositoryPublicKey("FakeRepo");
        });
    }

    @Test
    void testGetRepositoryPublicKeyResponseCodeException() throws IOException {
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/public-key")
                .toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        when(fakeHttpConnection.getResponseCode()).thenThrow(IOException.class);
        URLHelper.instance().getURLStreamHandler().addConnection(fakeUrl, fakeHttpConnection);
        assertThrows(IOException.class, () -> {
            GitHubAPI.getInstance().getRepositoryPublicKey("FakeRepo");
        });
    }

    @Test
    void testGetRepositoryPublicKeyResponseCodeNot200() throws IOException {
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/public-key")
                .toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        when(fakeHttpConnection.getResponseCode()).thenReturn(500);
        URLHelper.instance().getURLStreamHandler().addConnection(fakeUrl, fakeHttpConnection);
        Assertions.assertNull(GitHubAPI.getInstance().getRepositoryPublicKey("FakeRepo"));
    }

    @Test
    void testGetRepositoryPublicKeyFailedResponse() throws IOException {
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/public-key")
                .toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        when(fakeHttpConnection.getInputStream()).thenThrow(IOException.class);
        when(fakeHttpConnection.getResponseCode()).thenReturn(200);
        URLHelper.instance().getURLStreamHandler().addConnection(fakeUrl, fakeHttpConnection);
        Assertions.assertThrows(IOException.class, () -> {
            GitHubAPI.getInstance().getRepositoryPublicKey("FakeRepo");
        });
    }

    @Test
    void testGetRepositoryPublicKeySuccessResponse() throws IOException {
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/public-key")
                .toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        when(fakeHttpConnection.getInputStream())
                .thenReturn(Files.newInputStream(
                        Path.of("src", "test", "resources", "public_key_fake_response_25_06_2024.json")));
        when(fakeHttpConnection.getResponseCode()).thenReturn(200);
        URLHelper.instance().getURLStreamHandler().addConnection(fakeUrl, fakeHttpConnection);
        var result = GitHubAPI.getInstance().getRepositoryPublicKey("FakeRepo");
        Assertions.assertEquals("fakeKeyId", result.getKeyId());
        Assertions.assertEquals("fakeKey", result.getKey());
    }

    @Test
    void testCreateOrUpdateRepositorySecretWrongBaseUrl() {
        GitHubAPI.INSTANCE = new GitHubImpl(
                "XX://api.github.com/repos/%s/actions/secrets/public-key",
                "XX://api.github.com/repos/%s/actions/secrets/%s");
        Assertions.assertThrows(
                MalformedURLException.class,
                () -> {
                    GitHubAPI.getInstance()
                            .createOrUpdateRepositorySecret("FakeKey", "fakeSecret", "FakeRepo", "fakeKeyId");
                },
                "unknown protocol: xx");
    }

    @Test
    void testCreateOrUpdateRepositorySecretFailedToOpenConnection() throws IOException {
        GitHubAPI.INSTANCE = new GitHubImpl(
                "XX://api.github.com/repos/%s/actions/secrets/public-key",
                "https://api.github.com/repos/%s/actions/secrets/%s");
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/FakeKey")
                .toURL());
        URLHelper.instance().getURLStreamHandler().addConnection(fakeUrl, new IOException());
        assertThrows(IOException.class, () -> {
            GitHubAPI.getInstance().createOrUpdateRepositorySecret("FakeKey", "fakeSecret", "FakeRepo", "fakeKeyId");
        });
    }

    @Test
    void testCreateOrUpdateRepositorySecretFailedToSetAuthHeader() throws IOException {
        GitHubAPI.INSTANCE = new GitHubImpl(
                "XX://api.github.com/repos/%s/actions/secrets/public-key",
                "https://api.github.com/repos/%s/actions/secrets/%s");
        Exception exception = assertThrows(NullPointerException.class, () -> {
            GitHubAPI.getInstance().createOrUpdateRepositorySecret("FakeKey", "fakeSecret", "FakeRepo", "fakeKeyId");
        });
        String expectedMessage =
                "Cannot invoke \"java.net.HttpURLConnection.setRequestProperty(String, String)\" because \"conn\" is null";
        String actualMessage = exception.getMessage();
        Assertions.assertTrue(actualMessage.contains(expectedMessage));
    }

    @Test
    void testCreateOrUpdateRepositorySecretFailedToSetRequestMethod() throws IOException {
        GitHubAPI.INSTANCE = new GitHubImpl(
                "XX://api.github.com/repos/%s/actions/secrets/public-key",
                "https://api.github.com/repos/%s/actions/secrets/%s");
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/FakeKey")
                .toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        doThrow(ProtocolException.class).when(fakeHttpConnection).setRequestMethod(anyString());
        URLHelper.instance().getURLStreamHandler().addConnection(fakeUrl, fakeHttpConnection);
        Assertions.assertThrows(ProtocolException.class, () -> {
            GitHubAPI.getInstance().createOrUpdateRepositorySecret("FakeKey", "fakeSecret", "FakeRepo", "fakeKeyId");
        });
    }

    @Test
    void testCreateOrUpdateRepositorySecretFailedToCallGetOutputStreamMethod() throws IOException {
        GitHubAPI.INSTANCE = new GitHubImpl(
                "XX://api.github.com/repos/%s/actions/secrets/public-key",
                "https://api.github.com/repos/%s/actions/secrets/%s");
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/FakeKey")
                .toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        when(fakeHttpConnection.getOutputStream()).thenThrow(IOException.class);
        URLHelper.instance().getURLStreamHandler().addConnection(fakeUrl, fakeHttpConnection);
        Assertions.assertThrows(IOException.class, () -> {
            GitHubAPI.getInstance().createOrUpdateRepositorySecret("FakeKey", "fakeSecret", "FakeRepo", "fakeKeyId");
        });
    }

    @Test
    void testCreateOrUpdateRepositorySecretOutputStreamContent() throws IOException {
        GitHubAPI.INSTANCE = new GitHubImpl(
                "XX://api.github.com/repos/%s/actions/secrets/public-key",
                "https://api.github.com/repos/%s/actions/secrets/%s");
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/FakeKey")
                .toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        when(fakeHttpConnection.getOutputStream()).thenReturn(outputStream);
        when(fakeHttpConnection.getResponseCode()).thenThrow(IOException.class);

        URLHelper.instance().getURLStreamHandler().addConnection(fakeUrl, fakeHttpConnection);
        Assertions.assertThrows(IOException.class, () -> {
            GitHubAPI.getInstance().createOrUpdateRepositorySecret("FakeKey", "fakeSecret", "FakeRepo", "fakeKeyId");
        });
        var result = outputStream.toString(StandardCharsets.UTF_8);
        Assertions.assertEquals("{\"encrypted_value\":\"fakeSecret\",\"key_id\":\"fakeKeyId\"}\n", result);
    }

    @Test
    void testCreateOrUpdateRepositorySecretTestAllAttempts() throws IOException {
        GitHubAPI.INSTANCE = new GitHubImpl(
                "XX://api.github.com/repos/%s/actions/secrets/public-key",
                "https://api.github.com/repos/%s/actions/secrets/%s");
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/FakeKey")
                .toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        when(fakeHttpConnection.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(fakeHttpConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_NO_CONTENT);

        URLHelper.instance().getURLStreamHandler().addConnection(fakeUrl, fakeHttpConnection);
        GitHubAPI.getInstance().createOrUpdateRepositorySecret("FakeKey", "fakeSecret", "FakeRepo", "fakeKeyId");
    }

    @Test
    void testCreateOrUpdateRepositorySecretWithNoAttempts() throws IOException {
        GitHubAPI.INSTANCE = new GitHubImpl(
                "XX://api.github.com/repos/%s/actions/secrets/public-key",
                "https://api.github.com/repos/%s/actions/secrets/%s");
        URL fakeUrl = spy(URI.create("https://api.github.com/repos/FakeRepo/actions/secrets/FakeKey")
                .toURL());
        var fakeHttpConnection = mock(HttpURLConnection.class);
        when(fakeHttpConnection.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(fakeHttpConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);

        URLHelper.instance().getURLStreamHandler().addConnection(fakeUrl, fakeHttpConnection);
        GitHubAPI.getInstance().createOrUpdateRepositorySecret("FakeKey", "fakeSecret", "FakeRepo", "fakeKeyId");
    }
}
