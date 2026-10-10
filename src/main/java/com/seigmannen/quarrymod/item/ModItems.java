package com.seigmannen.quarrymod.item;

import com.seigmannen.quarrymod.QuarryMod;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Samler alle vanlige items i moden (oppgraderingene) og registrerer dem i spillet.
 */
public final class ModItems {
	private ModItems() {
	}

	/** Antall nivåer per oppgraderingstype (I–V). */
	public static final int UPGRADE_LEVELS = 5;

	/** Indeks 0 = nivå I, indeks 4 = nivå V. */
	public static final Item[] SPEED_UPGRADES = registerUpgrades("speed_upgrade");
	public static final Item[] AREA_UPGRADES = registerUpgrades("area_upgrade");

	/** Registrerer name_1 … name_5, stack-størrelse 16. */
	private static Item[] registerUpgrades(String name) {
		Item[] items = new Item[UPGRADE_LEVELS];
		for (int i = 0; i < UPGRADE_LEVELS; i++) {
			items[i] = register(name + "_" + (i + 1), new Item.Properties().stacksTo(16));
		}
		return items;
	}

	private static Item register(String name, Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, QuarryMod.id(name));
		// Itemet må kjenne sin egen ID før det opprettes (setId).
		return Registry.register(BuiltInRegistries.ITEM, key, new Item(properties.setId(key)));
	}

	/** Nivået (1–5) til en speed-oppgradering, eller 0 hvis stacken ikke er det. */
	public static int getSpeedLevel(ItemStack stack) {
		return getLevel(SPEED_UPGRADES, stack);
	}

	/** Nivået (1–5) til en area-oppgradering, eller 0 hvis stacken ikke er det. */
	public static int getAreaLevel(ItemStack stack) {
		return getLevel(AREA_UPGRADES, stack);
	}

	private static int getLevel(Item[] upgrades, ItemStack stack) {
		for (int i = 0; i < upgrades.length; i++) {
			if (stack.is(upgrades[i])) {
				return i + 1;
			}
		}
		return 0;
	}

	/** Kalles fra QuarryMod.onInitialize(), etter ModBlocks. */
	public static void initialize() {
		// Samme creative-fane som quarrien, rett etter den.
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(output -> {
			for (Item item : SPEED_UPGRADES) {
				output.accept(item);
			}
			for (Item item : AREA_UPGRADES) {
				output.accept(item);
			}
		});
	}
}
