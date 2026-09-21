package io.github.kineticnapier.atcrafter;

import io.github.kineticnapier.atcrafter.block.ModBlocks;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AtCrafter implements ModInitializer {
	public static final String MOD_ID = "atcrafter";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModBlocks.initialize();
		LOGGER.info("AtCrafter initialized");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
