package com.gigachat.unit.tests.generator.llm;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GigaChatTokenClientTest {

    @Test
    void resolveApiUrlUsesOfficialDefaultWhenEndpointIsMissing() {
        assertEquals("https://gigachat.devices.sberbank.ru/api/v1",
                GigaChatTokenClient.resolveApiUrl(null));
    }

    @Test
    void resolveApiUrlAppendsApiPathWhenBaseUrlDoesNotContainIt() {
        assertEquals("https://gigachat.devices.sberbank.ru/api/v1",
                GigaChatTokenClient.resolveApiUrl("https://gigachat.devices.sberbank.ru"));
    }

    @Test
    void resolveAuthUrlUsesOfficialDefaultWhenOauthUrlIsMissing() {
        assertEquals(URI.create("https://ngw.devices.sberbank.ru:9443/api/v2/oauth"),
                GigaChatTokenClient.resolveAuthUrl(null));
    }

    @Test
    void resolveCompletionUrlAppendsChatCompletionsPath() {
        assertEquals("https://gigachat.devices.sberbank.ru/api/v1/chat/completions",
                GigaChatTokenClient.resolveCompletionUrl("https://gigachat.devices.sberbank.ru"));
    }
}
