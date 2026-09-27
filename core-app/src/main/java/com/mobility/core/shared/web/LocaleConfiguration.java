package com.mobility.core.shared.web;

import java.util.Arrays;

import com.mobility.core.shared.i18n.Language;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

@Configuration(proxyBeanMethods = false)
class LocaleConfiguration {

	/** Resolves km or en from Accept-Language; anything else (or no header) gets Khmer. */
	@Bean
	LocaleResolver localeResolver() {
		AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
		resolver.setSupportedLocales(Arrays.stream(Language.values()).map(Language::locale).toList());
		resolver.setDefaultLocale(Language.DEFAULT.locale());
		return resolver;
	}
}
