package com.overload.overload_server.security;

import com.overload.overload_server.auth.google.GoogleOidcSecurity;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.session.web.http.CookieSerializer;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

	private final ObjectProvider<GoogleOidcSecurity> google;

	public SecurityConfiguration(ObjectProvider<GoogleOidcSecurity> google) {
		this.google = google;
	}

	@Bean
	@ConditionalOnMissingBean(Clock.class)
	Clock securityClock() {
		return Clock.systemUTC();
	}

	@Bean
	SessionPolicy sessionPolicy(Clock clock) {
		return new SessionPolicy(clock);
	}

	@Bean
	HttpSessionCsrfTokenRepository csrfTokenRepository() {
		return new HttpSessionCsrfTokenRepository();
	}

	@Bean
	HttpSessionSecurityContextRepository securityContextRepository() {
		var repository = new HttpSessionSecurityContextRepository();
		repository.setDisableUrlRewriting(true);
		return repository;
	}

	@Bean
	AuthenticationSessionService authenticationSessionService(SessionPolicy policy, HttpSessionCsrfTokenRepository csrf,
			HttpSessionSecurityContextRepository contexts) {
		return new AuthenticationSessionService(policy, new CompositeSessionAuthenticationStrategy(List.of(
				new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf))), contexts);
	}

	@Bean
	CookieSerializer sessionCookieSerializer(SessionPolicy policy,
			@Value("${overload.session.cookie.secure:true}") boolean secure, Environment environment) {
		boolean local = environment.acceptsProfiles(Profiles.of("local"))
				&& !environment.acceptsProfiles(Profiles.of("production", "prod"));
		return new AbsoluteExpiryCookieSerializer(policy, !local || secure);
	}

	@Bean
	AuthenticationManager authenticationManager() {
		// Block generated/password users. OIDC providers are installed only in the explicitly enabled HTTP chain.
		return authentication -> { throw new ProviderNotFoundException("No login provider configured"); };
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, SessionPolicy policy,
			HttpSessionCsrfTokenRepository csrf, HttpSessionSecurityContextRepository contexts) throws Exception {
		AccessDeniedHandler denied = (request, response, exception) -> {
			var authentication = SecurityContextHolder.getContext().getAuthentication();
			boolean anonymous = authentication == null || !authentication.isAuthenticated()
					|| authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken;
			writeError(response, anonymous ? 401 : 403, anonymous ? "AUTHENTICATION_REQUIRED"
					: exception instanceof CsrfException ? "CSRF_INVALID" : "ACCESS_DENIED");
		};
		http.formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
				.requestCache(cache -> cache.requestCache(new NullRequestCache()))
				.securityContext(context -> context.securityContextRepository(contexts).requireExplicitSave(true))
				.csrf(config -> config.csrfTokenRepository(csrf).ignoringRequestMatchers(request ->
						"POST".equals(request.getMethod())
								&& (request.getContextPath() + "/api/v1/health").equals(request.getRequestURI())))
				.authorizeHttpRequests(authorize -> authorize.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers("/api/v1/health").permitAll()
						.requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
						.requestMatchers(org.springframework.http.HttpMethod.GET, "/oauth2/authorization/google",
								"/login/oauth2/code/google", "/auth/google/failure", "/auth/google/reauth/failure", "/auth/google/contact/failure", "/auth/google/link/failure").permitAll()
						.requestMatchers(org.springframework.http.HttpMethod.POST,
								"/api/v1/auth/email-otp/request", "/api/v1/auth/email-otp/verify").permitAll()
						.requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/exercises").denyAll()
						.requestMatchers(org.springframework.http.HttpMethod.PATCH, "/api/v1/exercises/*").denyAll()
						.anyRequest().authenticated())
				.exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) ->
						writeError(response, 401, "AUTHENTICATION_REQUIRED")).accessDeniedHandler(denied))
				.logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
						.logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
				.addFilterAfter(new SessionExpiryFilter(policy), SecurityContextHolderFilter.class)
				.addFilterAfter(new ReauthenticationResponseFilter(), SessionExpiryFilter.class);
		var oidc = google.getIfAvailable();
		if (oidc != null) {
			oidc.configure(http);
		}
		return http.build();
	}

	private static void writeError(HttpServletResponse response, int status, String code) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.setHeader("Cache-Control", "no-store");
		response.getWriter().write("{\"code\":\"" + code + "\"}");
	}

}
