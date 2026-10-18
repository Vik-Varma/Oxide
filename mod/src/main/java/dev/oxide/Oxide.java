package dev.oxide;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class Oxide implements ModInitializer {
	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTING.register(OxideServer::start);
		// After the worlds are saved, so no chunk is left waiting on a worker.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> OxideServer.stop());
	}
}
