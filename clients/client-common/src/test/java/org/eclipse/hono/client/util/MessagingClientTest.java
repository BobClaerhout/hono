/**
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */


package org.eclipse.hono.client.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.assertj.core.api.Assertions;
import org.eclipse.hono.util.Lifecycle;
import org.eclipse.hono.util.MessagingType;
import org.eclipse.hono.util.TenantConstants;
import org.eclipse.hono.util.TenantObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


/**
 * Tests verifying behavior of {@link MessagingClient}.
 *
 */
public class MessagingClientTest {

    private static final String tenant = "tenant";

    private static final Map<String, String> CONFIG_KAFKA = Map.of(TenantConstants.FIELD_EXT_MESSAGING_TYPE,
            MessagingType.kafka.name());
    private static final Map<String, String> CONFIG_AMQP = Map.of(TenantConstants.FIELD_EXT_MESSAGING_TYPE,
            MessagingType.amqp.name());

    private final Lifecycle amqpClient = mock(Lifecycle.class);
    private final Lifecycle kafkaClient = mock(Lifecycle.class);

    private MessagingClient<Lifecycle> underTest;

    /**
     * Sets up the fixture.
     */
    @BeforeEach
    void setUp() {
        underTest = new MessagingClient<>();
    }

    /**
     * Verifies that the check for client implementations being configured succeeds.
     */
    @Test
    public void containsImplementations() {

        assertThat(underTest.containsImplementations()).isFalse();

        underTest.setClient(MessagingType.amqp, amqpClient);
        assertThat(underTest.containsImplementations()).isTrue();
    }

    /**
     * Verifies that when the messaging to be used is configured for a tenant, then this is returned.
     */
    @Test
    public void testGetClientConfiguredOnTenant() {

        underTest.setClient(MessagingType.amqp, amqpClient)
                .setClient(MessagingType.kafka, kafkaClient);

        assertThat(underTest.getClient(TenantObject.from(tenant, true).setProperty(
                TenantConstants.FIELD_EXT, CONFIG_KAFKA))).isEqualTo(kafkaClient);

        assertThat(underTest.getClient(TenantObject.from(tenant, true).setProperty(
                TenantConstants.FIELD_EXT, CONFIG_AMQP))).isEqualTo(amqpClient);

    }

    /**
     * Verifies that when no messaging type is configured for a tenant and only the Kafka client is present, then
     * this one is returned.
     */
    @Test
    public void testGetClientReturnsKafkaClient() {
        underTest.setClient(MessagingType.kafka, kafkaClient);

        assertThat(underTest.getClient(TenantObject.from(tenant, true))).isEqualTo(kafkaClient);
    }

    /**
     * Verifies that when no messaging type is configured for a tenant and only the AMQP client is present, then
     * this one is returned.
     */
    @Test
    public void testGetClientReturnsAmqpClient() {
        underTest.setClient(MessagingType.amqp, amqpClient);

        assertThat(underTest.getClient(TenantObject.from(tenant, true))).isEqualTo(amqpClient);
    }

    /**
     * Verifies that when no messaging type is configured for a tenant and multiple client sets are present, then the
     * default (i.e. the AMQP) client is returned.
     */
    @Test
    public void testGetClientReturnsDefaultClient() {
        underTest.setClient(MessagingType.kafka, kafkaClient);
        underTest.setClient(MessagingType.amqp, amqpClient);

        assertThat(underTest.getClient(TenantObject.from(tenant, true))).isEqualTo(amqpClient);
    }

    /**
     * Verifies that an exception is thrown if no client has been set.
     */
    @Test
    public void testGetClientFailsIfNoClientHasBeenSet() {
        Assertions.assertThatIllegalStateException().isThrownBy(() -> underTest.getClient(TenantObject.from(tenant, true)));
    }

}
