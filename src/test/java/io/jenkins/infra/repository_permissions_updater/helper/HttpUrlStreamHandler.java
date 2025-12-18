package io.jenkins.infra.repository_permissions_updater.helper;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HttpUrlStreamHandler extends URLStreamHandler {
    private Map<URL, Object> connections = new ConcurrentHashMap();
    private Map<String, Object> stringConnections = new ConcurrentHashMap();

    @Override
    public URLConnection openConnection(URL url) throws IOException {
        var result = connections.getOrDefault(url, this.stringConnections.getOrDefault(url.toString(), null));
        if (result instanceof IOException) {
            throw (IOException) result;
        }
        return (URLConnection) result;
    }

    public void resetConnections() {
        this.connections.clear();
        this.stringConnections.clear();
    }

    public HttpUrlStreamHandler addConnection(URL url, Object urlConnection) {
        this.connections.put(url, urlConnection);
        this.stringConnections.put(url.toString(), urlConnection);
        return this;
    }

    public HttpUrlStreamHandler addConnection(String url, Object urlConnection) {
        this.stringConnections.put(url.toString(), urlConnection);
        return this;
    }
}
