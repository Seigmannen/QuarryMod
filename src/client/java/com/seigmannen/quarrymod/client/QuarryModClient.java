package com.seigmannen.quarrymod.client;

import com.seigmannen.quarrymod.block.ModBlocks;
import com.seigmannen.quarrymod.client.screen.QuarryScreen;
import com.seigmannen.quarrymod.menu.ModMenuTypes;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;

public class QuarryModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Kobler menytypen til skjermen, slik at klienten vet hvilken GUI den skal vise.
		MenuScreens.register(ModMenuTypes.QUARRY, QuarryScreen::new);

		// Tooltips vises bare på klienten, så de legges til her.
		// Fabric kaller denne hver gang en tooltip tegnes; vi legger til en grå linje på quarry-itemet.
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (stack.is(ModBlocks.QUARRY.asItem())) {
				lines.add(Component.translatable("block.quarrymod.quarry.tooltip").withStyle(ChatFormatting.GRAY));
			}
		});
	}
}
