/*
 * Copyright (C) 2009 by Eric Herman <eric@freesa.org>
 * Copyright (C) 2009 by Robert Stewart <robert@wombatnation.com>
 * Use and distribution licensed under the 
 * GNU Lesser General Public License (LGPL) version 2.1.
 * See the COPYING file in the parent directory for full text.
 */
package org.gearman.client;

import static org.gearman.util.ByteUtils.NULL;

import java.io.PrintStream;
import java.io.Serializable;
import java.util.concurrent.Callable;

import org.gearman.Packet;
import org.gearman.PacketConnection;
import org.gearman.PacketType;
import org.gearman.Worker;
import org.gearman.util.ByteArrayBuffer;
import org.gearman.util.ByteUtils;
import org.gearman.worker.JavaFunction;

public class ClientRequest implements Callable<JobResponse> {

    private PacketConnection connection;

    private boolean loop;

    private String function;

    private String uniqueId;

    private byte[] jobHandle;

    private byte[] respBytes;

    private PrintStream err;

    private byte[] data;

    /**
     * For submission of a job to a job server.
     * 
     * @param connection
     *            Connection to a gearmand
     * @param function
     *            Name of the function to be performed
     * @param uniqueId
     *            Unique ID associated with the job
     * @param data
     *            Data to be used by a {@link Worker} to perform the job
     */
    public ClientRequest(PacketConnection connection, String function,
            String uniqueId, byte[] data) {
        this.connection = connection;
        this.function = function;
        this.uniqueId = uniqueId;
        this.data = data;
        this.jobHandle = ByteUtils.EMPTY;
        this.err = System.err;
        this.respBytes = ByteUtils.EMPTY;
        this.loop = true;
    }

    public ClientRequest(PacketConnection connection, Runnable job) {
        this(connection, JavaFunction.class.getSimpleName(), null, //
                ByteUtils.toByteArray((Serializable) job));
    }

    public ClientRequest(PacketConnection connection, String uniqueId,
            Callable<?> job) {
        this(connection, JavaFunction.class.getSimpleName(), uniqueId,
                ByteUtils.toByteArray((Serializable) job));
    }

    /**
     * Submit the job to a server, blocks until response is returned
     * 
     * @return result returned by a Worker
     */
    public JobResponse call() {
        connection.open();
        try {
            SubmitJob request = new SubmitJob(function, uniqueId, data);
            connection.write(request);
            while (loop) {
                readResponse();
            }
        } finally {
            connection.close();
        }
        JobResponse jobResponse = new JobResponse(respBytes);
        return jobResponse;
    }

    private void readResponse() {
        Packet fromServer = connection.read();

        PacketType packetType = fromServer.getPacketType();
        if (packetType == PacketType.JOB_CREATED) {
            jobCreated(fromServer);
        } else if (packetType == PacketType.WORK_COMPLETE) {
            workComplete(fromServer);
        } else {
            printErr("Unexpected PacketType: " + packetType);
            printErr("Unexpected Packet: " + fromServer);
        }
    }

    private void jobCreated(Packet fromServer) {
        setJobHandle(fromServer.toBytes());
    }

    private void workComplete(Packet fromServer) {
        ByteArrayBuffer dataBuf = new ByteArrayBuffer(fromServer.getData());
        int handleLen = dataBuf.indexOf(NULL);
        // byte[] jobHandle2 = dataBuf.subArray(0, handleLen);
        // println("expected: " + ByteUtils.fromAsciiBytes(jobhandle));
        // println("got:" + ByteUtils.fromAsciiBytes(jobHandle2));
        // jobHandle = ByteUtils.EMPTY;
        setResult(dataBuf.subArray(handleLen, dataBuf.length()));
        shutdown();
    }

    public byte[] getHandle() {
        return jobHandle;
    }

    public byte[] getData() {
        return data;
    }

    public String getFunctionName() {
        return function;
    }

    public byte[] getID() {
        return ByteUtils.toUTF8Bytes(uniqueId);
    }

    public byte[] getResult() {
        return respBytes;
    }

    public void setResult(byte[] result) {
        this.respBytes = result;
    }

    public void setJobHandle(byte[] bytes) {
        this.jobHandle = bytes;
    }

    public void shutdown() {
        loop = false;
    }

    /**
     * Sets the {@link PrintStream} object to which error messages will be
     * written.
     * 
     * @param err
     *            destination for error messages
     */
    public void setErr(PrintStream err) {
        this.err = err;
    }

    /**
     * Writes an error message to the PrintStream specified via
     * {@link #setErr(PrintStream)}
     * 
     * @param msgs
     *            An array of error message parts
     */
    public void printErr(Object... msgs) {
        if (err == null) {
            return;
        }
        synchronized (err) {
            err.print(Thread.currentThread().getName());
            err.print("");
            for (Object msg : msgs) {
                err.print(" ");
                err.print(msg);
            }
            err.println();
        }
    }

    public String toString() {
        return "connection: " + connection.toString() //
                + " currentJobHandle:" + ByteUtils.toHex(jobHandle);
    }

}
