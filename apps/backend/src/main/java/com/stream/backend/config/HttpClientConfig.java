package com.stream.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient readerRestClient(@Value("${reader.base-url}") String readerBaseUrl) {
        return RestClient.builder().baseUrl(readerBaseUrl).build();
    }

    @Bean
    public RestClient writerRestClient(@Value("${writer.base-url}") String writerBaseUrl) {
        return RestClient.builder().baseUrl(writerBaseUrl).build();
    }
}
