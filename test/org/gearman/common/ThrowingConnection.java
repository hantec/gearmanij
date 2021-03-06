/*
 * Copyright (C) 2009 by Eric Herman <eric@freesa.org>
 * Use and distribution licensed under the 
 * GNU Lesser General Public License (LGPL) version 2.1.
 * See the COPYING file in the parent directory for full text.
 */
package org.gearman.common;

import java.util.Arrays;
import java.util.List;

import org.gearman.PacketConnection;
import org.gearman.Packet;
import org.gearman.TextConnection;
import org.gearman.util.NotImplementedException;

public class ThrowingConnection implements PacketConnection, TextConnection {

    public void open() {
        throw new NotImplementedException();
    }

    public void close() {
        throw new NotImplementedException();
    }

    public Packet read() {
        throw new NotImplementedException();
    }

    public void write(Packet request) {
        throw new NotImplementedException("" + request);
    }

    public List<String> getTextModeListResult(String command) {
        throw new NotImplementedException(command);
    }

    public String getTextModeResult(String command, Object[] params) {
        String msg = command;
        if (params != null) {
            msg = command + " " + Arrays.asList(params);
        }
        throw new NotImplementedException(msg);
    }

}
