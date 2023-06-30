/*
 * Copyright 2013-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gateway.server.mvc.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.cloud.gateway.server.mvc.filter.FilterDiscoverer;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerDiscoverer;
import org.springframework.cloud.gateway.server.mvc.invoke.InvocationContext;
import org.springframework.cloud.gateway.server.mvc.invoke.OperationArgumentResolver;
import org.springframework.cloud.gateway.server.mvc.invoke.OperationParameters;
import org.springframework.cloud.gateway.server.mvc.invoke.ParameterValueMapper;
import org.springframework.cloud.gateway.server.mvc.invoke.convert.ConversionServiceParameterValueMapper;
import org.springframework.cloud.gateway.server.mvc.invoke.reflect.OperationMethod;
import org.springframework.cloud.gateway.server.mvc.invoke.reflect.ReflectiveOperationInvoker;
import org.springframework.cloud.gateway.server.mvc.predicate.PredicateDiscoverer;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.log.LogMessage;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.web.servlet.function.RouterFunctions.route;

public class GatewayMvcPropertiesBeanDefinitionRegistrar implements ImportBeanDefinitionRegistrar {

	protected final Log log = LogFactory.getLog(getClass());

	private final TrueNullOperationArgumentResolver trueNullOperationArgumentResolver = new TrueNullOperationArgumentResolver();

	private final Environment env;

	private final FilterDiscoverer filterDiscoverer = new FilterDiscoverer();

	private final HandlerDiscoverer handlerDiscoverer = new HandlerDiscoverer();

	private final PredicateDiscoverer predicateDiscoverer = new PredicateDiscoverer();

	private final ParameterValueMapper parameterValueMapper = new ConversionServiceParameterValueMapper();

	private final Binder binder;

	public GatewayMvcPropertiesBeanDefinitionRegistrar(Environment env) {
		this.env = env;
		binder = Binder.get(env);
	}

	@Override
	public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
		GatewayMvcProperties properties = binder.bindOrCreate(GatewayMvcProperties.PREFIX, GatewayMvcProperties.class);
		properties.getRoutes().forEach(routeProperties -> {
			registerRoute(registry, routeProperties, routeProperties.getId());
		});
		properties.getRoutesMap().forEach((routeId, routeProperties) -> {
			String beanNamePrefix = routeId;
			if (StringUtils.hasText(routeProperties.getId())) {
				beanNamePrefix = routeProperties.getId();
			}
			registerRoute(registry, routeProperties, beanNamePrefix);
		});
	}

	private void registerRoute(BeanDefinitionRegistry registry, RouteProperties routeProperties,
			String beanNamePrefix) {
		AbstractBeanDefinition beanDefinition = BeanDefinitionBuilder.genericBeanDefinition(RouterFunction.class,
				() -> getRouterFunctionSupplier(routeProperties, beanNamePrefix)).getBeanDefinition();
		registry.registerBeanDefinition(beanNamePrefix + "RouterFunction", beanDefinition);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private RouterFunction getRouterFunctionSupplier(RouteProperties routeProperties, String beanNamePrefix) {
		log.trace(LogMessage.format("Creating route for : %s", routeProperties));

		RouterFunctions.Builder builder = route();

		// MVC.fn users won't need this anonymous filter as url will be set directly.
		// Put this function first, so if a filter from a handler changes the url
		// it is after this one.
		builder.filter((request, next) -> {
			MvcUtils.setRequestUrl(request, routeProperties.getUri());
			return next.handle(request);
		});

		MultiValueMap<String, OperationMethod> handlerOperations = handlerDiscoverer.getOperations();
		// TODO: cache?
		// translate handlerFunction
		String scheme = routeProperties.getUri().getScheme();
		Map<String, String> handlerArgs = new HashMap<>();
		// TODO: avoid hardcoded scheme/uri args
		// maybe find empty args or single RouteProperties param?
		if (scheme.equals("lb")) {
			handlerArgs.put("uri", routeProperties.getUri().toString());
		}
		Optional<NormalizedOperationMethod> handlerOperationMethod = findOperation(handlerOperations,
				scheme.toLowerCase(), handlerArgs);
		if (handlerOperationMethod.isEmpty()) {
			throw new IllegalStateException("Unable to find HandlerFunction for scheme: " + scheme);
		}
		NormalizedOperationMethod normalizedOpMethod = handlerOperationMethod.get();
		Object response = invokeOperation(normalizedOpMethod, normalizedOpMethod.getNormalizedArgs());
		HandlerFunction<ServerResponse> handlerFunction = null;
		if (response instanceof HandlerFunction<?>) {
			handlerFunction = (HandlerFunction<ServerResponse>) response;
		}
		else if (response instanceof HandlerDiscoverer.Result result) {
			handlerFunction = result.getHandlerFunction();
			result.getFilters().forEach(builder::filter);
		}
		if (handlerFunction == null) {
			throw new IllegalStateException(
					"Unable to find HandlerFunction for scheme: " + scheme + " and response " + response);
		}

		// translate predicates
		MultiValueMap<String, OperationMethod> predicateOperations = predicateDiscoverer.getOperations();
		final AtomicReference<RequestPredicate> predicate = new AtomicReference<>();

		routeProperties.getPredicates()
				.forEach(predicateProperties -> translate(predicateOperations, predicateProperties.getName(),
						predicateProperties.getArgs(), RequestPredicate.class, requestPredicate -> {
							log.trace(LogMessage.format("Adding predicate to route %s - %s", beanNamePrefix,
									predicateProperties));
							if (predicate.get() == null) {
								predicate.set(requestPredicate);
							}
							else {
								RequestPredicate combined = predicate.get().and(requestPredicate);
								predicate.set(combined);
							}
							log.trace(LogMessage.format("Combined predicate for route %s - %s", beanNamePrefix,
									predicate.get()));
						}));

		// combine predicate and handlerFunction
		builder.route(predicate.get(), handlerFunction);
		predicate.set(null);

		// translate filters
		MultiValueMap<String, OperationMethod> filterOperations = filterDiscoverer.getOperations();
		routeProperties.getFilters().forEach(filterProperties -> translate(filterOperations, filterProperties.getName(),
				filterProperties.getArgs(), HandlerFilterFunction.class, builder::filter));

		return builder.build();
	}

	private <T> void translate(MultiValueMap<String, OperationMethod> operations, String operationName,
			Map<String, String> operationArgs, Class<T> returnType, Consumer<T> operationHandler) {
		String normalizedName = StringUtils.uncapitalize(operationName);
		Optional<NormalizedOperationMethod> operationMethod = findOperation(operations, normalizedName, operationArgs);
		if (operationMethod.isPresent()) {
			NormalizedOperationMethod opMethod = operationMethod.get();
			T handlerFilterFunction = invokeOperation(opMethod, opMethod.getNormalizedArgs());
			if (handlerFilterFunction != null) {
				operationHandler.accept(handlerFilterFunction);
			}
		}
		else {
			throw new IllegalArgumentException(String.format("Unable to find operation %s for %s with args %s",
					returnType, normalizedName, operationArgs));
		}
	}

	private Optional<NormalizedOperationMethod> findOperation(MultiValueMap<String, OperationMethod> operations,
			String operationName, Map<String, String> operationArgs) {
		return operations.getOrDefault(operationName, Collections.emptyList()).stream()
				.map(operationMethod -> new NormalizedOperationMethod(operationMethod, operationArgs))
				.filter(opeMethod -> matchOperation(opeMethod, operationArgs)).findFirst();
	}

	private static boolean matchOperation(NormalizedOperationMethod operationMethod, Map<String, String> args) {
		Map<String, String> normalizedArgs = operationMethod.getNormalizedArgs();
		OperationParameters parameters = operationMethod.getParameters();
		if (parameters.getParameterCount() != normalizedArgs.size()) {
			return false;
		}
		for (int i = 0; i < parameters.getParameterCount(); i++) {
			if (!normalizedArgs.containsKey(parameters.get(i).getName())) {
				return false;
			}
		}
		// args contains all parameter names
		return true;
	}

	private <T> T invokeOperation(OperationMethod operationMethod, Map<String, String> operationArgs) {
		Map<String, Object> args = new HashMap<>(operationArgs);
		ReflectiveOperationInvoker operationInvoker = new ReflectiveOperationInvoker(operationMethod,
				this.parameterValueMapper);
		InvocationContext context = new InvocationContext(args, trueNullOperationArgumentResolver);
		return operationInvoker.invoke(context);
	}

	static class TrueNullOperationArgumentResolver implements OperationArgumentResolver {

		@Override
		public boolean canResolve(Class<?> type) {
			return true;
		}

		@Override
		public <T> T resolve(Class<T> type) {
			return null;
		}

	}

}
