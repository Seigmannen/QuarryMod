package com.seigmannen.quarrymod.compat;

import com.seigmannen.mineeverything.api.MineEverythingApi;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Alt som har med Mine (Almost) Everything å gjøre. Dette er den ENESTE klassen som refererer til MineEverythingApi.
 *
 * Moden er valgfri (suggests i fabric.mod.json). Java laster ikke MineEverythingApi før et kall til den faktisk kjøres,
 * og hvert kall står bak en sjekk av LOADED. Uten moden lastes klassen derfor aldri, og quarrien oppfører seg som i vanilla.
 */
public final class MineEverythingCompat {
	public static final String MOD_ID = "mineeverything";
	/** Om Mine (Almost) Everything er installert (endres ikke mens spillet kjører). */
	private static final boolean LOADED = FabricLoader.getInstance().isModLoaded(MOD_ID);

	/** Taggen fra Mine (Almost) Everything (bedrock, reinforced deepslate). Bare en tagg, så den er trygg uten moden. */
	private static final TagKey<Block> NETHERITE_MINEABLE =
			TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(MOD_ID, "netherite_mineable"));

	private MineEverythingCompat() {
	}

	/**
	 * Om quarrien kan mine en blokk som ikke kan knuses i vanilla (hardhet under 0, dvs. bedrock). Samme krav som
	 * for en spiller: blokken er i taggen, pickaxen er netherite, og /mae-innstillingene tillater den her
	 * (for bedrock på y = -64 i Overworld også "bottom_layer"). Alltid false uten moden.
	 */
	public static boolean canMineUnbreakable(Level level, BlockState state, BlockPos pos, ItemStack pickaxe) {
		return LOADED && state.is(NETHERITE_MINEABLE) && pickaxe.is(Items.NETHERITE_PICKAXE)
				&& MineEverythingApi.isAllowed(level, state, pos);
	}
}
