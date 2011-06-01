/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.protocol.mgmt;

import java.io.DataInput;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.as.protocol.ProtocolChannel;
import org.jboss.remoting3.Channel;
import org.jboss.threads.AsyncFuture;
import org.jboss.threads.AsyncFutureTask;
import org.xnio.IoUtils;

/**
 * Base management request used for remote requests.  Provides the basic mechanism for connecting to a remote host controller
 * for performing a task.  It will manage connecting and retrieving the correct response.
 *
 *
 * @author John Bailey
 * @author Kabir Khan
 */
public abstract class ManagementRequest<T> extends ManagementResponseHandler<T> {
    private static final AtomicInteger requestId = new AtomicInteger();
    protected final int currentRequestId = requestId.getAndIncrement();
    private final ManagementFuture<T> future = new ManagementFuture<T>();


    /**
     * Get the id of the protocol request. The {@link ManagementOperationHandler} will use this to
     * determine the {@link ManagementRequestHandler} to use.
     *
     * @return the request code
     */
    protected abstract byte getRequestCode();

    protected ManagementRequest() {
    }

    /**
     * Execute the request by connecting and then delegating to the implementation's execute
     * and return a future used to get the response when complete.
     *
     * @param executor The executor to use to handle the request and response
     * @param channel The channel strategy
     * @return A future to retrieve the result when the request is complete
     */
    public AsyncFuture<T> execute(final ExecutorService executor, final ManagementClientChannelStrategy channelStrategy) {
        executor.execute(new Runnable() {

            @Override
            public void run() {
                final ProtocolChannel channel = channelStrategy.getChannel();

                FlushableDataOutputImpl output = null;
                try {

                    channel.getReceiver(ManagementChannelReceiver.class).registerResponseHandler(currentRequestId, new DelegatingResponseHandler());
                    output = FlushableDataOutputImpl.create(channel.writeMessage());
                    //Header
                    //TODO Handler Id should be possible to infer from the channel name
                    final ManagementRequestHeader managementRequestHeader = new ManagementRequestHeader(ManagementProtocol.VERSION, currentRequestId);
                    managementRequestHeader.write(output);

                    //Body
                    writeRequestBody(channel, output);

                    //End
                    output.writeByte(ManagementProtocol.REQUEST_END);
                } catch (Exception e) {
                    future.failed(e);
                } finally {
                    IoUtils.safeClose(output);
                }
            }
        });

        return future;
    }

    /**
     * Execute the request and wait for the result.
     *
     * @param executor The executor to use to handle the request and response
     * @param channelStrategy The channel strategy
     * @return The result
     * @throws IOException If any problems occur
     */
    public T executeForResult(final ExecutorService executor, final ManagementClientChannelStrategy channelStrategy) throws Exception {
        return execute(executor, channelStrategy).get();
    }

    /**
     * Override to send extra parameters to the server {@link ManagementRequestHandler}. This default
     * implementation does not send any extra data
     *
     * @param protocolVersion the protocol version
     * @param output the data output to write the data to
     */
    protected void writeRequest(final int protocolVersion, final FlushableDataOutput output) throws IOException {
    }

    private void writeRequestBody(Channel channel, FlushableDataOutput output) throws IOException {
        output.writeByte(ManagementProtocol.REQUEST_OPERATION);
        output.writeByte(getRequestCode());
        output.writeByte(ManagementProtocol.REQUEST_START);

        output.write(ManagementProtocol.REQUEST_BODY);
        writeRequest(ManagementProtocol.VERSION, output);

    }

    private final class DelegatingResponseHandler extends ManagementResponseHandler<T>{
        @Override
        protected T readResponse(DataInput input) {
            T result = null;
            try {
                result = ManagementRequest.this.readResponse(input);
                future.done(result);
                return result;
            } catch (Exception e) {
                future.failed(e);
            }
            return result;
        }
    }

    private static class ManagementFuture<T> extends AsyncFutureTask<T>{
        protected ManagementFuture() {
            super(null);
        }

        void done(T result) {
            super.setResult(result);
        }

        void failed(Exception ex) {
            super.setFailed(ex);
        }
    }
}
