package com.overload.overload_server.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "overload.gif", name = "serving-enabled", havingValue = "true")
class ExerciseGifAuthorizationService {
    enum Decision { ALLOWED, FORBIDDEN, NOT_FOUND }

    private static final Pattern SOURCE_ID = Pattern.compile("[A-Za-z0-9]{7}");
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{64}");

    private final ExerciseSourceRepository sources;
    private final byte[] token;

    ExerciseGifAuthorizationService(ExerciseSourceRepository sources, @Value("${overload.gif.proxy-token}") String token) {
        if (!TOKEN.matcher(token).matches()) throw new IllegalStateException("A 64-character hex GIF proxy token is required");
        this.sources = sources;
        this.token = token.getBytes(StandardCharsets.US_ASCII);
    }

    Decision authorize(UUID userId, String suppliedToken, String sourceId) {
        if (suppliedToken == null || !TOKEN.matcher(suppliedToken).matches()
                || !MessageDigest.isEqual(token, suppliedToken.getBytes(StandardCharsets.US_ASCII))) return Decision.FORBIDDEN;
        if (sourceId == null || !SOURCE_ID.matcher(sourceId).matches()) return Decision.NOT_FOUND;
        var status = sources.gifAccess(userId, sourceId);
        if (!status.getMemberAllowed()) return Decision.FORBIDDEN;
        return status.getCurrentRepresentative() ? Decision.ALLOWED : Decision.NOT_FOUND;
    }
}
