package com.seigmannen.quarrymod.component;

import com.seigmannen.quarrymod.QuarryMod;
import net.fabricmc.fabric.api.item.v1.ItemComponentTooltipProviderRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Data-komponentene i moden.
 */
public final class ModDataComponents {
	private ModDataComponents() {
	}

	/** Fyll-lageret på quarry-itemet. Kopieres fra block entityen av loot table-en når quarrien brytes. */
	public static final DataComponentType<FillerStorage> FILLER_STORAGE = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			QuarryMod.id("filler_storage"),
			DataComponentType.<FillerStorage>builder()
					.persistent(FillerStorage.CODEC)
					.networkSynchronized(FillerStorage.STREAM_CODEC)
					.build());

	/** Kalles fra QuarryMod.onInitialize(), før blokkene. */
	public static void initialize() {
		// Egne komponenter vises ikke i tooltipen av seg selv; Fabric legger den inn øverst.
		ItemComponentTooltipProviderRegistry.addFirst(FILLER_STORAGE);
	}
}
