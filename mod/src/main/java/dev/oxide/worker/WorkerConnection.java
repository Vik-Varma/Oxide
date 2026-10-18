package dev.oxide.worker;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * One running {@code oxide-worker} process for one dimension. See
 * {@code protocol/README.md} for the message layout.
 */
public final class WorkerConnection implements AutoCloseable {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int PROTOCOL_VERSION = 1;
	private static final int MAX_FRAME_LENGTH = 64 * 1024 * 1024;

	private static final byte HELLO = 0x01;
	private static final byte TERRAIN_REQUEST = 0x02;
	private static final byte SHUTDOWN = 0x03;
	private static final byte READY = (byte) 0x81;
	private static final byte TERRAIN = (byte) 0x82;
	private static final byte ERROR = (byte) 0x83;

	private final String dimension;
	private final Process process;
	private final DataOutputStream output;
	private final DataInputStream input;
	private final BlockState[] states;
	private final int minY;
	private final int sectionCount;
	private final Map<Integer, CompletableFuture<TerrainData>> pending = new ConcurrentHashMap<>();
	private final AtomicInteger nextRequestId = new AtomicInteger(1);
	private volatile @Nullable String failure;
	/** Set by {@link #close()}, so the expected end of the process is not reported as a failure. */
	private volatile boolean closing;

	private WorkerConnection(String dimension, Process process, DataOutputStream output, DataInputStream input,
		BlockState[] states, int minY, int sectionCount) {
		this.dimension = dimension;
		this.process = process;
		this.output = output;
		this.input = input;
		this.states = states;
		this.minY = minY;
		this.sectionCount = sectionCount;
	}

	/** Starts a worker and completes the Hello/Ready handshake. */
	public static WorkerConnection start(Path executable, long seed, String dimension, Path cacheDir, int threads)
		throws IOException {
		Process process = new ProcessBuilder(executable.toString()).start();
		forwardStderr(process, dimension);
		try {
			DataOutputStream output = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
			DataInputStream input = new DataInputStream(new BufferedInputStream(process.getInputStream()));

			byte[] dimensionBytes = dimension.getBytes(StandardCharsets.UTF_8);
			byte[] cacheDirBytes = cacheDir.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
			ByteBuffer hello = ByteBuffer.allocate(1 + 4 + 8 + 2 + dimensionBytes.length + 2 + cacheDirBytes.length + 2);
			hello.put(HELLO).putInt(PROTOCOL_VERSION).putLong(seed);
			putString(hello, dimensionBytes);
			putString(hello, cacheDirBytes);
			hello.putShort((short) threads);
			writeFrame(output, hello);

			ByteBuffer ready = readFrame(input);
			if (ready == null) {
				throw new IOException("worker exited before Ready");
			}
			byte type = ready.get();
			if (type == ERROR) {
				ready.getInt();
				throw new IOException("worker refused the session: " + readString(ready));
			}
			if (type != READY) {
				throw new IOException("expected Ready, got message 0x" + Integer.toHexString(type & 0xff));
			}
			int version = ready.getInt();
			if (version != PROTOCOL_VERSION) {
				throw new IOException("worker protocol " + version + ", expected " + PROTOCOL_VERSION);
			}
			int minY = ready.getInt();
			int sectionCount = Short.toUnsignedInt(ready.getShort());
			BlockState[] states = BlockStateTable.read(ready);

			WorkerConnection connection = new WorkerConnection(dimension, process, output, input, states, minY, sectionCount);
			Thread reader = new Thread(connection::readReplies, "oxide-worker-reader-" + dimension);
			reader.setDaemon(true);
			reader.start();
			return connection;
		} catch (IOException | RuntimeException error) {
			process.destroyForcibly();
			throw error instanceof IOException io ? io : new IOException(error);
		}
	}

	public int minY() {
		return this.minY;
	}

	public int sectionCount() {
		return this.sectionCount;
	}

	/** The vanilla block state for a worker state id; ids in {@link TerrainData} are validated. */
	public BlockState state(short id) {
		return this.states[Short.toUnsignedInt(id)];
	}

	/** Why the worker can no longer be used, or null while it is healthy. */
	public @Nullable String failure() {
		return this.failure;
	}

	/** Requests one chunk's terrain. The future fails if the worker reports an error or dies. */
	public CompletableFuture<TerrainData> requestTerrain(int chunkX, int chunkZ) {
		String failure = this.failure;
		if (failure != null) {
			return CompletableFuture.failedFuture(new IOException(failure));
		}
		int requestId = this.nextRequestId.getAndIncrement();
		CompletableFuture<TerrainData> future = new CompletableFuture<>();
		this.pending.put(requestId, future);
		ByteBuffer request = ByteBuffer.allocate(13);
		request.put(TERRAIN_REQUEST).putInt(requestId).putInt(chunkX).putInt(chunkZ);
		try {
			synchronized (this.output) {
				writeFrame(this.output, request);
			}
		} catch (IOException error) {
			this.fail("failed to send a request: " + error.getMessage());
		}
		// A failure between the check above and put() would miss this future; fail() covers it.
		if (this.failure != null) {
			this.fail(this.failure);
		}
		return future;
	}

	private void readReplies() {
		try {
			while (true) {
				ByteBuffer frame = readFrame(this.input);
				if (frame == null) {
					this.fail("worker exited");
					return;
				}
				byte type = frame.get();
				int requestId = frame.getInt();
				if (type == TERRAIN) {
					TerrainData data = TerrainData.read(frame, this.sectionCount, this.states.length, this.minY);
					CompletableFuture<TerrainData> future = this.pending.remove(requestId);
					if (future != null) {
						future.complete(data);
					}
				} else if (type == ERROR) {
					String message = readString(frame);
					if (requestId == 0) {
						this.fail("worker failed: " + message);
						return;
					}
					CompletableFuture<TerrainData> future = this.pending.remove(requestId);
					if (future != null) {
						future.completeExceptionally(new IOException(message));
					}
				} else {
					throw new IOException("unexpected message 0x" + Integer.toHexString(type & 0xff));
				}
			}
		} catch (IOException | BufferUnderflowException error) {
			this.fail("invalid reply from worker: " + error);
		}
	}

	/** Marks the worker unusable and fails every pending request so callers fall back to vanilla. */
	private void fail(String reason) {
		if (this.failure == null) {
			this.failure = reason;
			if (!this.closing) {
				LOGGER.warn("Oxide worker for {} stopped: {}. Vanilla generates its terrain from now on.", this.dimension, reason);
			}
		}
		for (Integer requestId : this.pending.keySet()) {
			CompletableFuture<TerrainData> future = this.pending.remove(requestId);
			if (future != null) {
				future.completeExceptionally(new IOException(this.failure));
			}
		}
	}

	/** Asks the worker to save its cache and exit; kills it if it takes too long. */
	@Override
	public void close() {
		this.closing = true;
		try {
			synchronized (this.output) {
				writeFrame(this.output, ByteBuffer.allocate(1).put(SHUTDOWN));
				this.output.close();
			}
		} catch (IOException ignored) {
			// Already gone; the wait below returns at once.
		}
		try {
			if (!this.process.waitFor(30, TimeUnit.SECONDS)) {
				LOGGER.warn("Oxide worker for {} did not exit in time; stopping it", this.dimension);
				this.process.destroyForcibly();
			}
		} catch (InterruptedException interrupted) {
			this.process.destroyForcibly();
			Thread.currentThread().interrupt();
		}
		this.fail("worker shut down");
	}

	private static void writeFrame(DataOutputStream output, ByteBuffer payload) throws IOException {
		payload.flip();
		output.writeInt(payload.remaining());
		output.write(payload.array(), payload.arrayOffset() + payload.position(), payload.remaining());
		output.flush();
	}

	private static @Nullable ByteBuffer readFrame(DataInputStream input) throws IOException {
		int length;
		try {
			length = input.readInt();
		} catch (EOFException end) {
			return null;
		}
		if (length <= 0 || length > MAX_FRAME_LENGTH) {
			throw new IOException("invalid frame length " + length);
		}
		byte[] payload = new byte[length];
		input.readFully(payload);
		return ByteBuffer.wrap(payload);
	}

	private static void putString(ByteBuffer buffer, byte[] utf8) throws IOException {
		if (utf8.length > 0xffff) {
			throw new IOException("string too long for the protocol: " + utf8.length + " bytes");
		}
		buffer.putShort((short) utf8.length).put(utf8);
	}

	static String readString(ByteBuffer buffer) {
		byte[] bytes = new byte[Short.toUnsignedInt(buffer.getShort())];
		buffer.get(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static void forwardStderr(Process process, String dimension) {
		Thread thread = new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					LOGGER.info("[{}] {}", dimension, line);
				}
			} catch (IOException ignored) {
				// The process exited.
			}
		}, "oxide-worker-log-" + dimension);
		thread.setDaemon(true);
		thread.start();
	}
}
