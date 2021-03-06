/*******************************************************************************
 * Copyright (c) 2020, 2021 Contributors to the Eclipse Foundation
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

package org.eclipse.hono.client.command.amqp;

import java.util.Objects;

import org.eclipse.hono.client.ServiceInvocationException;
import org.eclipse.hono.client.command.Command;
import org.eclipse.hono.client.command.CommandContext;
import org.eclipse.hono.util.Constants;

import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.vertx.proton.ProtonHelper;

/**
 * A wrapper around a legacy {@link org.eclipse.hono.client.CommandContext}.
 */
public class ProtonBasedLegacyCommandContextWrapper implements CommandContext {

    private final org.eclipse.hono.client.CommandContext ctx;
    private final Command command;

    /**
     * Creates a new command context.
     *
     * @param context The legacy command context to wrap.
     * @throws NullPointerException if context is {@code null}.
     */
    public ProtonBasedLegacyCommandContextWrapper(final org.eclipse.hono.client.CommandContext context) {
        this.ctx = Objects.requireNonNull(context);
        this.command = new ProtonBasedLegacyCommandWrapper(context.getCommand());
    }

    @Override
    public void logCommandToSpan(final Span span) {
        command.logToSpan(span);
    }

    @Override
    public Command getCommand() {
        return command;
    }

    @Override
    public void accept() {
        ctx.accept();
    }

    @Override
    public void release() {
        ctx.release();
    }

    @Override
    public void release(final Throwable error) {
        Objects.requireNonNull(error);
        ctx.release();
    }

    @Override
    public void modify(final boolean deliveryFailed, final boolean undeliverableHere) {
        ctx.modify(deliveryFailed, undeliverableHere);
    }

    @Override
    public void reject(final String cause) {
        ctx.reject(ProtonHelper.condition(Constants.AMQP_BAD_REQUEST, cause));
    }

    @Override
    public void reject(final Throwable error) {
        reject(ServiceInvocationException.getErrorMessageForExternalClient(error));
    }

    @Override
    public <T> T get(final String key) {
        return ctx.get(key);
    }

    @Override
    public <T> T get(final String key, final T defaultValue) {
        return ctx.get(key, defaultValue);
    }

    @Override
    public void put(final String key, final Object value) {
        ctx.put(key, value);
    }

    @Override
    public SpanContext getTracingContext() {
        return ctx.getTracingContext();
    }

    @Override
    public Span getTracingSpan() {
        return ctx.getTracingSpan();
    }
}
