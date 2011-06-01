/*
* JBoss, Home of Professional Open Source.
* Copyright 2006, Red Hat Middleware LLC, and individual contributors
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
package org.jboss.as.protocol.mgmt.support;

import java.io.DataInput;
import java.io.IOException;

import org.jboss.as.protocol.mgmt.FlushableDataOutput;
import org.jboss.as.protocol.mgmt.ManagementOperationHandler;
import org.jboss.as.protocol.mgmt.ManagementRequest;
import org.jboss.as.protocol.mgmt.ManagementRequestHandler;


/**
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 * @version $Revision: 1.1 $
 */
public class SimpleHandlers {

    public static class Request extends ManagementRequest<Integer>{
        final int sentData;

        public Request(int sentData) {
            this.sentData = sentData;
        }

        @Override
        protected byte getRequestCode() {
            return 102;
        }

        @Override
        protected void writeRequest(int protocolVersion, FlushableDataOutput output) throws IOException {
            //System.out.println("Writing request");
            output.writeInt(sentData);
        }

        @Override
        public Integer readResponse(DataInput input) throws IOException {
            int i = input.readInt();
            System.out.println("Reading response " + i);
            return i;
        }
    }

    public static class OperationHandler implements ManagementOperationHandler {

//        @Override
//        public Byte getId() {
//            return 101;
//        }

        @Override
        public ManagementRequestHandler getRequestHandler(byte id) {
            if (id != 102) {
                return null;
            }
            return new RequestHandler();
        }
    }

    public static class RequestHandler extends ManagementRequestHandler {
        int data;

        @Override
        public void readRequest(DataInput input) throws IOException {
            System.out.println("Reading request");
            data = input.readInt();
        }

        @Override
        public void writeResponse(FlushableDataOutput output) throws IOException {
            System.out.println("Writing response " + data * 2);
            output.writeInt(data * 2);
        }
    }
}
