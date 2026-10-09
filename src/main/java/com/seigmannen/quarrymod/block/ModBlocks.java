package com.seigmannen.quarrymod.block;

import com.seigmannen.quarrymod.QuarryMod;
import java.util.function.Function;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * Samler alle blokkene i moden og registrerer dem i spillet.
 */
public final class ModBlocks {
	private ModBlocks() {
	}

	// Quarry-blokken: like hard som en jernblokk (5.0 hardhet / 6.0 eksplosjonsmotstand),
	// metall-lyd. Den dropper seg selv uansett verktøy, men pickaxe er raskest
	// (fordi blokken ligger i taggen minecraft:mineable/pickaxe).
	public static final Block QUARRY = register(
			"quarry",
			QuarryBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(5.0F, 6.0F)
					.sound(SoundType.METAL)
	);

	/**
	 * Registrerer en blokk og et tilhørende BlockItem (slik at blokken kan ligge i inventory).
	 */
	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties properties) {
		// En ResourceKey er "navnet" til blokken i registeret, f.eks. quarrymod:quarry.
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, QuarryMod.id(name));
		// Blokken må kjenne sin egen ID før den opprettes (setId).
		Block block = factory.apply(properties.setId(blockKey));
		Registry.register(BuiltInRegistries.BLOCK, blockKey, block);

		// Itemet som representerer blokken i inventory.
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, QuarryMod.id(name));
		// useBlockDescriptionPrefix gjør at itemet bruker blokkens navn (block.quarrymod.quarry).
		Item.Properties itemProperties = new Item.Properties().setId(itemKey).useBlockDescriptionPrefix();
		Registry.register(BuiltInRegistries.ITEM, itemKey, new BlockItem(block, itemProperties));

		return block;
	}

	/**
	 * Kalles fra QuarryMod.onInitialize(). Å kalle denne sørger for at klassen lastes,
	 * slik at alle static-feltene over blir registrert.
	 */
	public static void initialize() {
		// Legg quarry-blokken i creative-fanen "Functional Blocks".
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
				.register(output -> output.accept(QUARRY));
	}
}
