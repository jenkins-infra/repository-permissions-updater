package io.jenkins.infra.repository_permissions_updater;

import io.jenkins.infra.repository_permissions_updater.helper.HttpUrlStreamHandler;

import java.net.URL;
import java.net.URLStreamHandlerFactory;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

sealed interface URLHelper permits URLHelper.URLHelperImpl {

    static URLHelper instance() {
        return URLHelperImpl.getINSTANCE();
    }

    HttpUrlStreamHandler getURLStreamHandler();

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

        static synchronized URLHelper getINSTANCE() {
            if (INSTANCE == null) {
                INSTANCE = new URLHelperImpl();
            }
            return INSTANCE;
        }

        @Override
        public HttpUrlStreamHandler getURLStreamHandler() {
            return this.httpUrlStreamHandler;
        }

        @Override
        public URLStreamHandlerFactory getURLStreamHandlerFactory() {
            return this.urlStreamHandlerFactory;
        }
    }


}
