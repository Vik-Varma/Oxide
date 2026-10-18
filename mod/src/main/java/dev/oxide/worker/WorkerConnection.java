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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * One running {@code oxide-worker} process for one dimension. See
 * {@code protocol/README.md} for the message layout.
 */
public final class WorkerConnection implements AutoCloseable {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int PROTOCOL_VERSION = 2;
	private static final int MAX_FRAME_LENGTH = 64 * 1024 * 1024;

	private static final byte HELLO = 0x01;
	private static final byte TERRAIN_REQUEST = 0x02;
	private static final byte SHUTDOWN = 0x03;
	private static final byte FULL_REQUEST = 0x04;
	private static final byte READY = (byte) 0x81;
	private static final byte TERRAIN = (byte) 0x82;
	private static final byte ERROR = (byte) 0x83;
	private static final byte FULL = (byte) 0x84;

	private final String dimension;
	private final Process process;
	private final DataOutputStream output;
	private final DataInputStream input;
	private final BlockState[] states;
	private final Holder<Biome>[] biomes;
	private final int minY;
	private final int sectionCount;
	private final Map<Integer, CompletableFuture<TerrainData>> pendingTerrain = new ConcurrentHashMap<>();
	private final Map<Integer, CompletableFuture<FullChunkData>> pendingFull = new ConcurrentHashMap<>();
	/** When each unanswered request was sent, to report requests the worker never answers. */
	private final Map<Integer, Sent> sent = new ConcurrentHashMap<>();
	private final AtomicInteger nextRequestId = new AtomicInteger(1);
	private volatile @Nullable String failure;
	/** Set by {@link #close()}, so the expected end of the process is not reported as a failure. */
	private volatile boolean closing;

	private WorkerConnection(String dimension, Process process, DataOutputStream output, DataInputStream input,
		BlockState[] states, Holder<Biome>[] biomes, int minY, int sectionCount) {
		this.dimension = dimension;
		this.process = process;
		this.output = output;
		this.input = input;
		this.states = states;
		this.biomes = biomes;
		this.minY = minY;
		this.sectionCount = sectionCount;
	}

	private record Sent(String kind, int chunkX, int chunkZ, long nanos) {
	}

	/** How long a request may go unanswered before it is reported. */
	private static final long SLOW_REQUEST_NANOS = 30_000_000_000L;

	/** Starts a worker and completes the Hello/Ready handshake. */
	public static WorkerConnection start(Path executable, long seed, String dimension, Path cacheDir, int threads,
		Registry<Biome> biomeRegistry) throws IOException {
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
			Holder<Biome>[] biomes = readBiomes(ready, biomeRegistry);

			WorkerConnection connection = new WorkerConnection(dimension, process, output, input, states, biomes, minY, sectionCount);
			Thread reader = new Thread(connection::readReplies, "oxide-worker-reader-" + dimension);
			reader.setDaemon(true);
			reader.start();
			Thread watch = new Thread(connection::watchSlowRequests, "oxide-worker-watch-" + dimension);
			watch.setDaemon(true);
			watch.start();
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

	/** Requests one chunk's biomes and terrain. The future fails if the worker reports an error or dies. */
	public CompletableFuture<TerrainData> requestTerrain(int chunkX, int chunkZ) {
		return this.request(TERRAIN_REQUEST, this.pendingTerrain, chunkX, chunkZ);
	}

	/** Requests a whole generated chunk. The future fails if the worker reports an error or dies. */
	public CompletableFuture<FullChunkData> requestFull(int chunkX, int chunkZ) {
		return this.request(FULL_REQUEST, this.pendingFull, chunkX, chunkZ);
	}

	private <T> CompletableFuture<T> request(byte type, Map<Integer, CompletableFuture<T>> pending, int chunkX, int chunkZ) {
		String failure = this.failure;
		if (failure != null) {
			return CompletableFuture.failedFuture(new IOException(failure));
		}
		int requestId = this.nextRequestId.getAndIncrement();
		CompletableFuture<T> future = new CompletableFuture<>();
		pending.put(requestId, future);
		this.sent.put(requestId, new Sent(type == FULL_REQUEST ? "whole chunk" : "terrain", chunkX, chunkZ, System.nanoTime()));
		ByteBuffer request = ByteBuffer.allocate(13);
		request.put(type).putInt(requestId).putInt(chunkX).putInt(chunkZ);
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
				this.sent.remove(requestId);
				if (type == TERRAIN) {
					TerrainData data = TerrainData.read(frame, this.sectionCount, this.states.length, this.biomes, this.minY);
					complete(this.pendingTerrain, requestId, data);
				} else if (type == FULL) {
					// A malformed chunk fails only its own request: the frame was read whole,
					// so the stream is still in sync.
					CompletableFuture<FullChunkData> future = this.pendingFull.remove(requestId);
					try {
						FullChunkData data = FullChunkData.read(frame, this.sectionCount, this.states.length, this.biomes, this.minY);
						if (future != null) {
							future.complete(data);
						}
					} catch (IOException | RuntimeException error) {
						if (future != null) {
							future.completeExceptionally(new IOException("invalid Full reply: " + error, error));
						}
					}
				} else if (type == ERROR) {
					String message = readString(frame);
					if (requestId == 0) {
						this.fail("worker failed: " + message);
						return;
					}
					IOException error = new IOException(message);
					CompletableFuture<TerrainData> terrain = this.pendingTerrain.remove(requestId);
					if (terrain != null) {
						terrain.completeExceptionally(error);
					}
					CompletableFuture<FullChunkData> full = this.pendingFull.remove(requestId);
					if (full != null) {
						full.completeExceptionally(error);
					}
				} else {
					throw new IOException("unexpected message 0x" + Integer.toHexString(type & 0xff));
				}
			}
		} catch (IOException | BufferUnderflowException error) {
			this.fail("invalid reply from worker: " + error);
		}
	}

	/** Logs requests the worker has not answered for a long time, once each, until the worker stops. */
	private void watchSlowRequests() {
		while (this.failure == null) {
			try {
				Thread.sleep(10_000);
			} catch (InterruptedException interrupted) {
				return;
			}
			long now = System.nanoTime();
			this.sent.entrySet().removeIf(entry -> {
				Sent request = entry.getValue();
				if (now - request.nanos() < SLOW_REQUEST_NANOS) {
					return false;
				}
				LOGGER.warn("Oxide worker for {} has not answered {} request {} for chunk [{}, {}] in {} s",
					this.dimension, request.kind(), entry.getKey(), request.chunkX(), request.chunkZ(), (now - request.nanos()) / 1_000_000_000L);
				return true;
			});
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
		failAll(this.pendingTerrain, this.failure);
		failAll(this.pendingFull, this.failure);
	}

	private static <T> void complete(Map<Integer, CompletableFuture<T>> pending, int requestId, T value) {
		CompletableFuture<T> future = pending.remove(requestId);
		if (future != null) {
			future.complete(value);
		}
	}

	private static <T> void failAll(Map<Integer, CompletableFuture<T>> pending, String reason) {
		for (Integer requestId : pending.keySet()) {
			CompletableFuture<T> future = pending.remove(requestId);
			if (future != null) {
				future.completeExceptionally(new IOException(reason));
			}
		}
	}

	/** Resolves the Ready message's biome table; fails if any biome does not exist in this game. */
	@SuppressWarnings("unchecked")
	private static Holder<Biome>[] readBiomes(ByteBuffer buffer, Registry<Biome> registry) throws IOException {
		int count = Short.toUnsignedInt(buffer.getShort());
		Holder<Biome>[] biomes = new Holder[count];
		for (int id = 0; id < count; id++) {
			String name = readString(buffer);
			Identifier biomeId = Identifier.tryParse(name);
			Optional<Holder.Reference<Biome>> biome = biomeId == null
				? Optional.empty()
				: registry.get(ResourceKey.create(Registries.BIOME, biomeId));
			if (biome.isEmpty()) {
				throw new IOException("biome " + id + ": unknown biome " + name);
			}
			biomes[id] = biome.get();
		}
		return biomes;
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
