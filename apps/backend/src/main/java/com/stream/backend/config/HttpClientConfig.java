package com.stream.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class HttpClientConfig {

    private static ClientHttpRequestFactory timeoutRequestFactory() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(5));
        return ClientHttpRequestFactoryBuilder.detect().build(settings);
    }

    @Bean
    public RestClient readerRestClient(@Value("${reader.base-url}") String readerBaseUrl) {
        return RestClient.builder()
                .baseUrl(readerBaseUrl)
                .requestFactory(timeoutRequestFactory())
                .build();
    }

    @Bean
    public RestClient writerRestClient(@Value("${writer.base-url}") String writerBaseUrl) {
        return RestClient.builder()
                .baseUrl(writerBaseUrl)
                .requestFactory(timeoutRequestFactory())
                .build();
    }
}
