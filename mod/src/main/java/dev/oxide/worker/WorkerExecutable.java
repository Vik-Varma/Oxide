package dev.oxide.worker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;

import net.fabricmc.loader.api.FabricLoader;

/** Finds the {@code oxide-worker} binary for this machine. */
public final class WorkerExecutable {
	/** System property pointing at a locally built worker, used for development. */
	public static final String PATH_PROPERTY = "oxide.worker";

	private WorkerExecutable() {
	}

	/**
	 * The {@value #PATH_PROPERTY} system property if set, otherwise the binary bundled in the
	 * mod jar for this platform, copied out next to the game directory. Empty when neither
	 * exists.
	 */
	public static Optional<Path> find(String modVersion) throws IOException {
		String override = System.getProperty(PATH_PROPERTY);
		if (override != null) {
			Path path = Path.of(override);
			if (!Files.isExecutable(path)) {
				throw new IOException(PATH_PROPERTY + " points at " + path + ", which is not an executable file");
			}
			return Optional.of(path);
		}

		String platform = platform();
		String fileName = platform.startsWith("windows") ? "oxide-worker.exe" : "oxide-worker";
		String resource = "/oxide-worker/" + platform + "/" + fileName;
		try (InputStream bundled = WorkerExecutable.class.getResourceAsStream(resource)) {
			if (bundled == null) {
				return Optional.empty();
			}
			Path target = FabricLoader.getInstance().getGameDir()
				.resolve(".oxide").resolve("bin").resolve(modVersion).resolve(fileName);
			Files.createDirectories(target.getParent());
			Path temporary = Files.createTempFile(target.getParent(), fileName, ".tmp");
			Files.copy(bundled, temporary, StandardCopyOption.REPLACE_EXISTING);
			temporary.toFile().setExecutable(true);
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return Optional.of(target);
		}
	}

	/** {@code <os>-<arch>}, e.g. {@code linux-x86_64} or {@code macos-aarch64}. */
	public static String platform() {
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
		String osName = os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
		String archName = switch (arch) {
			case "amd64", "x86_64" -> "x86_64";
			case "aarch64", "arm64" -> "aarch64";
			default -> arch;
		};
		return osName + "-" + archName;
	}
}
