/*******************************************************************************
 * Copyright (c) 2016, 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.hono.adapter.lora.providers.downlink;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.eclipse.hono.adapter.lora.impl.LoraProtocolAdapter;
import org.eclipse.hono.adapter.lora.providers.LoraProviderDownlinkException;
import org.eclipse.hono.adapter.lora.providers.LoraUtils;
import org.eclipse.hono.cache.ExpiringValueCache;
import org.eclipse.hono.service.cache.SpringBasedExpiringValueCache;
import org.eclipse.hono.util.CredentialsObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static org.eclipse.hono.adapter.lora.LoraConstants.*;
import static org.eclipse.hono.util.Constants.JSON_FIELD_DEVICE_ID;
import static org.eclipse.hono.util.Constants.JSON_FIELD_TENANT_ID;
import static org.eclipse.hono.util.RequestResponseApiConstants.FIELD_PAYLOAD_DEVICE_ID;

/**
 * A LoRaWAN provider with base API for REST downlink commands.
 */
@Component
public abstract class RestDownlinkProvider {

    // Cached tokens will be invalidated already earlier than required to avoid edge cases.
    private static final int DEFAULT_DOWNLINK_TOKEN_PREEMPTIVE_INVALIDATION_TIME_IN_MS = 30_000;
    private static final String HEADER_BEARER_TOKEN = "Bearer";
    private static final Logger LOG = LoggerFactory.getLogger(LoraProtocolAdapter.class);

    private final CacheManager cacheManager;
    private final String className;

    private final ExpiringValueCache<String, String> sessionsCache;
    private int tokenPreemptiveInvalidationTimeInMs = DEFAULT_DOWNLINK_TOKEN_PREEMPTIVE_INVALIDATION_TIME_IN_MS;

    private final WebClient webClient;

    public RestDownlinkProvider(final Vertx vertx, final CacheManager cacheManager, final String className) {
        this.cacheManager = cacheManager;
        this.className = className;

        sessionsCache = new SpringBasedExpiringValueCache<>(cacheManager.getCache(className));

        final WebClientOptions options = new WebClientOptions();
        options.setTrustAll(true);

        this.webClient = WebClient.create(vertx, options);
    }

    protected Future<Void> sendDownlinkViaRest(final String bearerToken, final JsonObject gatewayDevice,
                                               final String targetDevice, final JsonObject message) {
        LOG.debug("Invoking downlink rest api for device '{}'", targetDevice);

        final Future<Void> result = Future.future();

        final String targetUri = getDownlinkRequestUri(gatewayDevice, targetDevice);

        webClient.postAbs(targetUri).putHeader("content-type", getDownlinkContentType())
                .putHeader("Authorization", getHeaderBearerToken() + " " + bearerToken)
                .sendJsonObject(message, response -> {
                    if (response.succeeded() && LoraUtils.isHttpSuccessStatusCode(response.result().statusCode())) {
                        LOG.debug("downlink rest api call for device '{}' was successful.", targetDevice);
                        result.complete();
                    } else if (response.succeeded() && response.result().statusCode() == HTTP_UNAUTHORIZED) {
                        LOG.debug(
                                "downlink rest api call for device '{}' failed because it was unauthorized. Response Body: '{}'",
                                targetDevice, response.result().bodyAsString());
                        invalidateCacheForGatewayDevice(gatewayDevice);
                        result.fail(new LoraProviderDownlinkException(
                                "Error invoking downlink provider api. Request was unauthorized."));
                    } else if (response.succeeded()) {
                        LOG.debug(
                                "Downlink rest api call for device '{}' returned unexpected status '{}'. Response Body: '{}'",
                                targetDevice, response.result().statusCode(), response.result().bodyAsString());
                        result.fail(new LoraProviderDownlinkException(
                                "Error invoking downlink provider api. Response Code of provider api was: "
                                        + response.result().statusCode()));
                    } else {
                        LOG.debug("Error invoking downlink rest api for device '{}'", targetDevice, response.cause());
                        result.fail(new LoraProviderDownlinkException("Error invoking downlink provider api.",
                                response.cause()));
                    }
                });

        return result;
    }

    protected String getHeaderBearerToken() {
        return HEADER_BEARER_TOKEN;
    }

    protected abstract String getDownlinkRequestUri(final JsonObject gatewayDevice, final String targetDevice);

    protected Future<String> getApiTokenFromCacheOrIssueNewFromLoraProvider(final JsonObject gatewayDevice,
                                                                            final CredentialsObject gatewayCredentials) {
        LOG.debug("A bearer token for gateway device '{}' with auth-id '{}' was requested",
                gatewayDevice.getString(FIELD_PAYLOAD_DEVICE_ID), gatewayCredentials.getAuthId());

        final String bearerToken = getCachedTokenForGatewayDevice(gatewayDevice);

        if (StringUtils.isEmpty(bearerToken)) {
            LOG.debug("No bearer token for gateway device '{}' and auth-id '{}' in cache. Will request a new one",
                    gatewayDevice.getString(FIELD_PAYLOAD_DEVICE_ID), gatewayCredentials.getAuthId());

            return getApiTokenFromLoraProvider(gatewayDevice, gatewayCredentials).compose(apiResponse -> {
                LOG.debug("Got bearer token for gateway device '{}' and auth-id '{}'.",
                        gatewayDevice.getString(FIELD_PAYLOAD_DEVICE_ID), gatewayCredentials.getAuthId());

                final String token = apiResponse.getString(getFieldToken());
                final Long tokenExpiryString = apiResponse.getLong(getFieldExpiryDate());
                final Instant tokenExpiry = Instant.ofEpochMilli(tokenExpiryString)
                        .minusMillis(getTokenPreemptiveInvalidationTimeInMs());

                if (Instant.now().isBefore(tokenExpiry)) {
                    putTokenForGatewayDeviceToCache(gatewayDevice, token, tokenExpiry);
                }

                return Future.succeededFuture(token);
            });
        } else {
            LOG.debug("Bearer token for gateway device '{}' and auth-id '{}' is in cache.",
                    gatewayDevice.getString(FIELD_PAYLOAD_DEVICE_ID), gatewayCredentials.getAuthId());
            return Future.succeededFuture(bearerToken);
        }
    }

    protected abstract String getFieldExpiryDate();

    protected abstract String getFieldToken();

    private Future<JsonObject> getApiTokenFromLoraProvider(final JsonObject gatewayDevice,
                                                           final CredentialsObject gatewayCredentials) {
        final List<JsonObject> currentlyValidSecrets = gatewayCredentials.getCandidateSecrets();

        LOG.debug("Got a total of {} valid secrets for gateway device '{}' and auth-id '{}'",
                currentlyValidSecrets.size(), gatewayDevice.getString(FIELD_PAYLOAD_DEVICE_ID),
                gatewayCredentials.getAuthId());

        // For now we didn't implement support for multiple valid secrets at the same time.
        final JsonObject currentSecret = currentlyValidSecrets.get(0);

        return requestApiTokenWithSecret(gatewayDevice, currentSecret);
    }

    private Future<JsonObject> requestApiTokenWithSecret(final JsonObject gatewayDevice, final JsonObject secret) {
        final Future<JsonObject> result = Future.future();

        final String loginUri = LoraUtils.getNormalizedProviderUrlFromGatewayDevice(gatewayDevice) + getDownlinkApiPathGetToken();

        final String passwordBase64 = secret.getString(FIELD_LORA_CREDENTIAL_KEY);
        final String password = new String(Base64.getDecoder().decode(passwordBase64));

        final JsonObject loginRequestPayload = new JsonObject();
        loginRequestPayload.put(getDownlinkFieldAuthLogin(), secret.getString(FIELD_LORA_CREDENTIAL_IDENTITY));
        loginRequestPayload.put(getDownlinkFieldAuthPassword(), password);

        LOG.debug("Going to obtain token for gateway device '{}' using url: '{}'",
                gatewayDevice.getString(FIELD_PAYLOAD_DEVICE_ID), loginUri);

        webClient.postAbs(loginUri).putHeader("content-type", getDownlinkContentType())
                .sendJsonObject(loginRequestPayload, response -> {
                    if (response.succeeded() && validateTokenResponse(response.result())) {
                        result.complete(response.result().bodyAsJsonObject());
                    } else {
                        LOG.debug("Error obtaining token for gateway device '{}' using url: '{}'",
                                gatewayDevice.getString(FIELD_PAYLOAD_DEVICE_ID), loginUri);
                        result.fail(new LoraProviderDownlinkException("Could not get authentication token for provider",
                                response.cause()));
                    }
                });
        return result;
    }

    private String getCachedTokenForGatewayDevice(final JsonObject gatewayDevice) {
        final String cacheId = getCacheIdForGatewayDevice(gatewayDevice);
        return sessionsCache.get(cacheId);
    }

    private void putTokenForGatewayDeviceToCache(final JsonObject gatewayDevice, final String token,
                                                 final Instant expiryDate) {
        final String cacheId = getCacheIdForGatewayDevice(gatewayDevice);
        LOG.debug("Going to put token to cache with id '{}'", cacheId);
        sessionsCache.put(cacheId, token, expiryDate);
    }

    private void invalidateCacheForGatewayDevice(final JsonObject gatewayDevice) {
        final String cacheId = getCacheIdForGatewayDevice(gatewayDevice);
        LOG.debug("Invalidating item in cache with id '{}'", cacheId);
        // Ugly to directly remove it from the underlaying cache, but Hono cache does not implement evict method yet.
        cacheManager.getCache(className).evict(cacheId);
    }

    private String getCacheIdForGatewayDevice(final JsonObject gatwayDevice) {
        return gatwayDevice.getString(JSON_FIELD_TENANT_ID) + "_" + gatwayDevice.getString(JSON_FIELD_DEVICE_ID) + "_"
                + LoraUtils.getLoraConfigFromLoraGatewayDevice(gatwayDevice).getString(FIELD_AUTH_ID);
    }

    private boolean validateTokenResponse(final HttpResponse<Buffer> response) {
        if (!LoraUtils.isHttpSuccessStatusCode(response.statusCode())) {
            LOG.debug("Received non success status code: '{}' from api.", response.statusCode());
            return false;
        }

        final JsonObject apiResponse;

        try {
            apiResponse = response.bodyAsJsonObject();
        } catch (final DecodeException e) {
            LOG.debug("Received non json object from api with data.", e);
            return false;
        }

        final String token;
        try {
            token = apiResponse.getString(getFieldToken());
        } catch (final ClassCastException e) {
            LOG.debug("Received token with invalid syntax from api.");
            return false;
        }

        if (StringUtils.isEmpty(token)) {
            LOG.debug("Received token with invalid syntax from api.");
            return false;
        }

        final Long expiryDate;
        try {
            expiryDate = apiResponse.getLong(getFieldExpiryDate());
        } catch (final ClassCastException e) {
            LOG.debug("Received expiry date with invalid syntax from api.");
            return false;
        }

        if (expiryDate == null) {
            LOG.debug("Received token without expiryDate from api.");
            return false;
        }

        return true;
    }

    int getTokenPreemptiveInvalidationTimeInMs() {
        return tokenPreemptiveInvalidationTimeInMs;
    }

    public void setTokenPreemptiveInvalidationTimeInMs(final int time) {
        this.tokenPreemptiveInvalidationTimeInMs = time;
    }

    protected abstract String getDownlinkContentType();
    protected abstract String getDownlinkApiPathGetToken();
    protected abstract String getDownlinkFieldAuthPassword();
    protected abstract String getDownlinkFieldAuthLogin();
}
