/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.spring.webmvc.v6_0;

import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.equalTo;
import static io.opentelemetry.semconv.HttpAttributes.HTTP_ROUTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RouterFunctions.route;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.testing.junit.InstrumentationExtension;
import io.opentelemetry.instrumentation.testing.junit.LibraryInstrumentationExtension;
import io.opentelemetry.testing.internal.armeria.client.WebClient;
import io.opentelemetry.testing.internal.armeria.common.AggregatedHttpResponse;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = FunctionalHttpRouteTest.TestApplication.class,
    properties = {"server.servlet.context-path=/app", "spring.mvc.servlet.load-on-startup=1"})
class FunctionalHttpRouteTest {

  @RegisterExtension
  static final InstrumentationExtension testing = LibraryInstrumentationExtension.create();

  @Value("${local.server.port}")
  private int port;

  @Autowired private AtomicInteger matches;

  @Test
  void functionalEndpointUsesMatchedRoute() {
    int matchesBefore = matches.get();
    AggregatedHttpResponse response =
        WebClient.of("http://localhost:" + port).get("/app/items/42").aggregate().join();
    assertThat(response.status().code()).isEqualTo(200);

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span ->
                    span.hasName("GET /app/items/{id}")
                        .hasKind(SpanKind.SERVER)
                        .hasAttribute(equalTo(HTTP_ROUTE, "/app/items/{id}"))));
    assertThat(matches.get()).isEqualTo(matchesBefore + 1);
  }

  @Test
  void forwardKeepsOriginalRoute() {
    AggregatedHttpResponse response =
        WebClient.of("http://localhost:" + port).get("/app/entry/42").aggregate().join();
    assertThat(response.status().code()).isEqualTo(200);
    assertThat(response.contentUtf8()).isEqualTo("42");

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span ->
                    span.hasName("GET /app/entry/{id}")
                        .hasKind(SpanKind.SERVER)
                        .hasAttribute(equalTo(HTTP_ROUTE, "/app/entry/{id}"))));
  }

  @Test
  void requestRejectedBeforeMvcUsesFallbackRoute() {
    AggregatedHttpResponse response =
        WebClient.of("http://localhost:" + port).get("/app/blocked/42").aggregate().join();
    assertThat(response.status().code()).isEqualTo(401);

    testing.waitAndAssertTraces(
        trace ->
            trace.hasSpansSatisfyingExactly(
                span ->
                    span.hasName("GET /app/blocked/{id}")
                        .hasKind(SpanKind.SERVER)
                        .hasAttribute(equalTo(HTTP_ROUTE, "/app/blocked/{id}"))));
  }

  @EnableAutoConfiguration
  @Import(TestController.class)
  static class TestApplication {

    @Bean
    Filter telemetryFilter() {
      return SpringWebMvcTelemetry.create(GlobalOpenTelemetry.get()).createServletFilter();
    }

    @Bean
    WebMvcConfigurer telemetryRouteConfigurer() {
      return SpringWebMvcTelemetry.createWebMvcConfigurer();
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> rejectingFilter() {
      FilterRegistrationBean<OncePerRequestFilter> registration =
          new FilterRegistrationBean<>(
              new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(
                    HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
                  response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                }
              });
      registration.addUrlPatterns("/blocked/*");
      registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
      return registration;
    }

    @Bean
    AtomicInteger matches() {
      return new AtomicInteger();
    }

    @Bean
    RouterFunction<ServerResponse> itemsRoute(AtomicInteger matches) {
      return route(
          GET("/items/{id}")
              .and(
                  request -> {
                    matches.incrementAndGet();
                    return true;
                  }),
          request -> ServerResponse.ok().body(request.pathVariable("id")));
    }
  }

  @RestController
  static class TestController {
    @GetMapping("/entry/{id}")
    void forward(
        @PathVariable("id") String id, HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
      request.getRequestDispatcher("/items/" + id).forward(request, response);
    }

    @GetMapping("/blocked/{id}")
    String blocked(@PathVariable("id") String id) {
      return id;
    }
  }
}
