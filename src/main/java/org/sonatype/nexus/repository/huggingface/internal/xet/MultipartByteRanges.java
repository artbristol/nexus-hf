/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface.internal.xet;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * Reads a {@code multipart/byteranges} response body (RFC 9110, section 14.6) part by part, without buffering it: XET
 * storage answers a request for several ranges of a xorb with one.
 */
public final class MultipartByteRanges
{
  private static final Pattern BOUNDARY = Pattern.compile("boundary=(?:\"([^\"]+)\"|([^;\\s]+))");

  private static final Pattern CONTENT_RANGE = Pattern.compile("^bytes\\s+(\\d+)-(\\d+)/(?:\\d+|\\*)$");

  private static final int MAX_LINE = 8 * 1024;

  /**
   * Receives the parts in order. The stream ends with the part; what is not read of it is skipped.
   */
  @FunctionalInterface
  public interface PartConsumer
  {
    void accept(long first, long last, InputStream data) throws IOException;
  }

  private MultipartByteRanges() {
  }

  /**
   * The boundary of a {@code multipart/byteranges} content type, or {@code null} for any other content type.
   */
  @Nullable
  public static String boundary(@Nullable final String contentType) {
    if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/byteranges")) {
      return null;
    }
    Matcher matcher = BOUNDARY.matcher(contentType);
    return matcher.find() ? (matcher.group(1) != null ? matcher.group(1) : matcher.group(2)) : null;
  }

  public static void read(final InputStream in, final String boundary, final PartConsumer consumer)
      throws IOException
  {
    String delimiter = "--" + boundary;
    String line = readLine(in);
    while (line != null && !line.equals(delimiter)) {
      line = readLine(in); // preamble
    }
    while (line != null && line.equals(delimiter)) {
      long[] range = null;
      for (line = readLine(in); line != null && !line.isEmpty(); line = readLine(in)) {
        int colon = line.indexOf(':');
        if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Range")) {
          Matcher matcher = CONTENT_RANGE.matcher(line.substring(colon + 1).trim());
          if (!matcher.matches()) {
            throw new IOException("Invalid Content-Range in multipart body: " + line);
          }
          range = new long[]{Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2))};
        }
      }
      if (line == null || range == null || range[1] < range[0]) {
        throw new IOException("Part of multipart body without valid Content-Range");
      }
      BoundedInputStream data = new BoundedInputStream(in, range[1] - range[0] + 1);
      consumer.accept(range[0], range[1], data);
      data.skipRest();
      // the CRLF after the data belongs to the next delimiter
      line = readLine(in);
      if (line != null && line.isEmpty()) {
        line = readLine(in);
      }
    }
    if (line == null || !line.equals(delimiter + "--")) {
      throw new IOException("Multipart body ends without its closing delimiter");
    }
  }

  @Nullable
  private static String readLine(final InputStream in) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1 && b != '\n') {
      if (line.size() >= MAX_LINE) {
        throw new IOException("Line too long in multipart body");
      }
      line.write(b);
    }
    if (b == -1 && line.size() == 0) {
      return null;
    }
    String value = line.toString(StandardCharsets.ISO_8859_1);
    return value.endsWith("\r") ? value.substring(0, value.length() - 1) : value;
  }

  private static final class BoundedInputStream
      extends FilterInputStream
  {
    private long remaining;

    BoundedInputStream(final InputStream in, final long length) {
      super(in);
      this.remaining = length;
    }

    @Override
    public int read() throws IOException {
      if (remaining <= 0) {
        return -1;
      }
      int b = super.read();
      if (b == -1) {
        throw new IOException("Multipart body ends within a part");
      }
      remaining--;
      return b;
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) throws IOException {
      if (remaining <= 0) {
        return -1;
      }
      int n = super.read(buffer, offset, (int) Math.min(length, remaining));
      if (n == -1) {
        throw new IOException("Multipart body ends within a part");
      }
      remaining -= n;
      return n;
    }

    @Override
    public long skip(final long n) throws IOException {
      byte[] buffer = new byte[8192];
      long skipped = 0;
      while (skipped < n) {
        int read = read(buffer, 0, (int) Math.min(buffer.length, n - skipped));
        if (read == -1) {
          break;
        }
        skipped += read;
      }
      return skipped;
    }

    @Override
    public int available() throws IOException {
      return (int) Math.min(super.available(), remaining);
    }

    @Override
    public void close() {
      // the parts share the body
    }

    void skipRest() throws IOException {
      skip(remaining);
    }
  }
}
