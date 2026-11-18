/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.spring.webmvc.v6_0;

import static io.opentelemetry.instrumentation.api.semconv.http.HttpServerRouteSource.CONTROLLER;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRoute;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.HandlerFunction;

final class WebMvcRouteInterceptor implements HandlerInterceptor {
  static final ContextKey<Context> SERVER_CONTEXT = ContextKey.named("otel-spring-webmvc-server");

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (request.getDispatcherType() != DispatcherType.REQUEST
        || !(handler instanceof HandlerMethod || handler instanceof HandlerFunction<?>)) {
      return true;
    }

    Context context = Context.current().get(SERVER_CONTEXT);
    if (context == null) {
      return true;
    }

    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    if (pattern instanceof String) {
      HttpServerRoute.update(
          context, CONTROLLER, HttpRouteSupport.prependContextPath(request, (String) pattern));
    }
    return true;
  }
}
