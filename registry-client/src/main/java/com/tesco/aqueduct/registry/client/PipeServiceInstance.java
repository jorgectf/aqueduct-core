package com.tesco.aqueduct.registry.client;

import com.tesco.aqueduct.registry.utils.RegistryLogger;
import io.micronaut.discovery.ServiceInstance;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.uri.UriBuilder;
import io.reactivex.Completable;
import io.reactivex.Single;
import jakarta.inject.Inject;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;

public class PipeServiceInstance implements ServiceInstance {

    private final HttpClient httpClient;
    private final URL url;
    private boolean up = false;
    private static final RegistryLogger LOG = new RegistryLogger(LoggerFactory.getLogger(PipeServiceInstance.class));

    @Inject
    public PipeServiceInstance(final HttpClient httpClient, final URL url) {
        this.httpClient = httpClient;
        this.url = url;
    }

    public boolean isUp() {
        return up;
    }

    public void isUp(final boolean isServiceUp) {
        up = isServiceUp;
    }

    public URL getUrl() {
        return url;
    }

    @Override
    public String getId() {
        return "pipe";
    }

    @Override
    public URI getURI() {
        try {
            return url.toURI();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public URI resolve(URI relativeURI) {
        try {
            if (url.getPath() != null && !url.getPath().isEmpty()) {
                relativeURI = getUriWithBasePath(relativeURI);
            }
            return ServiceInstance.super.resolve(relativeURI);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("ServiceInstance URI is invalid: " + e.getMessage(), e);
        }
    }

    Completable updateState() {
        return Single.defer(() -> Single.just(withStatusUrlFromBaseUri())).
            flatMap(uri -> Single.fromPublisher(httpClient.retrieve(uri)))
            // if got response, then it's a true
            .map(response -> true)
            // log result
            .doOnSuccess(b -> LOG.debug("healthcheck.success", url.toString()))
            .doOnError(this::logError)
            .retry(2)
            // change exception to "false"
            .onErrorResumeNext(Single.just(false))
            // set the status of the instance
            .doOnSuccess(this::isUp)
            // return as completable, close client and ignore any errors
            .ignoreElement(); // returns completable
    }

    private void logError(Throwable throwable) {
        LOG.error("healthcheck.failed", url + " failed with error " + throwable.getMessage(), "");
    }

    private String withStatusUrlFromBaseUri() {
        return UriBuilder.of(getURI()).path("/pipe/_status").build().toString();
    }

    private URI getUriWithBasePath(final URI relativeURI) throws URISyntaxException {
        // replace() needed to make it compatible with the Windows file system
        final String path = Paths.get(url.getPath(), relativeURI.getPath()).toString().replace('\\', '/');
        return new URI(
            relativeURI.getScheme(),
            relativeURI.getUserInfo(),
            relativeURI.getHost(),
            relativeURI.getPort(),
            path,
            relativeURI.getQuery(),
            relativeURI.getFragment()
        );
    }
}