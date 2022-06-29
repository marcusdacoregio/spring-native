package com.example.methodsecurity;

import org.thymeleaf.spring6.expression.SpringStandardExpressionObjectFactory;
import org.thymeleaf.spring6.view.ThymeleafView;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

@SpringBootApplication
@ImportRuntimeHints(MethodSecurityApplication.ThymeleafRuntimeHints.class)
public class MethodSecurityApplication {

	public static void main(String[] args) throws Throwable {
		SpringApplication.run(MethodSecurityApplication.class, args);
	}

	static class ThymeleafRuntimeHints implements RuntimeHintsRegistrar {

		@Override
		public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
			hints.reflection().registerType(ThymeleafView.class, builder -> builder.withMembers(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS));
			hints.reflection().registerType(SpringStandardExpressionObjectFactory.class, builder -> builder.withMembers(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS));
			hints.reflection().registerType(TypeReference.of("org.thymeleaf.spring6.expression.Mvc$Spring41MvcUriComponentsBuilderDelegate"), builder -> builder
					.withMembers(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS));
			hints.resources().registerPattern("*.html");
		}

	}

}
