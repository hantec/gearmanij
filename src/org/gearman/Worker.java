/*
 * Copyright (C) 2009 by Robert Stewart <robert@wombatnation.com>
 * Copyright (C) 2009 by Eric Herman <eric@freesa.org>
 * Use and distribution licensed under the 
 * GNU Lesser General Public License (LGPL) version 2.1.
 * See the COPYING file in the parent directory for full text.
 */
package org.gearman;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.gearman.util.IORuntimeException;

/**
 * A Worker grabs a {@link Job} from a job server, performs the
 * {@link JobFunction} specified on the data in the Job, and returns the results
 * of the processing to the server. The server relays the results to the client
 * that submitted the job. The worker may also return status updates or partial
 * results to the job server.
 */
public interface Worker {
    // These enums were copied over from the C library.
    public enum WorkerOption {
        NON_BLOCKING, PACKET_INIT, GRAB_JOB_IN_USE, PRE_SLEEP_IN_USE, WORK_JOB_IN_USE, CHANGE, GRAB_UNIQ
    }

    enum FunctionOption {
        PACKET_IN_USE, CHANGE, REMOVE
    }

    enum WorkerState {
        START, STATE_FUNCTION_SEND, STATE_CONNECT, STATE_GRAB_JOB_SEND, STATE_GRAB_JOB_RECV, STATE_PRE_SLEEP
    }

    enum WorkState {
        GRAB_JOB, FUNCTION, COMPLETE, FAIL
    }

    /**
     * Wait for a job and call the appropriate callback function when it gets
     * one.
     */
    void work();

    /**
     * Clears all {@link WorkerOption}s.
     */
    void clearWorkerOptions();

    /**
     * Returns {@link java.util.EnumSet} of {@link WorkerOption}s.
     * 
     * @return EnumSet of WorkerOptions
     */
    EnumSet<WorkerOption> getWorkerOptions();

    /**
     * Removes each specified WorkerOption from the current set of Worker
     * options.
     * 
     * @param workerOptions
     *            one or more WorkerOptions
     */
    void removeWorkerOptions(WorkerOption... workerOptions);

    /**
     * Adds each specified WorkerOption to the current set of Worker options.
     * For example,
     * <code>worker.setWorkerOptions(WorkerOption.NON_BLOCKING, WorkerOption.GRAB_JOB_IN_USE))</code>
     * 
     * @param workerOptions
     *            one or more WorkerOptions
     */
    void setWorkerOptions(WorkerOption... workerOptions);

    /**
     * Adds a {@link PacketConnection} to a job server.
     * 
     * @param conn
     *            connection to a job server
     */
    void addServer(PacketConnection conn);

    /**
     * Sends <code>text</code> to a job server with expectation of receiving the
     * same data echoed back.
     * 
     * @param text
     *            String to be echoed
     * @param conn
     *            connection to a job server
     * @throws IORuntimeException
     */
    String echo(String text, PacketConnection conn);

    /**
     * Registers a JobFunction that a Worker can perform on a Job. If the worker
     * does not respond with a result within the given timeout period in
     * seconds, the job server will assume the work will not be performed by
     * that worker and will again make the work available to be performed by any
     * worker capable of performing this function.
     * 
     * @param function
     *            JobFunction for a function a Worker can perform
     * @param timeout
     *            time in seconds after job server will consider job to be
     *            abandoned
     */
    void registerFunction(JobFunction function, int timeout);

    /**
     * Registers a JobFunction that a Worker can perform on a Job.
     * 
     * @param function
     *            JobFunction for a function a Worker can perform
     */
    void registerFunction(JobFunction function);

    /**
     * Registers a JobFunction that a Worker can perform on a Job. If the worker
     * does not respond with a result within the given timeout period in
     * seconds, the job server will assume the work will not be performed by
     * that worker and will again make the work available to be performed by any
     * worker capable of performing this function.
     * 
     * @param function
     *            JobFunction Class for a function a Worker can perform
     * @param timeout
     *            time in seconds after job server will consider job to be
     *            abandoned
     */
    void registerFunction(Class<? extends JobFunction> function, int timeout);

    /**
     * Registers a JobFunction that a Worker can perform on a Job.
     * 
     * @param function
     *            JobFunction Class for a function a Worker can perform
     */
    void registerFunction(Class<? extends JobFunction> function);

    /**
     * Registers a JobFunctionFactory that a Worker will use to create a
     * JobFunction object to execute a Job.If the worker does not respond with a
     * result within the given timeout period in seconds, the job server will
     * assume the work will not be performed by that worker and will again make
     * the work available to be performed by any worker capable of performing
     * this function.
     * 
     * @param factory
     *            Factory that will be called to create a JobFunction instance
     *            for a function a Worker can perform
     * @param timeout
     *            time in seconds after job server will consider job to be
     *            abandoned
     */
    void registerFunctionFactory(JobFunctionFactory factory, int timeout);

    /**
     * Registers a JobFunctionFactory that a Worker will use to create a
     * JobFunction object to execute a Job.
     * 
     * @param factory
     *            Factory that will be called to create a JobFunction instance
     *            for a function a Worker can perform
     */
    void registerFunctionFactory(JobFunctionFactory factory);

    /**
     * Sets the worker ID in a job server so monitoring and reporting commands
     * can uniquely identify the connected workers.
     * 
     * @param id
     *            ID that job server should use for an instance of a worker
     */
    void setWorkerID(String id);

    /**
     * Sets the worker ID in a job server so monitoring and reporting commands
     * can uniquely identify the connected workers. If a different ID is set
     * with each job server, and connections can more easily be monitored and
     * reported on independently.
     * 
     * @param id
     *            ID that job server should use for an instance of a worker
     * @param conn
     *            connection to the job server
     */
    void setWorkerID(String id, PacketConnection conn);

    /**
     * Unregisters with the Connection a function that a worker can perform on a
     * Job.
     * 
     * @param functionName
     *            Name for a function a Worker can no longer perform
     */
    void unregisterFunction(String functionName);

    /**
     * Unregisters all functions on all Connections.
     */
    void unregisterAll();

    /**
     * Stops the work loop; requests to shutdown
     */
    void stop();

    /**
     * Stops the work loop and closes all open connections.
     * 
     * @return a List of any Exceptions thrown when closing connections
     */
    List<Exception> shutdown();

    /**
     * @return the number of jobs succesfully completed
     */
    int jobsCompleted();

}
