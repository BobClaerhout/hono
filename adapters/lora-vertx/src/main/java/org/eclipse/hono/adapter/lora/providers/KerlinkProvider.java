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

import static org.eclipse.hono.adapter.lora.LoraConstants.*;
import static org.eclipse.hono.util.RequestResponseApiConstants.FIELD_PAYLOAD_DEVICE_ID;

import org.eclipse.hono.adapter.lora.LoraConstants;
import org.eclipse.hono.adapter.lora.impl.LoraProtocolAdapter;
import org.eclipse.hono.adapter.lora.providers.downlink.RestDownlinkProvider;
import org.eclipse.hono.client.Command;
import org.eclipse.hono.util.CredentialsObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.text.MessageFormat;

/**
 * A LoRaWAN provider with API for Kerlink.
 */
@Component
public class KerlinkProvider extends RestDownlinkProvider implements LoraProvider {

    static final String FIELD_KERLINK_CLUSTER_ID = "cluster-id";
    static final String FIELD_KERLINK_CUSTOMER_ID = "customer-id";

    private static final Logger LOG = LoggerFactory.getLogger(LoraProtocolAdapter.class);

    private static final String HEADER_CONTENT_TYPE_KERLINK_JSON = "application/vnd.kerlink.iot-v1+json";


    private static final String API_PATH_GET_TOKEN = "/oss/application/login";
    private static final String API_PATH_TX_MESSAGE = "/oss/application/customers/{0}/clusters/{1}/endpoints/{2}/txMessages";

    private static final String FIELD_UPLINK_DEVICE_EUI = "devEui";
    private static final String FIELD_UPLINK_USER_DATA = "userdata";
    private static final String FIELD_UPLINK_PAYLOAD = "payload";

    private static final String FIELD_DOWNLINK_PORT = "port";
    private static final String FIELD_DOWNLINK_PAYLOAD = "payload";
    private static final String FIELD_DOWNLINK_CONTENT_TYPE = "contentType";
    private static final String FIELD_DOWNLINK_ACK = "ack";

    private static final String VALUE_DOWNLINK_CONTENT_TYPE_HEXA = "HEXA";

    private static final String FIELD_KERLINK_AUTH_LOGIN = "login";

    private static final String FIELD_KERLINK_AUTH_PASSWORD = "password";

    private static final String FIELD_KERLINK_EXPIRY_DATE = "expiredDate";
    private static final String FIELD_KERLINK_TOKEN = "token";


    /**
     * Creates a Kerlink provider with the given vertx instance and cache manager.
     *
     * @param vertx the vertx instance this provider should run on
     * @param cacheManager the cache manager this provider should use
     */
    @Autowired
    public KerlinkProvider(final Vertx vertx, final CacheManager cacheManager) {
        super(vertx, cacheManager, KerlinkProvider.class.getName());
    }

    @Override
    protected String getDownlinkRequestUri(final JsonObject gatewayDevice, final String targetDevice) {
        final String hostName = LoraUtils.getNormalizedProviderUrlFromGatewayDevice(gatewayDevice);
        final JsonObject vendorProperties = LoraUtils.getLoraConfigFromLoraGatewayDevice(gatewayDevice)
                .getJsonObject(FIELD_LORA_VENDOR_PROPERTIES);
        final int customerId = vendorProperties.getInteger(FIELD_KERLINK_CUSTOMER_ID);
        final int clusterId = vendorProperties.getInteger(FIELD_KERLINK_CLUSTER_ID);

        final String txUrlPath = MessageFormat.format(API_PATH_TX_MESSAGE, customerId, clusterId, targetDevice);
        final String targetUrl = hostName + txUrlPath;

        LOG.debug("Invoking downlink rest api using url '{}' for device '{}'", targetUrl, targetDevice);

        return targetUrl;
    }

    @Override
    protected String getDownlinkContentType() {
        return HEADER_CONTENT_TYPE_KERLINK_JSON;
    }

    @Override
    protected String getFieldExpiryDate() {
        return FIELD_KERLINK_EXPIRY_DATE;
    }

    @Override
    protected String getFieldToken() {
        return FIELD_KERLINK_TOKEN;
    }

    @Override
    protected String getDownlinkApiPathGetToken() {
        return API_PATH_GET_TOKEN;
    }

    @Override
    protected String getDownlinkFieldAuthPassword() {
        return FIELD_KERLINK_AUTH_PASSWORD;
    }

    @Override
    protected String getDownlinkFieldAuthLogin() {
        return FIELD_KERLINK_AUTH_LOGIN;
    }

    @Override
    public String getProviderName() {
        return "kerlink";
    }

    @Override
    public String pathPrefix() {
        return "/kerlink/rxmessage";
    }

    @Override
    public String acceptedContentType() {
        return HEADER_CONTENT_TYPE_KERLINK_JSON;
    }

    @Override
    public String extractDeviceId(final JsonObject loraMessage) {
        return loraMessage.getString(FIELD_UPLINK_DEVICE_EUI);
    }

    @Override
    public String extractPayloadEncodedInBase64(final JsonObject loraMessage) {
        return loraMessage.getJsonObject(FIELD_UPLINK_USER_DATA, new JsonObject()).getString(FIELD_UPLINK_PAYLOAD);
    }

    @Override
    public Future<Void> sendDownlinkCommand(final JsonObject gatewayDevice, final CredentialsObject gatewayCredential,
            final String targetDeviceId, final Command loraCommand) {
        LOG.info("Send downlink command for device '{}' using gateway '{}'", targetDeviceId,
                gatewayDevice.getString(FIELD_PAYLOAD_DEVICE_ID));

        if (!isValidDownlinkKerlinkGateway(gatewayDevice)) {
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
        txMessage.put(FIELD_DOWNLINK_CONTENT_TYPE, VALUE_DOWNLINK_CONTENT_TYPE_HEXA);
        txMessage.put(FIELD_DOWNLINK_ACK, false);
        return txMessage;
    }


    private boolean isValidDownlinkKerlinkGateway(final JsonObject gatewayDevice) {
        final JsonObject loraConfig = LoraUtils.getLoraConfigFromLoraGatewayDevice(gatewayDevice);
        if (loraConfig == null) {
            return false;
        }

        final JsonObject vendorProperties = loraConfig.getJsonObject(FIELD_LORA_VENDOR_PROPERTIES);
        if (vendorProperties == null) {
            return false;
        }

        if (vendorProperties.getInteger(FIELD_KERLINK_CUSTOMER_ID) == null) {
            return false;
        }

        if (vendorProperties.getInteger(FIELD_KERLINK_CLUSTER_ID) == null) {
            return false;
        }

        return true;
    }

}
