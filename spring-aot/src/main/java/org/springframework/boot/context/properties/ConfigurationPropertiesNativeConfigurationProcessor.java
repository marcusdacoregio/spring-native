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

package org.springframework.boot.context.properties;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.aot.context.bootstrap.generator.infrastructure.nativex.BeanFactoryNativeConfigurationProcessor;
import org.springframework.aot.context.bootstrap.generator.infrastructure.nativex.DefaultNativeReflectionEntry.Builder;
import org.springframework.aot.context.bootstrap.generator.infrastructure.nativex.NativeConfigurationRegistry;
import org.springframework.beans.BeanInfoFactory;
import org.springframework.beans.ExtendedBeanInfoFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.nativex.hint.TypeAccess;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * A {@link BeanFactoryNativeConfigurationProcessor} that allows reflection access on
 * all declared methods of {@link ConfigurationProperties @ConfigurationProperties}
 * annotated types, their nested types and any complex types that are exposed as a sub-
 * namespace.
 *
 * @author Stephane Nicoll
 * @author Christoph Strobl
 * @author Sebastien Deleuze
 */
class ConfigurationPropertiesNativeConfigurationProcessor implements BeanFactoryNativeConfigurationProcessor {

	@Override
	public void process(ConfigurableListableBeanFactory beanFactory, NativeConfigurationRegistry registry) {
		String[] beanNames = beanFactory.getBeanNamesForAnnotation(ConfigurationProperties.class);
		for (String beanName : beanNames) {
			processConfigurationProperties(registry, beanFactory.getMergedBeanDefinition(beanName));
		}
	}

	private void processConfigurationProperties(NativeConfigurationRegistry registry, BeanDefinition beanDefinition) {
		Class<?> type = ClassUtils.getUserClass(beanDefinition.getResolvableType().toClass());
		TypeProcessor.processConfigurationProperties(type, registry);
	}

	/**
	 * Process a given type for binding purposes, discovering any nested type it may
	 * expose via a property.
	 */
	private static class TypeProcessor {

		private static final BeanInfoFactory beanInfoFactory = new ExtendedBeanInfoFactory();

		private final Class<?> type;

		private final Constructor<?> bindConstructor;

		private final BeanInfo beanInfo;

		private final Set<Class<?>> seen;

		private TypeProcessor(Class<?> type, Constructor<?> bindConstructor, Set<Class<?>> seen) {
			this.type = type;
			this.bindConstructor = bindConstructor;
			this.beanInfo = getBeanInfo(type);
			this.seen = seen;
		}

		public static void processConfigurationProperties(Class<?> type, NativeConfigurationRegistry registry) {
			new TypeProcessor(type, getBindConstructor(type, false), new HashSet<>()).process(registry);
		}

		private void processNestedType(Class<?> type, NativeConfigurationRegistry registry) {
			processNestedType(type, getBindConstructor(type, true), registry);
		}

		private void processNestedType(Class<?> type, Constructor<?> bindConstructor, NativeConfigurationRegistry registry) {
			new TypeProcessor(type, bindConstructor, this.seen).process(registry);
		}

		@Nullable
		private static Constructor<?> getBindConstructor(Class<?> type, boolean nestedType) {
			Bindable<?> bindable = Bindable.of(type);
			return ConfigurationPropertiesBindConstructorProvider.INSTANCE
					.getBindConstructor(bindable, nestedType);
		}

		private void process(NativeConfigurationRegistry registry) {
			if (this.seen.contains(this.type)) {
				return;
			}
			this.seen.add(this.type);
			Builder reflection = registry.reflection().forType(this.type);
			if (isClassOnlyReflectionType()) {
				return;
			}
			// Flag.allPublicMethods required to handle inherited methods
			reflection.withAccess(TypeAccess.DECLARED_METHODS, TypeAccess.PUBLIC_METHODS);
			handleConstructor(reflection);
			if (this.bindConstructor != null) {
				handleValueObjectProperties(registry);
			}
			else if (this.beanInfo != null) {
				handleJavaBeanProperties(registry);
			}
		}

		private boolean isClassOnlyReflectionType() {
			return this.type.getPackageName().startsWith("java.") ||
					this.type.isArray() ||
					this.type.equals(ApplicationContext.class) ||
					this.type.equals(Environment.class);
		}

		private void handleConstructor(Builder reflection) {
			if (this.bindConstructor != null) {
				reflection.withExecutables(this.bindConstructor);
			}
			else {
				Constructor<?>[] allConstructors = this.type.getDeclaredConstructors();
				if (allConstructors.length == 1) {
					reflection.withExecutables(allConstructors[0]);
				}
				else {
					try {
						reflection.withExecutables(this.type.getDeclaredConstructor());
					}
					catch (NoSuchMethodException ex) {
						reflection.withAccess(TypeAccess.DECLARED_CONSTRUCTORS);
					}
				}
			}
		}

		private void handleValueObjectProperties(NativeConfigurationRegistry registry) {
			for (int i = 0; i < this.bindConstructor.getParameterCount(); i++) {
				ResolvableType propertyType = ResolvableType.forConstructorParameter(this.bindConstructor, i);
				Class<?> propertyClass = propertyType.resolve();
				if (propertyClass == null || propertyClass.equals(beanInfo.getBeanDescriptor().getBeanClass())) {
					return; // Prevent infinite recursion
				}
				processNestedType(propertyClass, registry);
				Class<?> nestedType = getNestedType(this.bindConstructor.getParameters()[i].getName(), propertyType);
				if (nestedType != null) {
					processNestedType(nestedType, registry);
				}
			}
		}

		private void handleJavaBeanProperties(NativeConfigurationRegistry registry) {
			for (PropertyDescriptor propertyDescriptor : beanInfo.getPropertyDescriptors()) {
				Method readMethod = propertyDescriptor.getReadMethod();
				if (readMethod != null && !readMethod.getName().equals("getClass")) {
					ResolvableType propertyType = ResolvableType.forMethodReturnType(readMethod, this.type);
					Class<?> propertyClass = propertyType.toClass();
					if (propertyClass.equals(beanInfo.getBeanDescriptor().getBeanClass())) {
						return; // Prevent infinite recursion
					}
					processNestedType(propertyClass, registry);
					Class<?> nestedType = getNestedType(propertyDescriptor.getName(), propertyType);
					if (nestedType != null) {
						processNestedType(nestedType, registry);
					}
				}
			}
		}

		private Class<?> getNestedType(String propertyName, ResolvableType propertyType) {
			Class<?> propertyClass = propertyType.toClass();
			if (propertyType.isArray()) {
				return propertyType.getComponentType().toClass();
			}
			else if (Collection.class.isAssignableFrom(propertyClass)) {
				return propertyType.as(Collection.class).getGeneric(0).toClass();
			}
			else if (Map.class.isAssignableFrom(propertyClass)) {
				return propertyType.as(Map.class).getGeneric(1).toClass();
			}
			else if (this.type.equals(propertyClass.getDeclaringClass())) {
				return propertyClass;
			}
			else {
				Field field = ReflectionUtils.findField(this.type, propertyName);
				if (field != null && isNestedConfigurationProperties(field)) {
					return propertyClass;
				}
			}
			return null;
		}

		private boolean isNestedConfigurationProperties(Field field) {
			return MergedAnnotations.from(field).isPresent(NestedConfigurationProperty.class);
		}

		private static BeanInfo getBeanInfo(Class<?> beanType) {
			try {
				BeanInfo beanInfo = beanInfoFactory.getBeanInfo(beanType);
				if (beanInfo != null) {
					return beanInfo;
				}
				return Introspector.getBeanInfo(beanType, Introspector.IGNORE_ALL_BEANINFO);
			}
			catch (IntrospectionException ex) {
				return null;
			}
		}

	}

}
