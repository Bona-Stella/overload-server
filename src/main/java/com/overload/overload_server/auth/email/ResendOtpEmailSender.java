package com.overload.overload_server.auth.email;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class ResendOtpEmailSender implements OtpEmailSender {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final boolean enabled;
	private final String apiKey;
	private final String from;
	private final HttpClient client;
	private final URI endpoint;
	private final Duration timeout;

	ResendOtpEmailSender(boolean enabled, String apiKey, String from, HttpClient client) {
		this(enabled, apiKey, from, client, URI.create("https://api.resend.com/emails"), Duration.ofSeconds(5));
	}

	ResendOtpEmailSender(boolean enabled, String apiKey, String from, HttpClient client, URI endpoint, Duration timeout) {
		this.enabled = enabled;
		this.apiKey = apiKey;
		this.from = from;
		this.client = client;
		this.endpoint = endpoint;
		this.timeout = timeout;
	}

	@Override
	public boolean ready() {
		return enabled && !apiKey.isBlank() && !from.isBlank();
	}

	@Override
	public void send(Message message) {
		if (!ready()) {
			throw new OtpFailure(503, "OTP_UNAVAILABLE");
		}
		boolean reauth = message.purpose() == OtpPurpose.REAUTH;
		boolean enrollment = message.purpose() == OtpPurpose.CONTACT_ENROLL;
		var body = JSON.createObjectNode().put("from", from)
				.put("subject", enrollment ? "Overload 재인증 이메일 등록 코드" : reauth ? "Overload 이메일 재인증 코드" : "Overload 로그인 인증 코드")
				.put("text", (reauth ? "이메일 재인증 코드: " : "인증 코드: ") + message.code()
						+ "\n5분 안에 입력하세요. 요청하지 않았다면 무시하세요.");
		body.putArray("to").add(message.recipient());
		var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
				.header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
				.header("Idempotency-Key", (enrollment ? "email-otp/contact-enroll/" : reauth ? "email-otp/reauth/" : "email-otp/") + message.issuanceId())
				.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
		try {
			var response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new OtpFailure(503, "OTP_UNAVAILABLE");
			}
			var id = JSON.readTree(response.body()).path("id");
			if (!id.isString() || id.asString().isBlank()) {
				throw new OtpFailure(503, "OTP_UNAVAILABLE");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new OtpFailure(503, "OTP_UNAVAILABLE");
		} catch (IOException | tools.jackson.core.JacksonException exception) {
			// Never expose provider bodies, addresses, codes, authorization headers or low-level causes.
			throw new OtpFailure(503, "OTP_UNAVAILABLE");
		}
	}

}
