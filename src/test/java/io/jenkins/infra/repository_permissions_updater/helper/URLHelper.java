package io.jenkins.infra.repository_permissions_updater.helper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.net.URLStreamHandlerFactory;

/**
 * This class manage the handling of fake url for URL creation
 */
public sealed interface URLHelper permits URLHelper.URLHelperImpl {

    /**
     * @return singleton instance of the {@link URLHelper}
     */
    static URLHelper instance() {
        return URLHelperImpl.getInstance();
    }

    /**
     * @return singleton instance of the {@link HttpUrlStreamHandler}
     */
    HttpUrlStreamHandler getURLStreamHandler();

    /**
     * @return singleton instance of the {@link URLStreamHandlerFactory}
     */
    URLStreamHandlerFactory getURLStreamHandlerFactory();

    final class URLHelperImpl implements URLHelper {
        private HttpUrlStreamHandler httpUrlStreamHandler;

        private URLStreamHandlerFactory urlStreamHandlerFactory;

        private static URLHelper INSTANCE;

        private URLHelperImpl() {
            urlStreamHandlerFactory = mock(URLStreamHandlerFactory.class);
            httpUrlStreamHandler = new HttpUrlStreamHandler();
            when(urlStreamHandlerFactory.createURLStreamHandler("http")).thenReturn(httpUrlStreamHandler);
            when(urlStreamHandlerFactory.createURLStreamHandler("https")).thenReturn(httpUrlStreamHandler);
            URL.setURLStreamHandlerFactory(urlStreamHandlerFactory);
        }

        @Override
        public HttpUrlStreamHandler getURLStreamHandler() {
            return this.httpUrlStreamHandler;
        }

        @Override
        public URLStreamHandlerFactory getURLStreamHandlerFactory() {
            return this.urlStreamHandlerFactory;
        }

        static synchronized URLHelper getInstance() {
            if (INSTANCE == null) {
                INSTANCE = new URLHelperImpl();
            }
            return INSTANCE;
        }
    }
}
