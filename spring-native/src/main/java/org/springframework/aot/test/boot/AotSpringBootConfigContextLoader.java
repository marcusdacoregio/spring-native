/*
 * Copyright 2019-2022 the original author or authors.
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

package org.springframework.aot.test.boot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.aot.SpringApplicationAotUtils;
import org.springframework.boot.ApplicationContextFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.test.context.SpringBootContextLoader;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.mock.web.SpringBootMockServletContext;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.test.util.TestPropertyValues.Type;
import org.springframework.boot.web.reactive.context.GenericReactiveWebApplicationContext;
import org.springframework.boot.web.servlet.support.ServletContextApplicationContextInitializer;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.SmartContextLoader;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.context.web.WebMergedContextConfiguration;
import org.springframework.util.ObjectUtils;
import org.springframework.web.context.support.GenericWebApplicationContext;

/**
 * A {@link SmartContextLoader} that use an {@link ApplicationContextInitializer}
 * to bootstrap a Spring Boot test. This includes support for {@code @SpringBootTest} as
 * well as slice tests.
 *
 * @author Stephane Nicoll
 */
public class AotSpringBootConfigContextLoader extends SpringBootContextLoader {

	private final Class<? extends ApplicationContextInitializer<?>> testContextInitializer;

	private final WebApplicationType webApplicationType;

	private final WebEnvironment webEnvironment;

	private final String[] args;

	/**
	 * Create an instance for the specified {@link ApplicationContextInitializer} and
	 * web-related details.
	 * @param testContextInitializer the context initializer to use
	 * @param webApplicationType the {@link WebApplicationType} to use for the context
	 * @param webEnvironment the {@link WebEnvironment} to use for the context
	 * @param args the command line arguments
	 */
	public AotSpringBootConfigContextLoader(Class<? extends ApplicationContextInitializer<?>> testContextInitializer,
			WebApplicationType webApplicationType, WebEnvironment webEnvironment, String... args) {
		this.testContextInitializer = testContextInitializer;
		this.webApplicationType = webApplicationType;
		this.webEnvironment = webEnvironment;
		this.args = args;
	}

	/**
	 * Create a new instance using the specified {@link ApplicationContextInitializer} for
	 * a non-web context.
	 * @param testContextInitializer the context initializer to use
	 * @param args the command line arguments
	 */
	public AotSpringBootConfigContextLoader(Class<? extends ApplicationContextInitializer<?>> testContextInitializer, String... args) {
		this(testContextInitializer, WebApplicationType.NONE, WebEnvironment.NONE, args);
	}

	@Override
	public ConfigurableApplicationContext loadContext(MergedContextConfiguration config) {
		SpringApplication application = new AotTestSpringApplication(config.getTestClass().getClassLoader(), testContextInitializer);
		application.setMainApplicationClass(config.getTestClass());
		application.setSources(Collections.singleton(testContextInitializer.getName()));
		ConfigurableEnvironment environment = getEnvironment();
		if (environment != null) {
			prepareEnvironment(config, application, environment, false);
			application.setEnvironment(environment);
		}
		else {
			application.addListeners(new PrepareEnvironmentListener(config));
		}
		application.setApplicationContextFactory(SpringApplicationAotUtils.AOT_FACTORY);
		application.setWebApplicationType(this.webApplicationType);

		if (!isEmbeddedWebEnvironment()) {
			if (this.webApplicationType == WebApplicationType.SERVLET) {
				List<ApplicationContextInitializer<?>> initializers = new ArrayList<>(application.getInitializers());
				new WebConfigurer().configure(config, application, initializers);
				application.setInitializers(initializers);
			}
			else if (this.webApplicationType == WebApplicationType.REACTIVE) {
				application.setApplicationContextFactory(
						ApplicationContextFactory.of(GenericReactiveWebApplicationContext::new));
			}
		}
		return application.run(this.args);
	}

	private boolean isEmbeddedWebEnvironment() {
		return this.webEnvironment.isEmbedded();
	}

	// Copy of SpringBootContextLoader
	private void prepareEnvironment(MergedContextConfiguration config, SpringApplication application,
			ConfigurableEnvironment environment, boolean applicationEnvironment) {
		setActiveProfiles(environment, config.getActiveProfiles(), applicationEnvironment);
		ResourceLoader resourceLoader = (application.getResourceLoader() != null) ? application.getResourceLoader()
				: new DefaultResourceLoader(null);
		TestPropertySourceUtils.addPropertiesFilesToEnvironment(environment, resourceLoader,
				config.getPropertySourceLocations());
		TestPropertySourceUtils.addInlinedPropertiesToEnvironment(environment, getInlinedProperties(config));
	}

	private void setActiveProfiles(ConfigurableEnvironment environment, String[] profiles, boolean applicationEnvironment) {
		if (!ObjectUtils.isEmpty(profiles)) {
			if (!applicationEnvironment) {
				environment.setActiveProfiles(profiles);
			}
			String[] pairs = new String[profiles.length];
			for(int i = 0; i < profiles.length; ++i) {
				pairs[i] = "spring.profiles.active[" + i + "]=" + profiles[i];
			}
			TestPropertyValues.of(pairs).applyTo(environment, Type.MAP, "active-test-profiles");
		}
	}

	/**
	 * {@link ApplicationListener} used to prepare the application created environment.
	 */
	private class PrepareEnvironmentListener
			implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, PriorityOrdered {

		private final MergedContextConfiguration config;

		PrepareEnvironmentListener(MergedContextConfiguration config) {
			this.config = config;
		}

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE;
		}

		@Override
		public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
			prepareEnvironment(this.config, event.getSpringApplication(), event.getEnvironment(), true);
		}

	}

	private static class WebConfigurer {

		void configure(MergedContextConfiguration configuration, SpringApplication application,
				List<ApplicationContextInitializer<?>> initializers) {
			WebMergedContextConfiguration webConfiguration = (WebMergedContextConfiguration) configuration;
			addMockServletContext(initializers, webConfiguration);
			application.setApplicationContextFactory((webApplicationType) -> new GenericWebApplicationContext());
		}

		private void addMockServletContext(List<ApplicationContextInitializer<?>> initializers,
				WebMergedContextConfiguration webConfiguration) {
			SpringBootMockServletContext servletContext = new SpringBootMockServletContext(
					webConfiguration.getResourceBasePath());
			initializers.add(0, new ServletContextApplicationContextInitializer(servletContext, true));
		}

	}

}
