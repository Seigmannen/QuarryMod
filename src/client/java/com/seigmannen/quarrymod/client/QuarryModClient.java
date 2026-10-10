package com.seigmannen.quarrymod.client;

import com.seigmannen.quarrymod.client.screen.QuarryScreen;
import com.seigmannen.quarrymod.menu.ModMenuTypes;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;

public class QuarryModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Kobler menytypen til skjermen, slik at klienten vet hvilken GUI den skal vise.
		MenuScreens.register(ModMenuTypes.QUARRY, QuarryScreen::new);
	}
}
