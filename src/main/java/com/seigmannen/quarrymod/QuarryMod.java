package com.seigmannen.quarrymod;

import com.seigmannen.quarrymod.block.ModBlocks;
import com.seigmannen.quarrymod.block.entity.ModBlockEntities;
import com.seigmannen.quarrymod.menu.ModMenuTypes;
import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuarryMod implements ModInitializer {
	public static final String MOD_ID = "quarrymod";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		// Registrer alle blokkene (og BlockItems) i moden.
		ModBlocks.initialize();
		// Registrer block entities (må komme etter blokkene, siden de peker på quarry-blokken).
		ModBlockEntities.initialize();
		// Registrer menytypene (GUI-ene).
		ModMenuTypes.initialize();

		LOGGER.info("Quarry Mod lastet!");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
