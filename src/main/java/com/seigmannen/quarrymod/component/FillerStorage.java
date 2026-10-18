package com.seigmannen.quarrymod.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;

/**
 * Innholdet i quarriens fyll-lager: en liste over item-typer og antall.
 * Brukes både når block entityen lagres og som data-komponent på quarry-itemet (som en shulker box).
 */
public record FillerStorage(List<Entry> entries) implements TooltipProvider {
	public static final Codec<FillerStorage> CODEC = Entry.CODEC.listOf().xmap(FillerStorage::new, FillerStorage::entries);
	public static final StreamCodec<RegistryFriendlyByteBuf, FillerStorage> STREAM_CODEC =
			Entry.STREAM_CODEC.apply(ByteBufCodecs.list()).map(FillerStorage::new, FillerStorage::entries);

	public FillerStorage {
		entries = List.copyOf(entries);
	}

	/** Totalt antall blokker. */
	public int total() {
		int total = 0;
		for (Entry entry : entries) {
			total += entry.count();
		}
		return total;
	}

	// Tooltip på quarry-itemet: "Filler: 4608" i grått.
	@Override
	public void addToTooltip(Item.TooltipContext context, Consumer<Component> consumer, TooltipFlag flag, DataComponentGetter components) {
		int total = total();
		if (total > 0) {
			consumer.accept(Component.translatable("item.quarrymod.quarry.filler", total).withStyle(ChatFormatting.GRAY));
		}
	}

	/** Én item-type og antallet av den. */
	public record Entry(Holder<Item> item, int count) {
		public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Item.CODEC.fieldOf("id").forGetter(Entry::item),
				ExtraCodecs.POSITIVE_INT.fieldOf("count").forGetter(Entry::count)
		).apply(instance, Entry::new));
		public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
				Item.STREAM_CODEC, Entry::item,
				ByteBufCodecs.VAR_INT, Entry::count,
				Entry::new);
	}
}
