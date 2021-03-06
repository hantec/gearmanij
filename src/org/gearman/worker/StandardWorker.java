/*
 * Copyright (C) 2009 by Robert Stewart
 * Copyright (C) 2009 by Eric Herman <eric@freesa.org>
 * Use and distribution licensed under the 
 * GNU Lesser General Public License (LGPL) version 2.1.
 * See the COPYING file in the parent directory for full text.
 */
package org.gearman.worker;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

import org.gearman.Job;
import org.gearman.JobFunction;
import org.gearman.JobFunctionFactory;
import org.gearman.Packet;
import org.gearman.PacketConnection;
import org.gearman.PacketMagic;
import org.gearman.PacketType;
import org.gearman.Worker;
import org.gearman.util.ByteArrayBuffer;
import org.gearman.util.ByteUtils;
import org.gearman.util.IORuntimeException;

/**
 * Standard implementation of the Worker interface that should meet most needs.
 * <p>
 * After a StandardWorker has been connected to at least one job server with
 * {@link #addServer(PacketConnection)}, the worker must be registered to
 * perform a function in order to grab jobs. A function can be registered by
 * specifying the either a JobFunction class or a JobFunctionFactory that will
 * be used to produce a JobFunction instance. The JobFunction instance is used
 * to execute the function on a Job.
 */
public class StandardWorker implements Worker {

    private EnumSet<WorkerOption> options;
    private Set<PacketConnection> connections;
    Map<String, JobFunctionFactory> functions;
    private volatile boolean running;
    private AtomicInteger jobsCompleted;
    private PrintStream err;
    private PrintStream out;
    private final int numberWorkerThreads;
    private Set<Thread> workerThreads;

    public StandardWorker() {
        this(1);
    }

    public StandardWorker(int numberWorkerThreads) {
        if (numberWorkerThreads < 1) {
            throw new IllegalArgumentException("" + numberWorkerThreads);
        }

        this.numberWorkerThreads = numberWorkerThreads;
        this.workerThreads = new HashSet<Thread>();
        this.options = EnumSet.noneOf(WorkerOption.class);
        this.connections = new LinkedHashSet<PacketConnection>();
        this.functions = new HashMap<String, JobFunctionFactory>();
        this.running = true;
        this.jobsCompleted = new AtomicInteger(0);
        this.err = System.err;
        this.out = null;
    }

    public void work() {
        for (int i = 0; i < numberWorkerThreads; i++) {
            Runnable workLoop = new Runnable() {
                public void run() {
                    while (running) {
                        try {
                            workLoop();
                        } catch (Exception e) {
                            if (running) {
                                e.printStackTrace(err);
                            }
                        }
                    }
                    close();
                }
            };
            String tName = Thread.currentThread().getName() + "[" + i + "]";
            Thread t = new Thread(workLoop, tName);
            t.start();
            workerThreads.add(t);
        }
    }

    void workLoop() {
        Map<PacketConnection, PacketType> jobs = workJobs();
        int nojob = 0;
        Set<Entry<PacketConnection, PacketType>> entries = jobs.entrySet();
        for (Map.Entry<PacketConnection, PacketType> entry : entries) {
            PacketConnection conn = entry.getKey();
            PacketType packetType = entry.getValue();
            switch (packetType) {
            case NO_JOB:
                nojob++;
                break;
            case JOB_ASSIGN:
            case NOOP:
                break;
            default:
                println(err, conn, " returned unexpected PacketType: ",
                        packetType);
                break;
            }
        }
        if (running && jobs.size() == nojob) {
            println(out, "sleep");
            sleep(250);
        }
    }

    private void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            if (running) {
                throw new RuntimeException(e);
            }
        }
    }

    public void clearWorkerOptions() {
        options = EnumSet.noneOf(WorkerOption.class);
    }

    public EnumSet<WorkerOption> getWorkerOptions() {
        return options;
    }

    public void removeWorkerOptions(WorkerOption... workerOptions) {
        for (WorkerOption option : workerOptions) {
            options.remove(option);
        }
    }

    public void setWorkerOptions(WorkerOption... workerOptions) {
        for (WorkerOption option : workerOptions) {
            options.add(option);
        }
    }

    public void addServer(PacketConnection conn) {
        conn.open();
        connections.add(conn);
    }

    public void stop() {
        running = false;
    }

    public List<Exception> shutdown() {
        stop();
        return close();
    }

    /* Copy collection avoids concurrent modification exception */
    private Iterable<PacketConnection> connections() {
        return new ArrayList<PacketConnection>(connections);
    }

    public List<Exception> close() {
        println(out, "close");
        List<Exception> exceptions = new ArrayList<Exception>();
        for (PacketConnection conn : connections()) {
            try {
                conn.close();
            } catch (Exception e) {
                exceptions.add(e);
            }
        }
        return exceptions;
    }

    public String echo(String text, PacketConnection conn) {
        // println(out, "text  in:", text);
        byte[] in = ByteUtils.toUTF8Bytes(text);
        Packet request = new Packet(PacketMagic.REQ, PacketType.ECHO_REQ, in);
        Packet read;

        synchronized (conn) {
            conn.write(request);
            read = conn.read();
        }

        byte[] bytesOut = read.getData();
        String textOut = ByteUtils.fromAsciiBytes(bytesOut);
        // println(out, "text  in:", textOut);
        return textOut;
    }

    public void registerFunction(JobFunction func, int timeout) {
        registerFunctionFactory(new InstanceJobFunctionFactory(func), timeout);
    }

    public void registerFunction(JobFunction func) {
        registerFunctionFactory(new InstanceJobFunctionFactory(func));
    }

    /**
     * Registers a JobFunction that a Worker can perform on a Job. If the worker
     * does not respond with a result within the given timeout period in
     * seconds, the job server will assume the work will not be performed by
     * that worker and will again make the work available to be performed by any
     * worker capable of performing this function.
     * 
     * @param fCls
     *            Class that implements the {@link JobFunction} interface
     * @param timeout
     *            timeout seconds (positive integer)
     * @throws IllegalArgumentException
     *             if timeout not positive
     */
    public void registerFunction(Class<? extends JobFunction> fCls, int timeout) {
        registerFunctionFactory(new ClassJobFunctionFactory(fCls), timeout);
    }

    /**
     * Registers with all connections a JobFunction that a Worker can perform on
     * a Job.
     * 
     * @param functionClass
     *            Class that implements the {@link JobFunction} interface
     */
    public void registerFunction(Class<? extends JobFunction> functionClass) {
        registerFunctionFactory(new ClassJobFunctionFactory(functionClass));
    }

    public void registerFunctionFactory(JobFunctionFactory factory, int timeout) {
        functions.put(factory.getFunctionName(), factory);
        registerFunctionAllConnections(factory.getFunctionName(), timeout);
    }

    public void registerFunctionFactory(JobFunctionFactory factory) {
        registerFunctionFactory(factory, 0);
    }

    /**
     * Unregisters with all connections a function that a worker can no longer
     * perform on a Job.
     * 
     * @param functionName
     */
    public void unregisterFunction(String functionName) {
        byte[] data = ByteUtils.toUTF8Bytes(functionName);
        Packet request = new Packet(PacketMagic.REQ, PacketType.CANT_DO, data);
        for (PacketConnection conn : connections) {
            write(conn, request);
        }

        // Potential race condition unless job server acknowledges CANT_DO,
        // though
        // worker could just return JOB_FAIL if it gets a job it just tried to
        // unregister for.
        functions.remove(functionName);
    }

    private void write(PacketConnection conn, Packet request) {
        synchronized (request) {
            conn.write(request);
        }
    }

    /**
     * Unregisters all functions on all connections.
     */
    public void unregisterAll() {
        functions.clear();

        Packet req = newResetAbilitiesPacket();
        for (PacketConnection conn : connections()) {
            write(conn, req);
        }
    }

    private Packet newResetAbilitiesPacket() {
        return new Packet(PacketMagic.REQ, PacketType.RESET_ABILITIES, null);
    }

    public void setWorkerID(String id) {
        byte[] data = ByteUtils.toUTF8Bytes(id);
        Packet req = new Packet(PacketMagic.REQ, PacketType.SET_CLIENT_ID, data);
        for (PacketConnection conn : connections()) {
            write(conn, req);
        }
    }

    public void setWorkerID(String id, PacketConnection conn) {
        byte[] data = ByteUtils.toUTF8Bytes(id);
        Packet req = new Packet(PacketMagic.REQ, PacketType.SET_CLIENT_ID, data);
        write(conn, req);
    }

    /**
     * Attempts to grab and then execute a Job on each connection.
     * 
     * @return a Map indicating for each connection whether a Job was grabbed
     */
    public Map<PacketConnection, PacketType> workJobs() {
        println(out, "workJobs");
        Map<PacketConnection, PacketType> jobs;
        jobs = new LinkedHashMap<PacketConnection, PacketType>();
        for (PacketConnection conn : connections()) {
            if (!running) {
                break;
            }
            try {
                PacketType jobPacket = workJob(conn);
                jobs.put(conn, jobPacket);
            } catch (IORuntimeException e) {
                if (!running) {
                    // we're done
                } else {
                    e.printStackTrace(err);
                }
            }
        }
        return jobs;
    }

    /**
     * Attempts to grab and then execute a Job on the specified connection.
     * 
     * @param conn
     *            connection to a job server
     * @return a PacketType indicating with a job was grabbed
     */
    public PacketType workJob(PacketConnection conn) {
        Packet request = new Packet(PacketMagic.REQ, PacketType.GRAB_JOB, null);
        Packet response;

        synchronized (conn) {
            conn.write(request);
            response = conn.read();
        }

        println(out, "grabbed:", response);
        if (response.getType() == PacketType.NO_JOB) {
            preSleep(conn);
        } else if (response.getType() == PacketType.JOB_ASSIGN) {
            jobAssign(conn, response);
        } else if (response.getType() == PacketType.NOOP) {
            // do nothing
        } else {
            // Need to handle other cases here, if any
            String msg = "unhandled type: " + response.getType() + " - "
                    + response;
            System.err.println(msg);
        }
        return response.getType();
    }

    private void jobAssign(PacketConnection conn, Packet response) {
        Job job = new WorkerJob(response.getData());
        boolean jobInProgress = true;
        while (jobInProgress) {
            execute(job);
            switch (job.getState()) {
            case COMPLETE:
                workComplete(conn, job);
                jobInProgress = false;
                break;
            case EXCEPTION:
                workException(conn, job);
                jobsCompleted.incrementAndGet();
                jobInProgress = false;
                break;
            case PARTIAL_DATA:
                workPartialData(conn, job);
                break;
            case STATUS:
                returnStatus(conn, job);
                break;
            case WARNING:
                workWarning(conn, job);
                break;
            case FAIL:
                workFail(conn, job);
                jobInProgress = false;
            default:
                String msg = "Function returned invalid job state "
                        + job.getState();
                System.err.println(msg);
                workFail(conn, job);
                jobInProgress = false;
                break;
            }
        }
    }

    /**
     * If non-blocking I/O implemented, worker/connection would go to sleep
     * until woken up with a NOOP command.
     * 
     * @throws IORuntimeException
     */
    public void preSleep(PacketConnection conn) {
        Packet request = new Packet(PacketMagic.REQ, PacketType.PRE_SLEEP, null);
        write(conn, request);
    }

    /**
     * Executes a job by calling the execute() method on the JobFunction for the
     * job. TODO: These RuntimeExceptions should likely cause Worker to send a
     * WORK_EXCEPTION to the job server.
     * 
     * @param job
     * @throws IllegalArgumentException
     *             if Worker not registered to execute the function
     * @throws RuntimeException
     *             any other error occurs while trying to execute the function
     */
    void execute(Job job) {
        String name = job.getFunctionName();
        JobFunction function = getFunction(name);
        function.execute(job);
    }

    JobFunction getFunction(String name) {
        JobFunctionFactory factory = functions.get(name);
        if (factory == null) {
            String msg = name + " not in " + functions.keySet();
            throw new IllegalArgumentException(msg);
        }

        JobFunction function = factory.getJobFunction();
        if (function == null) {
            // Do we need this?
            // It indicates a seriously broken JobFunctionFactory
            String msg = "Worker could not instantiate JobFunction for " + name;
            throw new NullPointerException(msg);
        }
        return function;
    }

    public void workComplete(PacketConnection conn, Job job) {
        returnResults(conn, job, PacketType.WORK_COMPLETE, true);
    }

    public void workException(PacketConnection conn, Job job) {
        returnResults(conn, job, PacketType.WORK_EXCEPTION, true);
    }

    public void workFail(PacketConnection conn, Job job) {
        returnResults(conn, job, PacketType.WORK_FAIL, false);
    }

    public void workWarning(PacketConnection conn, Job job) {
        returnResults(conn, job, PacketType.WORK_WARNING, true);
    }

    public void workPartialData(PacketConnection conn, Job job) {
        returnResults(conn, job, PacketType.WORK_DATA, true);
    }

    public void setErr(PrintStream err) {
        this.err = err;
    }

    public void setOut(PrintStream out) {
        this.out = out;
    }

    private void returnResults(PacketConnection conn, Job job,
            PacketType command, boolean includeData) {
        ByteArrayBuffer baBuff = new ByteArrayBuffer(job.getHandle());
        byte[] data = null;
        if (includeData) {
            baBuff.append(job.getResult());
            data = baBuff.getBytes();
        }
        Packet req = new Packet(PacketMagic.REQ, command, data);
        println(out, "returnResults:", req);
        write(conn, req);
    }

    private void returnStatus(PacketConnection conn, Job job) {
        ByteArrayBuffer baBuff = new ByteArrayBuffer(job.getHandle());
        byte[] data = null;
        baBuff.append(job.getResult());
        data = baBuff.getBytes();
        Packet req = new Packet(PacketMagic.REQ, PacketType.WORK_STATUS, data);
        println(out, "returnStatus:", req);
        write(conn, req);
    }

    private void registerFunctionAllConnections(String name, int timeout) {
        byte[] fName = ByteUtils.toUTF8Bytes(name);
        ByteArrayBuffer baBuff = new ByteArrayBuffer(fName);
        PacketType type;
        if (timeout > 0) {
            type = PacketType.CAN_DO_TIMEOUT;
            baBuff.append(ByteUtils.NULL);
            baBuff.append(ByteUtils.toUTF8Bytes(String.valueOf(timeout)));
        } else {
            type = PacketType.CAN_DO;
        }
        Packet req = new Packet(PacketMagic.REQ, type, baBuff.getBytes());
        for (PacketConnection conn : connections()) {
            println(out, "registerFunctionAllConnections:", req);
            write(conn, req);
        }
    }

    public int jobsCompleted() {
        return jobsCompleted.intValue();
    }

    private void println(PrintStream out, Object... msgs) {
        if (out == null) {
            return;
        }
        synchronized (out) {
            out.print(Thread.currentThread().getName());
            out.print(" ");
            out.print(getClass().getSimpleName());
            out.print(": ");
            for (Object msg : msgs) {
                out.print(msg);
            }
            out.println();
        }
    }

}
