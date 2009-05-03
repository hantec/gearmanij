/*
 * Copyright (C) 2009 by Eric Herman <eric@freesa.org>
 * Use and distribution licensed under the 
 * GNU Lesser General Public License (LGPL) version 2.1.
 * See the COPYING file in the parent directory for full text.
 */
package gearmanij;

import gearmanij.util.ByteUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class Packet {

	private final PacketMagic magic;

	private final PacketType type;

	private final byte[] data;

	public Packet(PacketMagic magic, PacketType type, byte[] data) {
		this.magic = magic;
		this.type = type;
		this.data = ByteUtils.copy(data);
	}

	/**
	 * @returns a copy of the array;
	 */
	public byte[] getData() {
		return ByteUtils.copy(data);
	}

	public int getDataSize() {
		return data.length;
	}

	/*
	 * 4 byte size - A big-endian (network-job) integer containing the size of
	 * the data being sent after the header.
	 */
	public byte[] getDataSizeBytes() {
		return ByteUtils.toBigEndian(getDataSize());
	}

	public PacketType getPacketType() {
		return type;
	}

	public byte[] toBytes() {
		int totalSize = getDataSize() + 12;
		ByteArrayOutputStream baos = new ByteArrayOutputStream(totalSize);
		write(baos);
		try {
			baos.flush();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return baos.toByteArray();
	}

	public void write(OutputStream os) {
		try {
			/*
			 * HEADER
			 * 
			 * 4 byte magic code - This is either "\0REQ" for requests or
			 * "\0RES"for responses.
			 * 
			 * 4 byte type - A big-endian (network-job) integer containing an
			 * enumerated packet type. Possible values are:
			 * 
			 * 4 byte size - A big-endian (network-job) integer containing the
			 * size of the data being sent after the header.
			 */
			os.write(magic.toBytes());
			os.write(type.toBytes());
			os.write(getDataSizeBytes());

			/*
			 * DATA
			 * 
			 * Arguments given in the data part are separated by a NULL byte,
			 * and the last argument is determined by the size of data after the
			 * last NULL byte separator. All job handle arguments must not be
			 * longer than 64 bytes, including NULL terminator.
			 */
			os.write(data);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	public PacketType getType() {
	  return type;
	}

	public String toString() {
		String s = magic + ":" + type + ":" + data.length;
		if (data.length > 0) {
			s += ": [" + ByteUtils.toHex(data) + "]";
		}
		return s;
	}

}
