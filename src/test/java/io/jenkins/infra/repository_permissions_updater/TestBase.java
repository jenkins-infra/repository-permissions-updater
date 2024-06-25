package io.jenkins.infra.repository_permissions_updater;

import io.jenkins.infra.repository_permissions_updater.helper.HttpUrlStreamHandler;
import org.junit.jupiter.api.BeforeAll;

import java.net.URL;
import java.net.URLStreamHandlerFactory;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

abstract class TestBase {
    protected static HttpUrlStreamHandler httpUrlStreamHandler;

    protected static URLStreamHandlerFactory urlStreamHandlerFactory;

    @BeforeAll
    public static void setup() {
        urlStreamHandlerFactory = mock(URLStreamHandlerFactory.class);
        httpUrlStreamHandler = new HttpUrlStreamHandler();
        when(urlStreamHandlerFactory.createURLStreamHandler("http")).thenReturn(httpUrlStreamHandler);
        when(urlStreamHandlerFactory.createURLStreamHandler("https")).thenReturn(httpUrlStreamHandler);
        URL.setURLStreamHandlerFactory(urlStreamHandlerFactory);
    }
}
