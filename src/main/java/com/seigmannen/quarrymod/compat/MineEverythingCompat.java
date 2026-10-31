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

	/**
	 * Om den installerte versjonen har MineEverythingApi.canMineUnbreakable (fra 1.1.1). Settes til false første gang
	 * kallet feiler med NoSuchMethodError, og deretter brukes den gamle logikken under.
	 */
	private static volatile boolean hasCanMineUnbreakable = true;

	/** Om den installerte versjonen har MineEverythingApi.canHarvestWith. Settes til false ved NoSuchMethodError. */
	private static volatile boolean hasCanHarvestWith = true;

	/** Bare for eldre versjoner: taggen fra Mine (Almost) Everything (bedrock, reinforced deepslate). Trygg uten moden. */
	private static final TagKey<Block> NETHERITE_MINEABLE =
			TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(MOD_ID, "netherite_mineable"));

	private MineEverythingCompat() {
	}

	/**
	 * Om quarrien kan mine en blokk som ikke kan knuses i vanilla (hardhet under 0, dvs. bedrock). Samme regler som
	 * for en spiller, hentet fra Mine (Almost) Everything (blokk, pickaxe og /mae-innstillingene). Alltid false uten moden.
	 */
	public static boolean canMineUnbreakable(Level level, BlockState state, BlockPos pos, ItemStack pickaxe) {
		if (!LOADED) {
			return false;
		}
		if (hasCanMineUnbreakable) {
			try {
				return MineEverythingApi.canMineUnbreakable(level, state, pos, pickaxe);
			} catch (NoSuchMethodError e) {
				// Eldre Mine (Almost) Everything uten metoden: bruk den gamle logikken fra nå av.
				hasCanMineUnbreakable = false;
			}
		}
		// Gammel logikk: blokken er i taggen, pickaxen er netherite, og /mae tillater den her.
		return state.is(NETHERITE_MINEABLE) && pickaxe.is(Items.NETHERITE_PICKAXE)
				&& MineEverythingApi.isAllowed(level, state, pos);
	}

	/**
	 * Om Mine (Almost) Everything sier at pickaxen kan høste blokken (f.eks. cobweb), selv om vanilla-sjekken
	 * (isCorrectToolForDrops) sier nei. Alltid false uten moden, eller med en eldre versjon som mangler metoden.
	 */
	public static boolean canHarvestWith(Level level, BlockState state, BlockPos pos, ItemStack pickaxe) {
		if (!LOADED || !hasCanHarvestWith) {
			return false;
		}
		try {
			return MineEverythingApi.canHarvestWith(level, state, pos, pickaxe);
		} catch (NoSuchMethodError e) {
			// Eldre Mine (Almost) Everything uten metoden: bare vanilla-sjekken fra nå av.
			hasCanHarvestWith = false;
			return false;
		}
	}
}
