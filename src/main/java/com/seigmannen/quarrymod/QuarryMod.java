package com.seigmannen.quarrymod;

import com.seigmannen.quarrymod.block.ModBlocks;
import com.seigmannen.quarrymod.block.entity.ModBlockEntities;
import com.seigmannen.quarrymod.item.ModItems;
import com.seigmannen.quarrymod.menu.ModMenuTypes;
import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuarryMod implements ModInitializer {
	public static final String MOD_ID = "quarrymod";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Registrer alle blokkene (og BlockItems) i moden.
		ModBlocks.initialize();
		// Registrer oppgraderings-itemene (etter blokkene, så de havner etter quarrien i creative-fanen).
		ModItems.initialize();
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
