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

package org.eclipse.hono.adapter.lora.providers;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.eclipse.hono.adapter.lora.LoraConstants;
import org.eclipse.hono.adapter.lora.LoraMessageType;
import org.eclipse.hono.adapter.lora.impl.LoraProtocolAdapter;
import org.eclipse.hono.adapter.lora.providers.downlink.RestDownlinkProvider;
import org.eclipse.hono.client.Command;
import org.eclipse.hono.service.http.HttpUtils;
import org.eclipse.hono.util.CredentialsObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.text.MessageFormat;
import java.time.Instant;
import java.util.Base64;

import static org.eclipse.hono.adapter.lora.LoraConstants.*;
import static org.eclipse.hono.util.RequestResponseApiConstants.FIELD_PAYLOAD_DEVICE_ID;

/**
 * A LoRaWAN provider with API for Actility.
 */
@Component
public class ActilityProvider extends RestDownlinkProvider implements LoraProvider {

    private static final String FIELD_ACTILITY_ROOT_OBJECT = "DevEUI_uplink";
    private static final String FIELD_ACTILITY_DEVICE_EUI = "DevEUI";
    private static final String FIELD_ACTILITY_PAYLOAD = "payload_hex";

    private static final String FIELD_DOWNLINK_PORT = "targetPorts";
    private static final String FIELD_DOWNLINK_PAYLOAD = "payloadHex";
    private static final String FIELD_DOWNLINK_CONTENT_TYPE = "contentType";
    private static final String FIELD_DOWNLINK_ACK = "ack";

    private static final String VALUE_DOWNLINK_CONTENT_TYPE_HEXA = "HEXA";

    private static final String API_PATH_GET_TOKEN = "/admin/latest/api/oauth/token?renewToken=true&validityPeriod=5minutes";
    private static final String API_PATH_DOWNLINK_MESSAGE = "/core/latest/api/devices/{0}/downlinkMessages";

    private static final Logger LOG = LoggerFactory.getLogger(LoraProtocolAdapter.class);

    private static final String FIELD_ACTILITY_TOKEN = "access_token";
    private static final String FIELD_ACILITY_EXPIRY_DATE = "expires_in";

    private static final String FIELD_ACTILITY_AUTH_LOGIN = "client_id";
    private static final String FIELD_ACTILITY_AUTH_PASSWORD = "client_secret";
    private static final String ACTILITY_TOKEN_GRANT_TYPE = "client_credentials";

    public ActilityProvider(Vertx vertx, CacheManager cacheManager) {
        super(vertx, cacheManager, ActilityProvider.class.getName());
    }

    @Override
    public String getProviderName() { return "actility"; }

    @Override
    public String pathPrefix() {
        return "/actility";
    }

    @Override
    public String extractDeviceId(final JsonObject loraMessage) {
        return loraMessage.getJsonObject(FIELD_ACTILITY_ROOT_OBJECT, new JsonObject())
                .getString(FIELD_ACTILITY_DEVICE_EUI);
    }

    @Override
    public String extractPayloadEncodedInBase64(final JsonObject loraMessage) {
        final String hexPayload = loraMessage.getJsonObject(FIELD_ACTILITY_ROOT_OBJECT, new JsonObject())
                .getString(FIELD_ACTILITY_PAYLOAD);

        return LoraUtils.convertFromHexToBase64(hexPayload);
    }

    @Override
    public LoraMessageType extractMessageType(final JsonObject loraMessage) {
        final String[] messageKeys = loraMessage.getMap().keySet().toArray(new String[0]);
        if (messageKeys.length > 0 && FIELD_ACTILITY_ROOT_OBJECT.equals(messageKeys[0])) {
            return LoraMessageType.UPLINK;
        }
        return LoraMessageType.UNKNOWN;
    }

    @Override
    public Future<Void> sendDownlinkCommand(final JsonObject gatewayDevice, final CredentialsObject gatewayCredential,
                                            final String targetDeviceId, final Command loraCommand) {
        LOG.info("Send downlink command for device '{}' using gateway '{}'", targetDeviceId,
                gatewayDevice.getString(FIELD_PAYLOAD_DEVICE_ID));

        if (!isValidDownlinkActilityGateway(gatewayDevice)) {
            LOG.info(
                    "Can't send downlink command for device '{}' using gateway '{}' because of invalid gateway configuration.",
                    targetDeviceId, gatewayDevice.getString(FIELD_PAYLOAD_DEVICE_ID));
            return Future.failedFuture(new LoraProviderDownlinkException("LoRa configuration is not valid."));
        }

        final Future<String> apiToken = getApiTokenFromCacheOrIssueNewFromLoraProvider(gatewayDevice,
                gatewayCredential);

        return apiToken.compose(token -> {
            LOG.info("Sending downlink command via rest api for device '{}' using gateway '{}' and resolved token",
                    targetDeviceId, gatewayDevice.getString(FIELD_PAYLOAD_DEVICE_ID));

            final JsonObject loraPayload = loraCommand.getPayload().toJsonObject();

            final String payloadBase64 = loraPayload.getString(LoraConstants.FIELD_LORA_DOWNLINK_PAYLOAD);

            final String payloadHex = LoraUtils.convertFromBase64ToHex(payloadBase64);


            final JsonObject txMessage = getJsonMessage(gatewayDevice, payloadHex);

            return sendDownlinkViaRest(token, gatewayDevice, targetDeviceId, txMessage);
        });
    }

    private JsonObject getJsonMessage(final JsonObject gatewayDevice, final String payloadHex) {
        final JsonObject loraProperties = LoraUtils.getLoraConfigFromLoraGatewayDevice(gatewayDevice);
        final int port = loraProperties.getInteger(FIELD_LORA_DEVICE_PORT);

        final JsonObject txMessage = new JsonObject();
        txMessage.put(FIELD_DOWNLINK_PORT, port);
        txMessage.put(FIELD_DOWNLINK_PAYLOAD, payloadHex);

        return txMessage;
    }

    private boolean isValidDownlinkActilityGateway(final JsonObject gatewayDevice) {
        final JsonObject loraConfig = LoraUtils.getLoraConfigFromLoraGatewayDevice(gatewayDevice);
        if (loraConfig == null) {
            return false;
        }
        return true;
    }

    @Override
    protected String getDownlinkRequestUri(final JsonObject gatewayDevice, final String targetDevice) {
        final String hostName = LoraUtils.getNormalizedProviderUrlFromGatewayDevice(gatewayDevice);

        final String downlinkUrlPath = MessageFormat.format(API_PATH_DOWNLINK_MESSAGE, targetDevice);
        final String targetUrl = hostName + downlinkUrlPath;

        LOG.debug("Invoking downlink rest api using url '{}' for device '{}'", targetUrl, targetDevice);

        return targetUrl;
    }

    @Override
    protected Instant GetTokenExpiryInstant(JsonObject apiResponse) {
        final Long tokenExpiryString = apiResponse.getLong(getFieldExpiryDate());
        return Instant.ofEpochMilli(tokenExpiryString);
    }

    @Override
    protected String getFieldExpiryDate() { return FIELD_ACILITY_EXPIRY_DATE; }

    @Override
    protected String getFieldToken() { return FIELD_ACTILITY_TOKEN; }

    @Override
    protected String getDownlinkContentType() { return HttpUtils.CONTENT_TYPE_JSON; }

    @Override
    protected String getDownlinkApiPathGetToken() { return API_PATH_GET_TOKEN; }

    @Override
    protected String getDownlinkFieldAuthPassword() {
        return FIELD_ACTILITY_AUTH_PASSWORD;
    }

    @Override
    protected String getDownlinkFieldAuthLogin() {
        return FIELD_ACTILITY_AUTH_LOGIN;
    }

    @Override
    protected Future<JsonObject> requestApiTokenWithSecret(final JsonObject gatewayDevice, final JsonObject secret) {
        final Future<JsonObject> result = Future.future();

        final String loginUri = LoraUtils.getNormalizedProviderUrlFromGatewayDevice(gatewayDevice) + getDownlinkApiPathGetToken();

        final String passwordBase64 = secret.getString(FIELD_LORA_CREDENTIAL_KEY);
        final String password = new String(Base64.getDecoder().decode(passwordBase64));

        final String loginRequestPayload = String.format("grant_type=%s&client_id=%s&client_secret=%s", ACTILITY_TOKEN_GRANT_TYPE, secret.getString(FIELD_LORA_CREDENTIAL_IDENTITY), password);

        LOG.debug("Going to obtain token for gateway device '{}' using url: '{}'",
                gatewayDevice.getString(FIELD_PAYLOAD_DEVICE_ID), loginUri);

        webClient.postAbs(loginUri).putHeader("content-type", getDownlinkContentType())
                .sendBuffer(Buffer.buffer(loginRequestPayload), response -> {
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
}
