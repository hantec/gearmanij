/*
 * Copyright (C) 2009 by Robert Stewart
 * Copyright (C) 2009 by Eric Herman <eric@freesa.org>
 * Use and distribution licensed under the 
 * GNU Lesser General Public License (LGPL) version 2.1.
 * See the COPYING file in the parent directory for full text.
 */
package gearmanij;

import gearmanij.util.ByteArrayBuffer;
import gearmanij.util.ByteUtils;
import gearmanij.util.IORuntimeException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Abstract Worker class that usually should be extended by all Worker implementations.
 */
public class SimpleWorker implements Worker {

  private EnumSet<WorkerOption> options = EnumSet.noneOf(WorkerOption.class);
  private List<Connection> connections = new LinkedList<Connection>();
  private Map<String, JobFunction> functions = new HashMap<String, JobFunction>();

  /**
   * Brought over from C implementation. May not be necessary.
   * 
   * @see gearmanij.Worker#getError()
   */
  public String getError() {
    // TODO Auto-generated method stub
    return null;
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

  public void addServer(Connection conn) {
    conn.open();
    connections.add(conn);
  }

  public List<Exception> close() {
    List<Exception> exceptions = new ArrayList<Exception>();
    for(Connection conn: connections) {
      try {
        conn.close();
      } catch (Exception e) {
        exceptions.add(e);
      }
    }
    return exceptions;
  }

  public String echo(String text, Connection conn) {
    byte[] in = ByteUtils.toAsciiBytes(text);
    Packet request = new Packet(PacketMagic.REQ, PacketType.ECHO_REQ, in);
    conn.write(request);
    byte[] out = conn.readPacket().getData();
    return ByteUtils.fromAsciiBytes(out);
  }

  public Map<String, List<String>> textModeTest(Connection conn) {
    // Send all supported text mode commands
    return conn.textMode(Arrays.asList(Constants.TEXT_MODE_TEST_COMMANDS));
  }

  /**
   * Registers a JobFunction that a Worker can perform on a Job. If the worker
   * does not respond with a result within the given timeout period in seconds,
   * the job server will assume the work will not be performed by that worker
   * and will again make the work available to be performed by any worker
   * capable of performing this function.
   * 
   * @param function
   */
  public void registerFunction(JobFunction function, int timeout) {
    functions.put(function.getName(), function);

    // Send CAN_DO_TIMEOUT command to job server

  }
  
  /**
   * Registers with all connections a JobFunction that a Worker can perform on a Job.
   * 
   * @param function
   */
  public void registerFunction(JobFunction function) {
    functions.put(function.getName(), function);

    Packet request = new Packet(PacketMagic.REQ, PacketType.CAN_DO,
        ByteUtils.toAsciiBytes(function.getName()));
    for(Connection conn : connections) {
      conn.write(request);
    }
  }

  /**
   * Unregisters with all connections a function that a worker can perform on a
   * Job.
   * 
   * @param function
   */
  public void unregisterFunction(JobFunction function) {
    Packet request = new Packet(PacketMagic.REQ, PacketType.CANT_DO,
        ByteUtils.toAsciiBytes(function.getName()));
    for(Connection conn : connections) {
      conn.write(request);
    }

    // Potential race condition unless job server acknowledges CANT_DO, though
    // worker could just return JOB_FAIL if it gets a job it just tried to
    // unregister for.
    functions.remove(function.getName());
  }

  /**
   * Unregisters all functions on all connections.
   * 
   * @param function
   */
  public void unregisterAll() {
    functions.clear();

    Packet request = new Packet(PacketMagic.REQ, PacketType.RESET_ABILITIES, null);
    for(Connection conn : connections) {
      conn.write(request);
    }
  }
  
  public void setWorkerID(String id) {
    byte[] in = ByteUtils.toAsciiBytes(id);
    Packet request = new Packet(PacketMagic.REQ, PacketType.SET_CLIENT_ID, in);
    for(Connection conn : connections) {
      conn.write(request);
    }
  }

  public Map<Connection, PacketType> grabJob() {
    Map<Connection, PacketType> jobsGrabbed;
    jobsGrabbed = new LinkedHashMap<Connection, PacketType>();
	  for(Connection conn : connections) {
      jobsGrabbed.put(conn, grabJob(conn));
    }
    return jobsGrabbed;
  }

  public PacketType grabJob(Connection conn) {
    Packet request = new Packet(PacketMagic.REQ, PacketType.GRAB_JOB, null);
    conn.write(request);

    Packet response = conn.readPacket();
    if (response.getType() == PacketType.NO_JOB) {
      preSleep(conn);
    } else if (response.getType() == PacketType.JOB_ASSIGN) {
      Job job = new JobImpl(response.getData());
      execute(conn, job);
      // If successful, call WORK_COMPLETE. Need to add support for WORK_* cases.
      workComplete(conn, job);
    } else if (response.getType() == PacketType.NOOP) {
      // do nothing
    } else {
      // Need to handle other cases here, if any
      System.err.println("unhandled type: " + response.getType() + " - " + response);
    }
    return response.getType();
  }

  /**
   * If non-blocking I/O implemented, worker/connection would go to sleep until
   * woken up with a NOOP command.
   * 
   * @throws IORuntimeException
   */
  public void preSleep(Connection conn) {
    Packet request = new Packet(PacketMagic.REQ, PacketType.PRE_SLEEP, null);
    conn.write(request);
  }

  public void execute(Connection conn, Job job) {
    // Perform the job and send back results
    JobFunction function = functions.get(job.getFunctionName());
    if (function == null) {
      String msg = job.getFunctionName() + " " + functions.keySet();
      throw new RuntimeException(msg);
    }
    job.setResult(function.execute(job.getData()));
  }

  public void workComplete(Connection conn, Job job) {
    ByteArrayBuffer baBuff = new ByteArrayBuffer(job.getHandle());
    baBuff.append(job.getResult());
    Packet request = new Packet(PacketMagic.REQ, PacketType.WORK_COMPLETE,
        baBuff.getBytes());
    conn.write(request);
  }

}
