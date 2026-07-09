package com.example.jiraclient;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import org.springaicommunity.mcp.security.client.sync.oauth2.http.client.OAuth2ClientCredentialsSyncHttpRequestCustomizer;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class McpSecurityConfig {

    private static final String REGISTRATION_ID = "authserver-client-credentials";
    // Matches the hardcoded principal name org.springaicommunity's OAuth2ClientCredentialsSyncHttpRequestCustomizer
    // uses internally, so the token we pre-warm here is the same cache entry it looks up later.
    private static final String PRINCIPAL_NAME = "mcp-client-service";

    @Bean
    OAuth2AuthorizedClientService authorizedClientService(ClientRegistrationRepository clientRegistrationRepository) {
        return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
    }

    @Bean
    AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {
        // Spring Security's default token-response client's Reactor Netty request factory was
        // intermittently timing out on this machine's network path with its default (short) timeout.
        // Keep Reactor Netty (so RestClient.builder()'s default converters/wiring stay intact) but
        // give it a longer timeout.
        ReactorClientHttpRequestFactory requestFactory = new ReactorClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(45));
        requestFactory.setReadTimeout(Duration.ofSeconds(45));
        // Replicating the same converter setup RestClientClientCredentialsTokenResponseClient's own
        // default constructor uses (verified by decompiling it) - a plain RestClient.builder().build()
        // does NOT include the OAuth2-specific response converter, which silently produced a token
        // response with a null access token.
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
        RestClientClientCredentialsTokenResponseClient tokenResponseClient = new RestClientClientCredentialsTokenResponseClient();
        tokenResponseClient.setRestClient(restClient);

        OAuth2AuthorizedClientProvider authorizedClientProvider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(clientCredentials -> clientCredentials.accessTokenResponseClient(tokenResponseClient))
                .build();
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(authorizedClientProvider);

        // Pre-warm the token now, during normal (blocking) Spring bean creation. The MCP SDK's own
        // sync-to-async customizer bridge wraps the whole per-request customize() call - including a
        // first-time token fetch - in a hardcoded ~20s reactive timeout, which a cold JVM's first
        // HTTPS/TLS negotiation can occasionally miss. Fetching here means that by the time the MCP
        // client calls customize(), the token is already cached and the call is instant.
        manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION_ID)
                .principal(PRINCIPAL_NAME)
                .build());
        return manager;
    }

    @Bean
    McpSyncHttpClientRequestCustomizer mcpRequestCustomizer(AuthorizedClientServiceOAuth2AuthorizedClientManager manager) {
        return new OAuth2ClientCredentialsSyncHttpRequestCustomizer(manager, REGISTRATION_ID);
    }

    @Bean
    McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> mcpTransportCustomizer(
            McpSyncHttpClientRequestCustomizer requestCustomizer) {
        return (name, builder) -> builder.httpRequestCustomizer(requestCustomizer);
    }
}
