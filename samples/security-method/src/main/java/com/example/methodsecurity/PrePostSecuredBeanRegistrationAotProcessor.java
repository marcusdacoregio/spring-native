/*
 * Copyright 2002-2022 the original author or authors.
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

package com.example.methodsecurity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.SpringProxy;
import org.springframework.aop.framework.Advised;
import org.springframework.aot.generate.GenerationContext;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.factory.aot.BeanRegistrationAotContribution;
import org.springframework.beans.factory.aot.BeanRegistrationAotProcessor;
import org.springframework.beans.factory.aot.BeanRegistrationCode;
import org.springframework.beans.factory.support.RegisteredBean;
import org.springframework.core.DecoratingProxy;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.prepost.PreFilter;

/**
 * Recognize components that use any of {@link PostAuthorize}, {@link PostFilter},
 * {@link PreAuthorize} or {@link PreFilter} annotations and register proxies for them.
 *
 * @author Marcus Da Coregio
 * @since 6.0
 */
public class PrePostSecuredBeanRegistrationAotProcessor implements BeanRegistrationAotProcessor {

	private static Log logger = LogFactory.getLog(PrePostSecuredBeanRegistrationAotProcessor.class);

	@Override
	public BeanRegistrationAotContribution processAheadOfTime(RegisteredBean registeredBean) {
		Class<?> beanType = registeredBean.getBeanType().toClass();
		if (isPrePostSecured(beanType, SearchStrategy.TYPE_HIERARCHY)) {
			return new PrePostSecuredBeanRegistrationAotContribution(beanType);
		}
		return null;
	}

	private static boolean hasPrePostSecuredMethods(Class<?> type, SearchStrategy strategy) {
		for (Method method : type.getDeclaredMethods()) {
			MergedAnnotations methodAnnotations = MergedAnnotations.from(method, strategy);
			if (methodAnnotations.isPresent(PreAuthorize.class) || methodAnnotations.isPresent(PreFilter.class)
					|| methodAnnotations.isPresent(PostAuthorize.class) || methodAnnotations.isPresent(PostFilter.class)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isPrePostSecured(Class<?> type, SearchStrategy strategy) {
		MergedAnnotations typeAnnotations = MergedAnnotations.from(type, strategy);
		return (typeAnnotations.isPresent(PreAuthorize.class) || typeAnnotations.isPresent(PreFilter.class)
				|| typeAnnotations.isPresent(PostAuthorize.class) || typeAnnotations.isPresent(PostFilter.class)
				|| hasPrePostSecuredMethods(type, strategy));
	}

	private static class PrePostSecuredBeanRegistrationAotContribution implements BeanRegistrationAotContribution {

		private final Class<?> beanType;

		private PrePostSecuredBeanRegistrationAotContribution(Class<?> beanType) {
			this.beanType = beanType;
		}

		@Override
		public void applyTo(GenerationContext generationContext, BeanRegistrationCode beanRegistrationCode) {
			RuntimeHints runtimeHints = generationContext.getRuntimeHints();
			if (beanType.isInterface() || !isPrePostSecured(beanType, SearchStrategy.DIRECT)) {
				logger.debug("Registering " + beanType.getSimpleName());
				logger.debug("Registering interfaces " + Arrays.toString(beanType.getInterfaces()));
				List<Class<?>> prePostSecuredInterfaces = new ArrayList<>(Arrays.asList(beanType.getInterfaces()));
				if (prePostSecuredInterfaces.size() != 0) {
					prePostSecuredInterfaces.add(SpringProxy.class);
					prePostSecuredInterfaces.add(Advised.class);
					prePostSecuredInterfaces.add(DecoratingProxy.class);
					logger.debug(
							"Creating native JDKProxy configuration for these interfaces: " + prePostSecuredInterfaces);
					runtimeHints.proxies().registerJdkProxy(prePostSecuredInterfaces.toArray(new Class<?>[0]));
				}
			}
			else {
				logger.debug("Creating AOTProxy for this class: " + beanType.getName());
				runtimeHints.proxies().registerJdkProxy(SpringProxy.class, Advised.class, DecoratingProxy.class);
				runtimeHints.proxies().registerClassProxy(beanType, builder -> builder.proxiedInterfaces(beanType.getInterfaces()));
			}
		}

	}

}
