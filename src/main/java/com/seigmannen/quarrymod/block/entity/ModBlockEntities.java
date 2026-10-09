package com.seigmannen.quarrymod.block.entity;

import com.seigmannen.quarrymod.QuarryMod;
import com.seigmannen.quarrymod.block.ModBlocks;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Registrerer alle block entity-typene i moden.
 * En BlockEntityType kobler en blokk (quarry) til klassen som holder dataene (QuarryBlockEntity).
 */
public final class ModBlockEntities {
	private ModBlockEntities() {
	}

	public static final BlockEntityType<QuarryBlockEntity> QUARRY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			QuarryMod.id("quarry"),
			// QuarryBlockEntity::new lager en ny block entity; Set.of(...) sier hvilke blokker den hører til.
			new BlockEntityType<>(QuarryBlockEntity::new, Set.of(ModBlocks.QUARRY))
	);

	/**
	 * Kalles fra QuarryMod.onInitialize() (etter ModBlocks), slik at typen over blir registrert.
	 */
	public static void initialize() {
	}
}
