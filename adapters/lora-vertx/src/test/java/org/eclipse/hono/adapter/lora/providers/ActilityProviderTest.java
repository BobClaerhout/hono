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

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.google.common.base.Charsets;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.adapter.lora.LoraConstants;
import org.eclipse.hono.adapter.lora.LoraMessageType;
import org.eclipse.hono.client.Command;
import org.eclipse.hono.service.http.HttpUtils;
import org.eclipse.hono.util.CredentialsObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.time.Instant;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Verifies behavior of {@link ActilityProvider}.
 */
@RunWith(VertxUnitRunner.class)
public class ActilityProviderTest {

    private ActilityProvider provider;
    private static final String ACTILITY_URL_TOKEN = "/admin/latest/api/oauth/token?renewToken=true&validityPeriod=5minutes";
    private static final String ACTILITY_TOKEN_GRANT_TYPE = "client_credentials";

    private static final String TEST_ACTILITY_API_USER = "actilityApiUser";
    private static final String TEST_ACTILITY_API_PASSWORD = "actilityApiPassword";

    private static final String ACTILITY_APPLICATION_TYPE = HttpUtils.CONTENT_TYPE_JSON;
    private static final String ACTILITY_URL_DOWNLINK = "/core/latest/api/devices/.*/downlinkMessages";

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(wireMockConfig().dynamicPort().dynamicPort());

    private final Vertx vertx = Vertx.vertx();
    /**
     * Sets up the fixture.
     */
    @Before
    public void before() {
        provider = new ActilityProvider(vertx, new ConcurrentMapCacheManager());
        // Use very small value here to avoid long running unit tests.
        provider.setTokenPreemptiveInvalidationTimeInMs(100);
    }

    /**
     * Verifies that the extraction of the device id from a message is successful.
     */
    @Test
    public void extractDeviceIdFromLoraMessage() {
        final JsonObject loraMessage = LoraTestUtil.loadTestFile("actility.uplink");
        final String deviceId = provider.extractDeviceId(loraMessage);

        Assert.assertEquals("actility-device", deviceId);
    }

    /**
     * Verifies the extraction of a payload from a message is successful.
     */
    @Test
    public void extractPayloadFromLoraMessage() {
        final JsonObject loraMessage = LoraTestUtil.loadTestFile("actility.uplink");
        final String payload = provider.extractPayloadEncodedInBase64(loraMessage);

        Assert.assertEquals("AA==", payload);
    }

    /**
     * Verifies that the extracted message type matches uplink.
     */
    @Test
    public void extractTypeFromLoraUplinkMessage() {
        final JsonObject loraMessage = LoraTestUtil.loadTestFile("actility.uplink");
        final LoraMessageType type = provider.extractMessageType(loraMessage);
        Assert.assertEquals(LoraMessageType.UPLINK, type);
    }

    /**
     * Verifies that an unknown message type defaults to the {@link LoraMessageType#UNKNOWN} type.
     */
    @Test
    public void extractTypeFromLoraUnknownMessage() {
        final JsonObject loraMessage = new JsonObject();
        loraMessage.put("bumlux", "bumlux");
        final LoraMessageType type = provider.extractMessageType(loraMessage);
        Assert.assertEquals(LoraMessageType.UNKNOWN, type);
    }

    /*

curl -X POST --header 'Content-Type: application/json' --header 'Accept: application/json' --header 'Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzY29wZSI6WyJTVUJTQ1JJQkVSOjEyMjE3OSJdLCJleHAiOjE1NTgwNzk3NzgsImp0aSI6IjljNWE5NzFlLTA2MjItNGIzZC04ZDRjLTNiNjk5N2IzMDdjMCIsImNsaWVudF9pZCI6ImRldjEtYXBpL2t3aW50ZW4uc2NocmFtQGFsb3h5LmlvIn0.SOI9SIGUY1MkCh9QlRnjRCu8VYefZNAzY2mZWAHQLzqUlvmnpaZuuSmCqRsIqCAj_W3hFAnkd_HXSxCX3OxMqg'
-d '{ \
   "payloadHex": "aa00bb11", \
   "targetPorts": "1" \
 }' 'https://dx-api.thingpark.com/core/latest/api/devices/00AD6610BFF57011/downlinkMessages'
     */

    /**
     * Verifies that sending a downlink command is successful.
     *
     * @param context The helper to use for running async tests on vertx.
     */
    @Test
    public void sendingDownlinkCommandIsSuccessful(final TestContext context) {
        final Async async = context.async();
        stubSuccessfulTokenRequest();
        stubSuccessfulDownlinkRequest();

        final JsonObject loraGatewayDevice = getValidGatewayDevice();
        final CredentialsObject gatewayCredential = getValidGatewayCredential();

        final String targetDeviceId = "myTestDevice";
        final Command command = getValidDownlinkCommand();

        final JsonObject loraPayload = command.getPayload().toJsonObject();
        final String payloadBase64 = loraPayload.getString(LoraConstants.FIELD_LORA_DOWNLINK_PAYLOAD);
        final String payloadHex = LoraUtils.convertFromBase64ToHex(payloadBase64);

        provider.sendDownlinkCommand(loraGatewayDevice, gatewayCredential, targetDeviceId, command)
                .setHandler(downlinkResult -> {
                    context.assertTrue(downlinkResult.succeeded());

                    verify(postRequestedFor(urlEqualTo("/admin/latest/api/oauth/token?renewToken=true&validityPeriod=5minutes"))
                            .withRequestBody(equalTo(String.format("grant_type=%s&client_id=%s&client_secret=%s", ACTILITY_TOKEN_GRANT_TYPE, TEST_ACTILITY_API_USER, TEST_ACTILITY_API_PASSWORD))));

                    final JsonObject expectedBody = new JsonObject();
                    expectedBody.put("targetPorts", 23);
                    expectedBody.put("payloadHex", payloadHex);

                    verify(postRequestedFor(urlEqualTo(String.format("/core/latest/api/devices/%s/downlinkMessages", targetDeviceId)))
                            .withRequestBody(equalToJson(expectedBody.toString())));


                    async.complete();
                });
    }

    private void stubSuccessfulDownlinkRequest() {
        stubFor(post(urlPathMatching(ACTILITY_URL_DOWNLINK))
                .withHeader("Content-Type", equalTo(ACTILITY_APPLICATION_TYPE))
                .willReturn(aResponse()
                        .withStatus(201)));
    }

    private JsonObject getValidGatewayDevice() {
        final JsonObject loraVendorProperties = new JsonObject();
        loraVendorProperties.put("cluster-id", 23);
        loraVendorProperties.put("customer-id", 4);

        final JsonObject loraNetworkServerData = new JsonObject();
        loraNetworkServerData.put("provider", "actility");
        loraNetworkServerData.put("auth-id", "lora-secret");
        loraNetworkServerData.put("url", "http://localhost:" + wireMockRule.port());
        loraNetworkServerData.put("vendor-properties", loraVendorProperties);
        loraNetworkServerData.put("lora-port", 23);

        final JsonObject loraGatewayData = new JsonObject();
        loraGatewayData.put("lora-network-server", loraNetworkServerData);

        final JsonObject loraGatewayDevice = new JsonObject();
        loraGatewayDevice.put("tenant-id", "test-tenant");
        loraGatewayDevice.put("device-id", "bumlux");
        loraGatewayDevice.put("enabled", true);
        loraGatewayDevice.put("data", loraGatewayData);

        return loraGatewayDevice;
    }

    private void stubSuccessfulTokenRequest() {
        final Instant tokenExpiryTime = Instant.now().plusSeconds(60);
        stubSuccessfulTokenRequest(tokenExpiryTime);
    }

    private void stubSuccessfulTokenRequest(final Instant tokenExpiryTime) {
        final JsonObject result = new JsonObject();
        result.put("expires_in", tokenExpiryTime.toEpochMilli());
        result.put("tokenType", "Bearer");
        result.put("access_token", "ThisIsAveryLongBearerTokenUsedByActility");

        stubFor(post(urlEqualTo(ACTILITY_URL_TOKEN))
                .withHeader("Content-Type", equalTo(ACTILITY_APPLICATION_TYPE))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", ACTILITY_APPLICATION_TYPE)
                        .withBody(result.encodePrettily())));
    }

    private CredentialsObject getValidGatewayCredential() {
        final JsonObject secret = new JsonObject();
        secret.put("identity", TEST_ACTILITY_API_USER);
        secret.put("key", Base64.getEncoder().encodeToString(TEST_ACTILITY_API_PASSWORD.getBytes(Charsets.UTF_8)));

        final CredentialsObject gatewayCredential = new CredentialsObject();
        gatewayCredential.setAuthId("lora-secret");
        gatewayCredential.addSecret(secret);

        return gatewayCredential;
    }

    private Command getValidDownlinkCommand() {
        final Message message = Message.Factory.create();
        message.setSubject("subject");
        message.setCorrelationId("correlation_id");
        message.setReplyTo("control/bumlux");

        final JsonObject payload = new JsonObject();
        payload.put(LoraConstants.FIELD_LORA_DOWNLINK_PAYLOAD, "bumlux".getBytes(Charsets.UTF_8));

        message.setBody(new AmqpValue(payload.encode()));

        return Command.from(message, "bumlux", "bumlux");
    }

}
