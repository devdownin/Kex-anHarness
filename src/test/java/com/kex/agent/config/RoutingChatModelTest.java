// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.config;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RoutingChatModelTest {

    @Test
    void validates_routing_properties() {
        LlmRoutingProperties.Endpoint ep = new LlmRoutingProperties.Endpoint(
                LlmRoutingProperties.Provider.OPENAI, "https://api.openai.com/v1", "key", "gpt-4o", Duration.ofSeconds(30), 4096, 0.2);

        LlmRoutingProperties props = new LlmRoutingProperties(true, false,
                Map.of("m1", ep), Map.of(LlmRoutingProperties.Task.CHAT, List.of("m1")));

        assertThat(props.route(LlmRoutingProperties.Task.CHAT)).containsExactly("m1");

        assertThatThrownBy(() -> new LlmRoutingProperties(true, false, Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void calls_primary_model_and_fails_over_on_eligible_error() {
        ChatModel m1 = mock(ChatModel.class);
        ChatModel m2 = mock(ChatModel.class);

        given(m1.call(any(Prompt.class))).willThrow(new RuntimeException(new ConnectException("connection refused")));

        ChatResponse response = new ChatResponse(List.of(new Generation(new org.springframework.ai.chat.messages.AssistantMessage("hello"))));
        given(m2.call(any(Prompt.class))).willReturn(response);

        LlmRoutingProperties.Endpoint ep1 = new LlmRoutingProperties.Endpoint(
                LlmRoutingProperties.Provider.OPENAI, "https://api.openai.com/v1", "key1", "gpt-4o", Duration.ofSeconds(30), 4096, 0.2);
        LlmRoutingProperties.Endpoint ep2 = new LlmRoutingProperties.Endpoint(
                LlmRoutingProperties.Provider.ANTHROPIC, "https://api.anthropic.com", "key2", "claude-3-5-sonnet", Duration.ofSeconds(30), 4096, 0.2);

        LlmRoutingProperties props = new LlmRoutingProperties(true, false,
                Map.of("m1", ep1, "m2", ep2), Map.of(LlmRoutingProperties.Task.CHAT, List.of("m1", "m2")));

        RoutingChatModel router = new RoutingChatModel(props, Map.of("m1", m1, "m2", m2));

        ChatResponse res = router.call(new Prompt("hi"));
        assertThat(res).isEqualTo(response);

        verify(m1).call(any(Prompt.class));
        verify(m2).call(any(Prompt.class));
    }

    @Test
    void streams_with_failover() {
        ChatModel m1 = mock(ChatModel.class);
        ChatModel m2 = mock(ChatModel.class);

        given(m1.stream(any(Prompt.class))).willReturn(Flux.error(new ConnectException("down")));

        ChatResponse response = new ChatResponse(List.of(new Generation(new org.springframework.ai.chat.messages.AssistantMessage("chunk"))));
        given(m2.stream(any(Prompt.class))).willReturn(Flux.just(response));

        LlmRoutingProperties.Endpoint ep1 = new LlmRoutingProperties.Endpoint(
                LlmRoutingProperties.Provider.OLLAMA, "http://localhost:11434/v1", "kex-local", "llama3", Duration.ofSeconds(30), 4096, 0.2);
        LlmRoutingProperties.Endpoint ep2 = new LlmRoutingProperties.Endpoint(
                LlmRoutingProperties.Provider.VLLM, "http://localhost:8000/v1", "kex-local", "vllm-model", Duration.ofSeconds(30), 4096, 0.2);

        LlmRoutingProperties props = new LlmRoutingProperties(true, true,
                Map.of("m1", ep1, "m2", ep2), Map.of(LlmRoutingProperties.Task.CHAT, List.of("m1", "m2")));

        RoutingChatModel router = new RoutingChatModel(props, Map.of("m1", m1, "m2", m2));

        StepVerifier.create(router.stream(new Prompt("hi")))
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void tests_eligibility_of_failures() {
        assertThat(RoutingChatModel.eligible(new ConnectException())).isTrue();
        assertThat(RoutingChatModel.eligible(new SocketTimeoutException())).isTrue();
        assertThat(RoutingChatModel.eligible(new CancellationException())).isFalse();
        assertThat(RoutingChatModel.eligible(new InterruptedException())).isFalse();
        assertThat(RoutingChatModel.eligible(new IllegalArgumentException("bad argument"))).isFalse();
    }

    @Test
    void conserve_les_options_portables_et_le_format_de_sortie_sans_fuiter_les_secrets() {
        ChatModel m1 = mock(ChatModel.class);
        ChatResponse response = new ChatResponse(List.of(new Generation(new org.springframework.ai.chat.messages.AssistantMessage("{}"))));
        given(m1.call(any(Prompt.class))).willReturn(response);

        LlmRoutingProperties.Endpoint ep1 = new LlmRoutingProperties.Endpoint(
                LlmRoutingProperties.Provider.OPENAI, "https://api.openai.com/v1", "secret-key", "gpt-4o-mini",
                Duration.ofSeconds(30), 4096, 0.2);
        LlmRoutingProperties props = new LlmRoutingProperties(true, false,
                Map.of("m1", ep1), Map.of(LlmRoutingProperties.Task.CHAT, List.of("m1")));
        RoutingChatModel router = new RoutingChatModel(props, Map.of("m1", m1));

        var format = org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.builder()
                .type(org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT).build();
        var options = org.springframework.ai.openai.OpenAiChatOptions.builder()
                .model("wrong-model").maxTokens(300).temperature(0.4).topP(0.9)
                .stopSequences(List.of("END")).responseFormat(format).build();

        router.call(new Prompt("hi", options));

        var captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(m1).call(captor.capture());
        var forwarded = (org.springframework.ai.openai.OpenAiChatOptions) captor.getValue().getOptions();
        assertThat(forwarded.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(forwarded.getMaxTokens()).isEqualTo(300);
        assertThat(forwarded.getTemperature()).isEqualTo(0.4);
        assertThat(forwarded.getTopP()).isEqualTo(0.9);
        assertThat(forwarded.getStopSequences()).containsExactly("END");
        assertThat(forwarded.getResponseFormat()).isEqualTo(format);
    }
}
