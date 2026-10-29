package com.flowpay.payment.provider;

import java.net.URI;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("mock-provider")
public class ProviderEndpoints {

    private final Environment environment;

    public ProviderEndpoints(Environment environment) {
        this.environment = environment;
    }

    public URI provider(String path) {
        return endpoint("mock-provider.base-url", path);
    }

    public URI callback() {
        return endpoint("mock-provider.callback-base-url", "/provider-callbacks/mock");
    }

    // Resolve the actual listening port at call time, including RANDOM_PORT tests.
    private URI endpoint(String property, String path) {
        String base = environment.getProperty(property, "");
        if (base.isBlank()) {
            String port = environment.getProperty("local.server.port",
                    environment.getProperty("server.port", "8080"));
            base = "http://127.0.0.1:" + port;
        }
        return URI.create(base.replaceAll("/+$", "") + path);
    }
}
