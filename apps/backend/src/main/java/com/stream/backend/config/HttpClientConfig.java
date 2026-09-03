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

    static final String INTERNAL_KEY_HEADER = "X-Internal-Key";

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

    // writer의 /internal/**는 이제 X-Internal-Key를 요구한다. 호출부마다 헤더를
    // 붙이지 않고 클라이언트에 한 번 심는다 - 새 writer 엔드포인트를 부를 때
    // 헤더를 빠뜨려 401을 받는 일이 생기지 않는다.
    @Bean
    public RestClient writerRestClient(@Value("${writer.base-url}") String writerBaseUrl,
                                       @Value("${internal.api-key}") String internalApiKey) {
        return writerRestClientBuilder(writerBaseUrl, internalApiKey).build();
    }

    // 빌더 단계를 노출해 테스트가 MockRestServiceServer를 붙일 수 있게 한다.
    // 완성된 RestClient에는 붙일 수 없어서, 헤더 이름 오타 같은 설정 실수는
    // 이 접합점이 없으면 실기동에서만 드러난다.
    static RestClient.Builder writerRestClientBuilder(String baseUrl, String internalApiKey) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(INTERNAL_KEY_HEADER, internalApiKey)
                .requestFactory(timeoutRequestFactory());
    }

    // youtube-service는 202를 즉시 돌려주고 캡처는 뒤에서 돈다. 읽기 타임아웃
    // 5초는 ffmpeg 실행 시간이 아니라 그 202를 기다리는 시간이라 넉넉하다.
    @Bean
    public RestClient youtubeRestClient(@Value("${youtube.base-url}") String youtubeBaseUrl) {
        return RestClient.builder()
                .baseUrl(youtubeBaseUrl)
                .requestFactory(timeoutRequestFactory())
                .build();
    }
}
