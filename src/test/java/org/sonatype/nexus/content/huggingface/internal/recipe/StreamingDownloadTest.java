/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.huggingface.internal.recipe;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.sonatype.nexus.repository.huggingface.HuggingFaceFileInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StreamingDownloadTest
{
  private static final String COMMIT = "607a30d783dfa663caf39e06633721c8d4cfcd7e";

  private final ExecutorService executor = Executors.newCachedThreadPool();

  private Path file;

  @Before
  public void setUp() throws IOException {
    file = Files.createTempFile("streaming-download-test", ".part");
  }

  @After
  public void tearDown() throws IOException {
    executor.shutdownNow();
    Files.deleteIfExists(file);
  }

  @Test
  public void readersStartingAtAnyTimeGetTheWholeVerifiedFile() throws Exception {
    byte[] content = randomBytes(5 * 1024 * 1024 + 123);
    StreamingDownload download = new StreamingDownload(file, lfsInfo(content), "application/octet-stream");
    SlowInputStream upstream = new SlowInputStream(content, 256 * 1024);

    Future<byte[]> early = executor.submit(() -> readAll(download));
    Future<?> producer = executor.submit(() -> {
      download.download(upstream);
      return null;
    });
    Thread.sleep(20);
    Future<byte[]> late = executor.submit(() -> readAll(download));

    producer.get(30, TimeUnit.SECONDS);
    assertTrue(download.isComplete());
    assertArrayEquals(content, early.get(30, TimeUnit.SECONDS));
    assertArrayEquals(content, late.get(30, TimeUnit.SECONDS));
    // after completion, a new reader still works until the file is deleted
    assertArrayEquals(content, readAll(download));
  }

  @Test
  public void verifiesGitBlobIdOfSmallFiles() throws Exception {
    byte[] content = "{\"model_type\": \"gpt2\"}\n".getBytes(StandardCharsets.UTF_8);
    MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
    sha1.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
    String blobId = HexFormat.of().formatHex(sha1.digest(content));
    StreamingDownload download =
        new StreamingDownload(file, new HuggingFaceFileInfo(COMMIT, blobId, (long) content.length), null);
    download.download(new ByteArrayInputStream(content));
    assertArrayEquals(content, readAll(download));
  }

  @Test
  public void corruptContentIsNeverFullyReleased() throws Exception {
    byte[] content = randomBytes(3 * 1024 * 1024);
    HuggingFaceFileInfo info = lfsInfo(content);
    byte[] corrupt = content.clone();
    corrupt[corrupt.length - 1] ^= 1;
    StreamingDownload download = new StreamingDownload(file, info, null);

    Future<byte[]> reader = executor.submit(() -> readAll(download));
    assertThrows(IOException.class, () -> download.download(new ByteArrayInputStream(corrupt)));
    try {
      reader.get(30, TimeUnit.SECONDS);
      fail("reader must fail");
    }
    catch (java.util.concurrent.ExecutionException e) {
      assertTrue(e.getCause() instanceof IOException);
    }
    assertNull("no new readers after a failure", download.openReader());
  }

  /**
   * A dropped connection is resumed where it stopped; readers that were waiting see one uninterrupted, correct body.
   */
  @Test
  public void resumesAfterTransportFailures() throws Exception {
    byte[] content = randomBytes(3 * 1024 * 1024 + 7);
    StreamingDownload download = new StreamingDownload(file, lfsInfo(content), null);
    List<Long> opened = new ArrayList<>();
    StreamingDownload.Upstream flaky = offset -> {
      opened.add(offset);
      InputStream rest = new ByteArrayInputStream(content, (int) offset, content.length - (int) offset);
      // the first two connections drop after 1 MB (not on a chunk boundary)
      return opened.size() <= 2 ? new FailingAfter(rest, 1024 * 1024 + 123) : rest;
    };
    Future<byte[]> reader = executor.submit(() -> readAll(download));
    download.download(flaky, 3);
    assertArrayEquals(content, reader.get(30, TimeUnit.SECONDS));
    assertEquals(0L, (long) opened.get(0));
    assertEquals(3, opened.size());
    assertTrue("resumed, not restarted", opened.get(1) > 0 && opened.get(2) > opened.get(1));
  }

  /**
   * A connection that fails before a whole chunk has arrived is reconnected from the start.
   */
  @Test
  public void reconnectsWhenNothingWasWrittenYet() throws Exception {
    byte[] content = randomBytes(300_000);
    StreamingDownload download = new StreamingDownload(file, lfsInfo(content), null);
    List<Long> opened = new ArrayList<>();
    StreamingDownload.Upstream failsEarly = offset -> {
      opened.add(offset);
      InputStream rest = new ByteArrayInputStream(content, (int) offset, content.length - (int) offset);
      return opened.size() == 1 ? new FailingAfter(rest, 1000) : rest;
    };
    download.download(failsEarly, 3);
    assertArrayEquals(content, readAll(download));
    assertEquals(List.of(0L, 0L), opened);
  }

  @Test
  public void givesUpAfterTooManyTransportFailures() {
    byte[] content = randomBytes(3 * 1024 * 1024);
    StreamingDownload download = new StreamingDownload(file, lfsInfo(content), null);
    StreamingDownload.Upstream broken = offset -> new FailingAfter(
        new ByteArrayInputStream(content, (int) offset, content.length - (int) offset), 100_000);
    assertThrows(IOException.class, () -> download.download(broken, 1));
  }

  /**
   * A body that ends early without an error (no Content-Length upstream) is resumed too.
   */
  @Test
  public void resumesABodyThatEndsEarly() throws Exception {
    byte[] content = randomBytes(2 * 1024 * 1024 + 1);
    StreamingDownload download = new StreamingDownload(file, lfsInfo(content), null);
    List<Long> opened = new ArrayList<>();
    StreamingDownload.Upstream shortFirst = offset -> {
      opened.add(offset);
      int end = opened.size() == 1 ? 1000 : content.length;
      return new ByteArrayInputStream(content, (int) offset, end - (int) offset);
    };
    download.download(shortFirst, 3);
    assertArrayEquals(content, readAll(download));
    assertEquals(List.of(0L, 1000L), opened);
  }

  @Test
  public void corruptContentIsNotRetried() {
    byte[] content = randomBytes(10_000);
    byte[] corrupt = content.clone();
    corrupt[5000] ^= 1;
    StreamingDownload download = new StreamingDownload(file, lfsInfo(content), null);
    List<Long> opened = new ArrayList<>();
    StreamingDownload.Upstream upstream = offset -> {
      opened.add(offset);
      return new ByteArrayInputStream(corrupt);
    };
    assertThrows(IOException.class, () -> download.download(upstream, 3));
    assertEquals(1, opened.size());
  }

  private static final class FailingAfter
      extends InputStream
  {
    private final InputStream delegate;

    private long remaining;

    FailingAfter(final InputStream delegate, final long bytes) {
      this.delegate = delegate;
      this.remaining = bytes;
    }

    @Override
    public int read() throws IOException {
      if (remaining-- <= 0) {
        throw new IOException("connection reset");
      }
      return delegate.read();
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
      if (remaining <= 0) {
        throw new IOException("connection reset");
      }
      int n = delegate.read(b, off, (int) Math.min(len, remaining));
      if (n > 0) {
        remaining -= n;
      }
      return n;
    }
  }

  @Test
  public void emptyFile() throws Exception {
    // git blob id of an empty file
    HuggingFaceFileInfo info = new HuggingFaceFileInfo(COMMIT, "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", 0L);
    StreamingDownload download = new StreamingDownload(file, info, null);
    Future<byte[]> reader = executor.submit(() -> readAll(download));
    download.download(new ByteArrayInputStream(new byte[0]));
    assertArrayEquals(new byte[0], reader.get(30, TimeUnit.SECONDS));
  }

  /**
   * The chunk hold-back must still release everything when the size is an exact multiple of the chunk size.
   */
  @Test
  public void sizeThatIsAMultipleOfTheChunkSize() throws Exception {
    byte[] content = randomBytes(2 * 1024 * 1024);
    StreamingDownload download = new StreamingDownload(file, lfsInfo(content), null);
    Future<byte[]> reader = executor.submit(() -> readAll(download));
    download.download(new ByteArrayInputStream(content));
    assertArrayEquals(content, reader.get(30, TimeUnit.SECONDS));
  }

  /**
   * Without size or a recognisable etag there is nothing to verify against; the content is passed on as is.
   */
  @Test
  public void unverifiableContentIsStillServed() throws Exception {
    byte[] content = randomBytes(1500);
    StreamingDownload download = new StreamingDownload(file, new HuggingFaceFileInfo(COMMIT, "W-opaque", null), null);
    download.download(new ByteArrayInputStream(content));
    assertArrayEquals(content, readAll(download));
  }

  @Test
  public void readerWaitingForTheFirstByteFailsWhenUpstreamFails() throws Exception {
    byte[] content = randomBytes(1000);
    StreamingDownload download = new StreamingDownload(file, lfsInfo(content), null);
    Future<byte[]> reader = executor.submit(() -> readAll(download));
    InputStream broken = new InputStream()
    {
      @Override
      public int read() throws IOException {
        throw new IOException("connection reset");
      }
    };
    assertThrows(IOException.class, () -> download.download(broken));
    try {
      reader.get(30, TimeUnit.SECONDS);
      fail("reader must fail");
    }
    catch (java.util.concurrent.ExecutionException e) {
      assertTrue(e.getCause() instanceof IOException);
    }
  }

  @Test
  public void truncatedContentFails() {
    byte[] content = randomBytes(1000);
    StreamingDownload download = new StreamingDownload(file, lfsInfo(content), null);
    byte[] truncated = new byte[999];
    System.arraycopy(content, 0, truncated, 0, truncated.length);
    assertThrows(IOException.class, () -> download.download(new ByteArrayInputStream(truncated)));
  }

  @Test
  public void noReadersAfterDeletion() throws Exception {
    byte[] content = randomBytes(100);
    StreamingDownload download = new StreamingDownload(file, lfsInfo(content), null);
    download.download(new ByteArrayInputStream(content));
    try (InputStream open = download.openReader()) {
      download.delete();
      assertNull(download.openReader());
      // a reader opened before deletion keeps working
      assertArrayEquals(content, open.readAllBytes());
    }
  }

  private static byte[] readAll(final StreamingDownload download) throws IOException {
    try (InputStream in = download.openReader()) {
      return in.readAllBytes();
    }
  }

  private static byte[] randomBytes(final int size) {
    byte[] bytes = new byte[size];
    new Random(size).nextBytes(bytes);
    return bytes;
  }

  private static HuggingFaceFileInfo lfsInfo(final byte[] content) {
    try {
      String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
      return new HuggingFaceFileInfo(COMMIT, sha256, (long) content.length);
    }
    catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Delivers the content in small pieces with pauses, like a network stream.
   */
  private static final class SlowInputStream
      extends InputStream
  {
    private final ByteArrayInputStream delegate;

    private final int piece;

    SlowInputStream(final byte[] content, final int piece) {
      this.delegate = new ByteArrayInputStream(content);
      this.piece = piece;
    }

    @Override
    public int read() {
      return delegate.read();
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
      try {
        Thread.sleep(2);
      }
      catch (InterruptedException e) {
        throw new IOException(e);
      }
      return delegate.read(b, off, Math.min(len, piece));
    }
  }
}
