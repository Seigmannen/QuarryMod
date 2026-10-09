package com.seigmannen.quarrymod.client;

import com.seigmannen.quarrymod.block.ModBlocks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public class QuarryModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Tooltips vises bare på klienten, så de legges til her.
		// Fabric kaller denne hver gang en tooltip tegnes; vi legger til en grå linje på quarry-itemet.
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (stack.is(ModBlocks.QUARRY.asItem())) {
				lines.add(Component.translatable("block.quarrymod.quarry.tooltip").withStyle(ChatFormatting.GRAY));
			}
		});
	}
}
