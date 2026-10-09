package com.seigmannen.quarrymod.menu;

import com.seigmannen.quarrymod.QuarryMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

/**
 * Registrerer alle menytypene (GUI-ene) i moden.
 * En MenuType forteller klienten hvilken meny (og dermed hvilken skjerm) den skal åpne.
 */
public final class ModMenuTypes {
	private ModMenuTypes() {
	}

	// QuarryMenu::new (int, Inventory) er klient-konstruktøren; serveren lager menyen via block entityen.
	public static final MenuType<QuarryMenu> QUARRY = Registry.register(
			BuiltInRegistries.MENU,
			QuarryMod.id("quarry"),
			new MenuType<>(QuarryMenu::new, FeatureFlags.VANILLA_SET)
	);

	/**
	 * Kalles fra QuarryMod.onInitialize(), slik at typen over blir registrert.
	 */
	public static void initialize() {
	}
}
