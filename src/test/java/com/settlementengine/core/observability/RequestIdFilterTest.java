package com.settlementengine.core.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesARequestIdWhenNoneIsProvidedAndMakesItAvailableDuringTheChain() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("X-Request-Id")).thenReturn(null);

        StringBuilder seenDuringChain = new StringBuilder();
        doAnswer(invocation -> {
            seenDuringChain.append(MDC.get("requestId"));
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        assertThat(seenDuringChain.toString()).isNotBlank();
        verify(response).setHeader(eq("X-Request-Id"), eq(seenDuringChain.toString()));
    }

    @Test
    void propagatesAnIncomingRequestIdInsteadOfGeneratingANewOne() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("X-Request-Id")).thenReturn("caller-supplied-id");

        StringBuilder seenDuringChain = new StringBuilder();
        doAnswer(invocation -> {
            seenDuringChain.append(MDC.get("requestId"));
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        assertThat(seenDuringChain.toString()).isEqualTo("caller-supplied-id");
        verify(response).setHeader("X-Request-Id", "caller-supplied-id");
    }

    @Test
    void clearsTheRequestIdFromMdcAfterTheChainCompletes() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("X-Request-Id")).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void clearsTheRequestIdFromMdcEvenWhenTheChainThrows() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("X-Request-Id")).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            doAnswer(invocation -> {
                throw new java.io.IOException("boom");
            }).when(chain).doFilter(any(), any());
            filter.doFilter(request, response, chain);
        }).isInstanceOf(java.io.IOException.class);

        assertThat(MDC.get("requestId")).isNull();
    }
}
