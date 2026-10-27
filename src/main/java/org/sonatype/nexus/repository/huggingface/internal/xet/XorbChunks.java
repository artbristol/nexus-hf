/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface.internal.xet;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Checks the structure of a byte range of a xorb, as XET storage serves it: a sequence of chunks, each an 8-byte header
 * (version, compressed size, compression scheme, uncompressed size; sizes 3 bytes little-endian) followed by its
 * compressed data.
 *
 * The chunk data cannot be verified against anything (a xorb range carries no hashes), but a range that was cut short,
 * padded, or is something else entirely (an error page) does not have exactly the expected chunks, in exactly its
 * length. The limits are those {@code hf_xet} enforces.
 */
public final class XorbChunks
{
  static final int HEADER_LENGTH = 8;

  private static final int MAX_UNCOMPRESSED = 128 * 1024;

  private static final int MAX_COMPRESSED = 2 * MAX_UNCOMPRESSED;

  private XorbChunks() {
  }

  /**
   * Reads a xorb range of {@code length} bytes that must hold exactly {@code chunks} chunks.
   *
   * @return the uncompressed size of its chunks
   * @throws IOException if it does not
   */
  public static long check(final InputStream in, final long length, final int chunks) throws IOException {
    byte[] header = new byte[HEADER_LENGTH];
    long offset = 0;
    long uncompressed = 0;
    for (int chunk = 0; chunk < chunks; chunk++) {
      if (length - offset < HEADER_LENGTH) {
        throw new IOException("Xorb range of " + length + " bytes ends within the header of chunk " + chunk);
      }
      if (in.readNBytes(header, 0, HEADER_LENGTH) < HEADER_LENGTH) {
        throw new IOException("Xorb range ends early");
      }
      int version = header[0] & 0xff;
      int compressedSize = uint24(header, 1);
      int uncompressedSize = uint24(header, 5);
      if (version != 0 || compressedSize > MAX_COMPRESSED || uncompressedSize > MAX_UNCOMPRESSED) {
        throw new IOException("Invalid header of chunk " + chunk + " at byte " + offset + " of xorb range");
      }
      offset += HEADER_LENGTH;
      if (length - offset < compressedSize) {
        throw new IOException("Xorb range of " + length + " bytes ends within chunk " + chunk);
      }
      skip(in, compressedSize);
      offset += compressedSize;
      uncompressed += uncompressedSize;
    }
    if (offset != length) {
      throw new IOException("Xorb range of " + length + " bytes has " + (length - offset) + " bytes after its "
          + chunks + " chunks");
    }
    if (in.read() != -1) {
      throw new IOException("Xorb range is longer than " + length + " bytes");
    }
    return uncompressed;
  }

  private static int uint24(final byte[] bytes, final int offset) {
    return (bytes[offset] & 0xff) | (bytes[offset + 1] & 0xff) << 8 | (bytes[offset + 2] & 0xff) << 16;
  }

  private static void skip(final InputStream in, final long n) throws IOException {
    try {
      in.skipNBytes(n);
    }
    catch (EOFException e) {
      throw new IOException("Xorb range ends early", e);
    }
  }
}
