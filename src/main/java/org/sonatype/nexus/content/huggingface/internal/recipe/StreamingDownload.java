/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.huggingface.internal.recipe;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.annotation.Nullable;

import org.sonatype.nexus.repository.huggingface.HuggingFaceFileInfo;

import com.google.common.annotations.VisibleForTesting;

import static java.nio.file.StandardOpenOption.READ;
import static java.util.Objects.requireNonNull;

/**
 * An upstream download being written to a temporary file, which any number of clients read while it grows.
 *
 * Bytes are only released to readers once they are known to be good: the most recently written chunk is held back
 * until the next one arrives, and the last one until the whole file has been checked against the size and hash the Hub
 * reported (SHA-256 for LFS files, git blob id otherwise). A reader therefore never sees a complete but corrupt file;
 * on failure it gets an {@link IOException} instead.
 */
final class StreamingDownload
{
  /**
   * Also the granularity of the hold-back: readers get bytes once the next chunk has arrived.
   */
  private static final int CHUNK_SIZE = 64 * 1024;

  private static final long READER_WAIT_MILLIS = 1000;

  private final Path file;

  private final HuggingFaceFileInfo info;

  @Nullable
  private final String contentType;

  private final Object lock = new Object();

  // guarded by lock
  private long released;

  private boolean complete;

  private IOException failure;

  private boolean deleted;

  StreamingDownload(final Path file, final HuggingFaceFileInfo info, @Nullable final String contentType) {
    this.file = requireNonNull(file);
    this.info = requireNonNull(info);
    this.contentType = contentType;
  }

  Path file() {
    return file;
  }

  @Nullable
  String contentType() {
    return contentType;
  }

  /**
   * The upstream body, from a given offset. Called once per connection: first with 0, then, to resume after a
   * transport failure, with how much has been written, which may still be 0. Each call must return a new stream.
   */
  @FunctionalInterface
  interface Upstream
  {
    /**
     * @param offset 0 for the whole body, or where to resume
     */
    InputStream open(long offset) throws IOException;
  }

  /**
   * Copies an upstream body that cannot be resumed.
   */
  void download(final InputStream upstream) throws IOException {
    download(offset -> {
      if (offset > 0) {
        throw new IOException("Cannot resume");
      }
      return upstream;
    }, 0);
  }

  /**
   * Copies the upstream body into the file, releasing bytes to readers as they are verified. A transport failure
   * (connection dropped or timed out, body ending early) is resumed where it stopped, up to {@code maxResumes} times,
   * without readers noticing; content that does not match the file metadata is not retried.
   *
   * @throws IOException if the download fails for good; readers fail too
   */
  void download(final Upstream upstream, final int maxResumes) throws IOException {
    try {
      MessageDigest digest = newDigest();
      long written = 0;
      int resumes = 0;
      InputStream in = upstream.open(0);
      try (OutputStream out = Files.newOutputStream(file)) {
        byte[] buffer = new byte[CHUNK_SIZE];
        while (true) {
          int n;
          try {
            n = in.readNBytes(buffer, 0, buffer.length);
            if (n <= 0 && info.size() != null && written < info.size()) {
              throw new IOException("Body ended after " + written + " of " + info.size() + " bytes");
            }
          }
          catch (IOException e) {
            // bytes read into the buffer before the failure are discarded and requested again
            closeQuietly(in);
            if (resumes++ >= maxResumes) {
              throw e;
            }
            in = upstream.open(written);
            continue;
          }
          if (n <= 0) {
            break;
          }
          out.write(buffer, 0, n);
          out.flush();
          digest.update(buffer, 0, n);
          release(written); // everything but this chunk
          written += n;
        }
      }
      finally {
        closeQuietly(in);
      }
      verify(written, digest);
      synchronized (lock) {
        released = written;
        complete = true;
        lock.notifyAll();
      }
    }
    catch (IOException | RuntimeException e) {
      fail(e instanceof IOException io ? io : new IOException(e));
      throw e;
    }
  }

  private static void closeQuietly(final InputStream in) {
    try {
      in.close();
    }
    catch (IOException | RuntimeException e) {
      // nothing more to do with it
    }
  }

  void fail(final IOException e) {
    synchronized (lock) {
      if (!complete) {
        failure = e;
        lock.notifyAll();
      }
    }
  }

  private void release(final long upTo) {
    synchronized (lock) {
      released = upTo;
      lock.notifyAll();
    }
  }

  /**
   * Hub etags: SHA-256 of the content for LFS files, git blob id ({@code sha1("blob <size>\0" + content)}) otherwise.
   */
  private MessageDigest newDigest() {
    try {
      if (info.isSha256Etag()) {
        return MessageDigest.getInstance("SHA-256");
      }
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      if (info.size() != null) {
        sha1.update(("blob " + info.size() + "\0").getBytes(StandardCharsets.US_ASCII));
      }
      return sha1;
    }
    catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void verify(final long written, final MessageDigest digest) throws IOException {
    if (info.size() != null && written != info.size()) {
      throw new IOException("Received " + written + " bytes, upstream reported " + info.size());
    }
    String actual = HexFormat.of().formatHex(digest.digest());
    boolean verifiable = info.isSha256Etag()
        || (info.size() != null && info.etag() != null && info.etag().matches("^[0-9a-f]{40}$"));
    if (verifiable && !info.etag().equals(actual)) {
      throw new IOException("Content hash " + actual + " does not match upstream etag " + info.etag());
    }
  }

  /**
   * Opens a reader from the start of the file, or returns {@code null} once the file is gone (the content is then in
   * the repository, or the download failed).
   */
  @Nullable
  InputStream openReader() throws IOException {
    synchronized (lock) {
      if (deleted || failure != null) {
        return null;
      }
      // opened under the lock so the file cannot be deleted in between; an open file survives deletion
      return new TailingInputStream(FileChannel.open(file, READ));
    }
  }

  /**
   * Deletes the file; readers that have it open carry on.
   */
  void delete() throws IOException {
    synchronized (lock) {
      deleted = true;
      Files.deleteIfExists(file);
    }
  }

  @VisibleForTesting
  boolean isComplete() {
    synchronized (lock) {
      return complete;
    }
  }

  private final class TailingInputStream
      extends InputStream
  {
    private final FileChannel channel;

    private long position;

    TailingInputStream(final FileChannel channel) {
      this.channel = channel;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int n = read(one, 0, 1);
      return n < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
      if (len == 0) {
        return 0;
      }
      long available;
      synchronized (lock) {
        while (released <= position && !complete && failure == null) {
          try {
            lock.wait(READER_WAIT_MILLIS);
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for upstream", e);
          }
        }
        if (failure != null) {
          throw new IOException("Upstream download failed", failure);
        }
        available = released - position;
      }
      if (available <= 0) {
        return -1;
      }
      int n = channel.read(ByteBuffer.wrap(b, off, (int) Math.min(len, available)), position);
      if (n > 0) {
        position += n;
      }
      return n;
    }

    @Override
    public void close() throws IOException {
      channel.close();
    }
  }
}
